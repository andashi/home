package de.mm20.launcher2.config.service

import android.content.Context
import android.util.Log
import de.mm20.launcher2.config.ConfigMigrations
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.WriteBackPlan
import de.mm20.launcher2.config.toLauncherConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException

/** What one write-back did to `launcher.json`. */
sealed class WriteBackResult {
    /** The file was replaced; [sha256] is the hash of the bytes now on disk. */
    data class Written(val sha256: String) : WriteBackResult()

    /** The file already says what the device has in effect; nothing was written. */
    data object Unchanged : WriteBackResult()

    /**
     * The device kept the change, the file was left alone. [code] is stable
     * and machine-readable, [reason] is for a log line or a snackbar.
     */
    data class Skipped(val code: String, val reason: String) : WriteBackResult()
}

/**
 * Writes a change made on the device back into `launcher.json`, for every
 * section (#3 slice 4; ADR 0003: the config is the source of truth in both
 * directions, and the device stays editable).
 *
 * Under the [ConfigFileLock], so that no reload runs in between:
 * 1. the file is read and parsed. A missing, broken, invalid, outdated or
 *    locked file is left alone: a broken file stays visibly broken and is
 *    never overwritten;
 * 2. the device's effective state is compared with the [AppliedBaseline],
 *    what the file produced when it was last applied - never with what the
 *    file literally says, so a value the launcher could not apply as written
 *    keeps its written value (D). Without a baseline for exactly this file
 *    nothing is written: the device state cannot be told apart from what the
 *    file produced, and guessing is how `h: 7` becomes `h: 6`;
 * 3. [WriteBackPlan] picks the keys the file already has (W1) and splices
 *    each changed value over its own span, so every other byte survives;
 * 4. the result is re-parsed as a safety net, written to a temp file and
 *    renamed onto `launcher.json`;
 * 5. a successful [ReloadReport] with [ReloadTrigger.SelfWrite] and the new
 *    hash is saved, which is how [ConfigWatcher] recognises the rename as
 *    the launcher's own, and the device state becomes the new baseline.
 *
 * A skip is a warning appended to the last reload report and exposed through
 * [lastResult], for the device and for provisioning. It never replaces that
 * report: the read-back provider and the startup check rely on it.
 */
class ConfigWriteBack(
    context: Context,
    private val configStore: ConfigStore,
    private val reportStore: ReloadReportStore,
    private val baselineStore: AppliedBaselineStore,
    private val lock: ConfigFileLock,
    private val fileProvider: () -> File? = { ConfigLocation.configFile(context.applicationContext) },
) {
    private val _lastResult = MutableStateFlow<WriteBackResult?>(null)

    /** The outcome of the most recent write-back, for the UI to show a skip. */
    val lastResult: StateFlow<WriteBackResult?> = _lastResult

    /** Writes back whatever the device changed. */
    suspend fun write(): WriteBackResult = lock.withLock { writeLocked(gridEdit = false) }

    /**
     * For a caller that already holds the [lock], because it changes the
     * device first ([GridWriteBack] writes the database). [gridEdit] says the
     * change was the home grid's, which a file that does not manage the grid
     * has to be told about.
     */
    internal suspend fun writeLocked(gridEdit: Boolean): WriteBackResult {
        val result = withContext(Dispatchers.IO) { writeFile(gridEdit) }
        if (result is WriteBackResult.Skipped) {
            Log.w(TAG, "launcher.json not written back (${result.code}): ${result.reason}")
            recordSkip(result)
        }
        _lastResult.value = result
        return result
    }

    private suspend fun writeFile(gridEdit: Boolean): WriteBackResult {
        val file = fileProvider()
            ?: return skipped("no-config-dir", "the config directory is unavailable")
        if (!file.exists()) {
            return skipped("no-config-file", "there is no ${file.name} to write into")
        }
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return skipped("read-failed", "could not read ${file.name}: ${e.message}")
        }
        val parsed = ConfigParser.parse(text)
        val config = parsed.config
            ?: return skipped("malformed-config", "${file.name} does not parse; it was left untouched")
        if (!parsed.isSuccess) {
            return skipped("invalid-config", "${file.name} has errors; it was left untouched")
        }
        val gridLocked = config.home?.grid?.locked == true
        if (gridEdit && gridLocked) {
            return skipped("locked", "home.grid.locked is true; nothing is written back")
        }
        val literal = ConfigParser.json.parseToJsonElement(text).jsonObject
        // The parsed config carries the migrated version; the declared one
        // is what is on disk. A v1 file is read through migration but never
        // written: current keys next to v1 keys would be a shape nobody can
        // regenerate from.
        val declared = (literal["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (declared != null && declared < ConfigMigrations.currentSchemaVersion) {
            return skipped(
                "schema-version-outdated",
                "${file.name} is schemaVersion $declared; the launcher reads it through migration " +
                    "but writes only the current schema (${ConfigMigrations.currentSchemaVersion}). " +
                    "Regenerate the file as schemaVersion ${ConfigMigrations.currentSchemaVersion} " +
                    "to enable write-back.",
            )
        }
        // W1: the file never grows a section. A grid edit on a file that does
        // not manage the grid stays on the device, and the person is told
        // how to make the file manage it (ADR 0003).
        if (gridEdit && literal.at(WriteBackPlan.GridLayoutsPath) == null) {
            return skipped(
                "grid-unmanaged",
                "${file.name} does not manage the home grid; add \"layouts\": {} under home.grid " +
                    "to keep the arrangement in the file (an empty \"grid\": {} is not enough: " +
                    "only keys the file has are written back)",
            )
        }

        val sha = text.toByteArray(Charsets.UTF_8).sha256Hex()
        val baseline = baselineStore.read()
            ?: return skipped(
                "no-baseline",
                "${file.name} has not been applied in this install yet; changes are written back after its next reload",
            )
        if (baseline.configSha256 != sha) {
            return skipped("not-applied-yet", "${file.name} changed since it was last applied; it is reloaded first")
        }

        val state = configStore.readState()
        val device = effectiveTree(state.toLauncherConfig())
        val changes = WriteBackPlan.changes(literal, baseline.effective, device, canonical = effectiveTree(config))
            // W2: a locked grid is not the device's to change, whatever else is.
            .filterNot { gridLocked && it.path.take(2) == listOf("home", "grid") }
        // A colour scheme a person made has no slug, so the read-back leaves
        // colors out and the plan writes nothing there: the file keeps what
        // it asked, and the report says why the effect differs (#3 slice 3).
        val keptColors = if (literal.at(ThemeColorsPath) != null && state.themeColors == null) {
            Diagnostic(
                Severity.Warning,
                SkipCodePrefix + "colors-custom",
                ThemeColorsPath.joinToString("."),
                "the device uses a colour scheme a person made, which appearance.theme.colors cannot name; " +
                    "the file keeps its value",
            )
        } else {
            null
        }
        if (changes.isEmpty()) {
            keptColors?.let { recordSkipWarning(it) }
            return WriteBackResult.Unchanged
        }

        val spliced = WriteBackPlan.splice(text, changes)
            ?: return skipped("malformed-config", "could not locate the changed keys in ${file.name}")
        if (spliced.toByteArray(Charsets.UTF_8).size > ConfigParser.MaxInputBytes) {
            return skipped("too-large", "the result would exceed the parser limit of ${ConfigParser.MaxInputBytes} bytes")
        }
        val check = ConfigParser.parse(spliced)
        if (check.config == null || !check.isSuccess) {
            // Must never happen: each splice replaces one value in a document
            // that parsed a moment ago. This is the net.
            Log.e(TAG, "write-back produced a document that does not parse; not written: ${check.diagnostics}")
            return skipped("write-back-produced-invalid-json", "internal error, nothing was written")
        }

        try {
            file.replaceAtomically(spliced, tempSuffix = ".wb")
        } catch (e: IOException) {
            return skipped("write-failed", "could not write ${file.name}: ${e.message}")
        }

        val written = spliced.toByteArray(Charsets.UTF_8).sha256Hex()
        // The baseline first, each record on its own (review on #155): what
        // the file now says is what the device state it was computed from has
        // in effect, and a report that fails to save must not leave the
        // baseline on the old hash, or every write-back after it skips.
        try {
            baselineStore.save(AppliedBaseline(written, device))
        } catch (e: Exception) {
            // The watcher sees an unknown hash and reloads, which records it.
            Log.w(TAG, "could not record the baseline of the self-write", e)
        }
        try {
            reportStore.save(
                ReloadReport(
                    success = true,
                    schemaVersion = ConfigMigrations.currentSchemaVersion,
                    appliedMutations = changes.map { it.path.joinToString(".") },
                    configSha256 = written,
                    trigger = ReloadTrigger.SelfWrite,
                    diagnostics = listOfNotNull(keptColors),
                )
            )
        } catch (e: Exception) {
            // The file is written; without the report the watcher reloads it
            // once, which converges to a no-op.
            Log.w(TAG, "could not record the self-write report", e)
        }
        return WriteBackResult.Written(written)
    }

    /** The skip, as a warning on the last reload report; the report itself stays what it was. */
    private suspend fun recordSkip(skip: WriteBackResult.Skipped) =
        recordSkipWarning(Diagnostic(Severity.Warning, SkipCodePrefix + skip.code, "", skip.reason))

    /** [warning] in place of any earlier skip warning on the last reload report. */
    private suspend fun recordSkipWarning(warning: Diagnostic) {
        try {
            val last = reportStore.read() ?: return
            if (last.diagnostics.any { it.code == warning.code && it.message == warning.message }) return
            val others = last.diagnostics.filterNot { it.code.startsWith(SkipCodePrefix) }
            reportStore.save(last.copy(diagnostics = others + warning))
        } catch (e: Exception) {
            Log.w(TAG, "could not record the skipped write-back", e)
        }
    }

    private fun skipped(code: String, reason: String) = WriteBackResult.Skipped(code, reason)



    companion object {
        private const val TAG = "ConfigWriteBack"

        /** A skipped write-back in the reload report: `write-back-skipped:<code>`. */
        const val SkipCodePrefix = "write-back-skipped:"

        private val ThemeColorsPath = listOf("appearance", "theme", "colors")
    }
}

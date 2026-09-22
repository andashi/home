package de.mm20.launcher2.config.service

import android.content.Context
import android.util.Log
import de.mm20.launcher2.config.ConfigMigrations
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.GridConfig
import de.mm20.launcher2.config.JsoncObjectSpan
import de.mm20.launcher2.config.JsoncSpanResult
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException

/** What one [GridWriteBack.write] did to `launcher.json`. */
sealed class WriteBackResult {
    /** The file was replaced; [sha256] is the hash of the bytes now on disk. */
    data class Written(val sha256: String) : WriteBackResult()

    /**
     * The database was updated but the file was left alone. [code] is
     * stable and machine-readable, [reason] is for a log line or a snackbar.
     */
    data class Skipped(val code: String, val reason: String) : WriteBackResult()
}

/**
 * Writes the home grid back into `launcher.json` after an edit-mode change
 * (ADR 0003, revised 2026-09-22: the config is the source of truth in both
 * directions).
 *
 * The protocol, in order, all of it under the [ConfigFileLock] so that no
 * reload runs between the two writes:
 * 1. the database first, through [HomeGridRepository.replace]: the screen
 *    is already right, the file is its mirror;
 * 2. the file is read and parsed; a missing,
 *    unparsable or `locked` file is left alone (a broken file stays visibly
 *    broken and is never overwritten);
 * 3. the effective `home.grid` from [ConfigStore.readState] is rendered and
 *    spliced over exactly the span [JsoncObjectSpan] finds, so every byte
 *    outside that object survives, comments included; a comment inside the
 *    old `home.grid` is lost, which is the one thing not preserved;
 * 4. the result is re-parsed as a safety net, written to a temp file and
 *    renamed onto `launcher.json`, the same way the ingest provider commits;
 * 5. a successful [ReloadReport] with [ReloadTrigger.SelfWrite] and the hash
 *    of the written bytes is saved, which is how [ConfigWatcher] recognises
 *    the rename as the launcher's own.
 *
 * Skips are reported through [lastResult] and the log, not through the
 * reload report: a skip must never overwrite the last good report, which is
 * what the read-back provider and the startup check rely on.
 */
class GridWriteBack(
    context: Context,
    private val repository: HomeGridRepository,
    private val configStore: ConfigStore,
    private val reportStore: ReloadReportStore,
    private val lock: ConfigFileLock,
    private val fileProvider: () -> File? = { ConfigLocation.configFile(context.applicationContext) },
) {
    private val _lastResult = MutableStateFlow<WriteBackResult?>(null)

    /** The outcome of the most recent [write], for the UI to show a skip. */
    val lastResult: StateFlow<WriteBackResult?> = _lastResult

    suspend fun write(layout: String, items: List<HomeGridItem>): WriteBackResult {
        // The lock first, then the database, then the file: a reload that
        // already read the old file must not apply its grid over the new
        // rows between the two writes (review on #68).
        val result = lock.withLock {
            repository.replace(layout, items)
            writeFile()
        }
        if (result is WriteBackResult.Skipped) {
            Log.w(TAG, "home.grid not written back (${result.code}): ${result.reason}")
        }
        _lastResult.value = result
        return result
    }

    private suspend fun writeFile(): WriteBackResult = withContext(Dispatchers.IO) {
        val file = fileProvider()
            ?: return@withContext skipped("no-config-dir", "the config directory is unavailable")
        if (!file.exists()) {
            return@withContext skipped("no-config-file", "there is no ${file.name} to write into")
        }
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return@withContext skipped("read-failed", "could not read ${file.name}: ${e.message}")
        }
        val parsed = ConfigParser.parse(text)
        val config = parsed.config
        if (config == null) {
            return@withContext skipped("malformed-config", "${file.name} does not parse; it was left untouched")
        }
        if (!parsed.isSuccess) {
            return@withContext skipped("invalid-config", "${file.name} has errors; it was left untouched")
        }
        if (config.home?.grid?.locked == true) {
            return@withContext skipped("locked", "home.grid.locked is true; nothing is written back")
        }
        // The parsed config carries the migrated version; the declared one
        // is what is on disk. A v1 file is read through migration but never
        // written: a v2 grid next to v1 keys would be a shape nobody can
        // regenerate from.
        val declared = declaredSchemaVersion(text)
        if (declared != null && declared < ConfigMigrations.currentSchemaVersion) {
            return@withContext skipped(
                "schema-version-outdated",
                "${file.name} is schemaVersion $declared; the launcher reads it through migration " +
                        "but writes only the current schema (${ConfigMigrations.currentSchemaVersion}). " +
                        "Regenerate the file as schemaVersion ${ConfigMigrations.currentSchemaVersion} " +
                        "to enable write-back.",
            )
        }

        val state = configStore.readState()
        val grid = GridConfig(columns = state.gridColumns, locked = state.gridLocked, layouts = state.gridLayouts)
        val rendered = ConfigParser.json.encodeToString(GridConfig.serializer(), grid)
        val spliced = splice(text, rendered)
            ?: return@withContext skipped("malformed-config", "could not locate home in ${file.name}")
        if (spliced.toByteArray(Charsets.UTF_8).size > ConfigParser.MaxInputBytes) {
            return@withContext skipped(
                "too-large",
                "the result would exceed the parser limit of ${ConfigParser.MaxInputBytes} bytes",
            )
        }
        val check = ConfigParser.parse(spliced)
        if (check.config == null || !check.isSuccess) {
            // Must never happen: the splice is a string replacement of one
            // value in a document that parsed a moment ago. This is the net.
            Log.e(TAG, "write-back produced a document that does not parse; not written")
            return@withContext skipped("write-back-produced-invalid-json", "internal error, nothing was written")
        }

        val tmp = File(file.parentFile, "${file.name}.wb")
        try {
            tmp.writeText(spliced)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return@withContext skipped("write-failed", "could not replace ${file.name}")
            }
        } catch (e: IOException) {
            tmp.delete()
            return@withContext skipped("write-failed", "could not write ${file.name}: ${e.message}")
        }

        val sha = spliced.toByteArray(Charsets.UTF_8).sha256Hex()
        try {
            reportStore.save(
                ReloadReport(
                    success = true,
                    schemaVersion = ConfigMigrations.currentSchemaVersion,
                    appliedMutations = listOf("home.grid"),
                    configSha256 = sha,
                    trigger = ReloadTrigger.SelfWrite,
                )
            )
        } catch (e: Exception) {
            // The file is written; without the report the watcher reloads it
            // once, which converges to a no-op.
            Log.w(TAG, "could not record the self-write report", e)
        }
        WriteBackResult.Written(sha)
    }

    private fun skipped(code: String, reason: String) = WriteBackResult.Skipped(code, reason)

    /** The `schemaVersion` as written in the file, before migration. Null when it cannot be read. */
    private fun declaredSchemaVersion(text: String): Int? = try {
        (ConfigParser.json.parseToJsonElement(text) as? JsonObject)
            ?.get("schemaVersion")?.let { it as? JsonPrimitive }?.intOrNull
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Replaces the value of `home.grid`, or inserts `grid` into `home`, or
     * inserts `home` with a `grid` at the root. Null when the document offers
     * no place to put it.
     */
    private fun splice(text: String, rendered: String): String? {
        return when (val r = JsoncObjectSpan.find(text, listOf("home", "grid"))) {
            is JsoncSpanResult.Found ->
                text.replaceRange(r.start, r.endExclusive, reindent(rendered, JsoncObjectSpan.lineIndent(text, r.start)))

            is JsoncSpanResult.Insert ->
                insertMember(text, r, "grid", reindent(rendered, r.indent))

            is JsoncSpanResult.MissingParent -> {
                if (r.depth != 0) return null
                val root = JsoncObjectSpan.find(text, listOf("home")) as? JsoncSpanResult.Insert ?: return null
                val inner = root.indent + "  "
                val home = "{\n" + inner + "\"grid\": " + reindent(rendered, inner) + "\n" + root.indent + "}"
                insertMember(text, root, "home", home)
            }

            JsoncSpanResult.Malformed -> null
        }
    }

    /** [value] is already indented for the member's line. */
    private fun insertMember(text: String, at: JsoncSpanResult.Insert, key: String, value: String): String {
        val sb = StringBuilder(text.length + value.length + 32)
        sb.append(text, 0, at.at)
        if (at.needsComma) sb.append(',')
        sb.append('\n').append(at.indent).append('"').append(key).append("\": ").append(value)
        // A closing brace on the same line (an empty or one-line object)
        // gets its own line, so the result reads like the rest of the file.
        val rest = text.substring(at.at)
        if (rest.trimStart(' ', '\t').startsWith("}")) sb.append('\n').append(at.closingIndent)
        sb.append(rest)
        return sb.toString()
    }

    /** Pretty JSON is rendered at column 0; every line after the first moves under [indent]. */
    private fun reindent(rendered: String, indent: String): String {
        val lines = rendered.split('\n')
        if (lines.size == 1) return rendered
        return lines[0] + lines.drop(1).joinToString("") { "\n" + indent + it }
    }

    private companion object {
        const val TAG = "GridWriteBack"
    }
}

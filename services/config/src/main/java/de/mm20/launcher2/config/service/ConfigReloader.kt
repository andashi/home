package de.mm20.launcher2.config.service

import android.util.Log
import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.diagnosticCodeOf
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.toLauncherConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Fork addition (Phase 2, ADR 0003): drives one config reload end to end —
 * parse, diff against current state, apply, persist a [ReloadReport].
 *
 * Reloads are serialized on the [ConfigFileLock]: a reload that arrives while
 * another one is running waits for it to finish, so writes of two reloads
 * never interleave, and neither does a write-back ([GridWriteBack]) with a
 * reload.
 *
 * Failure semantics:
 * - malformed input or validation errors → nothing is applied, failed report;
 * - unknown-key warnings → applied normally, recorded in the report;
 * - per-section apply failures → error diagnostics, remaining sections are
 *   still applied, report is not successful and the failed sections are
 *   excluded from [ReloadReport.appliedMutations].
 */
class ConfigReloader(
    private val configStore: ConfigStore,
    private val reportStore: ReloadReportStore,
    private val lock: ConfigFileLock = ConfigFileLock(),
    private val baselineStore: AppliedBaselineStore? = null,
    private val capabilities: CapabilityDiagnostics = CapabilityDiagnostics.None,
    /** How the file writes the apps of each list that names them (AppNaming): `apps`, `tags`. */
    private val namings: List<AppNaming> = emptyList(),
) {

    /**
     * Reloads from [configText].
     */
    suspend fun reload(
        configText: String,
        trigger: ReloadTrigger? = null,
    ): ReloadReport = lock.withLock {
        reloadLocked(configText, configText.toByteArray(Charsets.UTF_8).sha256Hex(), trigger)
    }

    /**
     * Reloads from [file]. An unreadable file produces a failed report;
     * nothing is applied.
     */
    suspend fun reload(
        file: File,
        trigger: ReloadTrigger? = null,
    ): ReloadReport = lock.withLock { reloadFileLocked(file, trigger) }

    /**
     * Reloads [file] if [condition] holds, deciding under the reload lock:
     * a reload already running finishes, and reports, before [condition]
     * reads anything. Decided outside the lock, an app arrival could read the
     * report of before a running reload that is about to report the app
     * missing, skip, and be spent (review on #213). Null when it did not reload.
     */
    suspend fun reloadIf(
        file: File,
        trigger: ReloadTrigger,
        condition: suspend () -> Boolean,
    ): ReloadReport? = lock.withLock {
        if (condition()) reloadFileLocked(file, trigger) else null
    }

    private suspend fun reloadFileLocked(file: File, trigger: ReloadTrigger?): ReloadReport {
        val text = try {
            withContext(Dispatchers.IO) { file.readText() }
        } catch (e: IOException) {
            return persistUnlessRepeated(
                ReloadReport(
                    success = false,
                    diagnostics = listOf(
                        Diagnostic(
                            DiagnosticCode.ReadFailed,
                            "",
                            "Could not read config file ${file.name}: " +
                                    (e.message ?: e.javaClass.simpleName),
                        )
                    ),
                    errorMessage = e.message,
                    trigger = trigger,
                )
            )
        } catch (e: SecurityException) {
            return persistUnlessRepeated(
                ReloadReport(
                    success = false,
                    diagnostics = listOf(
                        Diagnostic(
                            DiagnosticCode.ReadFailed,
                            "",
                            "Could not read config file ${file.name}: " +
                                    (e.message ?: e.javaClass.simpleName),
                        )
                    ),
                    errorMessage = e.message,
                    trigger = trigger,
                )
            )
        }
        return reloadLocked(text, text.toByteArray(Charsets.UTF_8).sha256Hex(), trigger)
    }

    private suspend fun reloadLocked(
        configText: String,
        configSha256: String,
        trigger: ReloadTrigger?,
    ): ReloadReport {
        val parseResult = ConfigParser.parse(configText)
        val config = parseResult.config
        if (config == null || !parseResult.isSuccess) {
            return persistUnlessRepeated(
                ReloadReport(
                    success = false,
                    schemaVersion = config?.schemaVersion,
                    diagnostics = parseResult.diagnostics,
                    errorMessage = parseResult.diagnostics
                        .firstOrNull { it.severity == Severity.Error }?.message,
                    configSha256 = configSha256,
                    trigger = trigger,
                )
            )
        }

        val (before, mutations) = try {
            configStore.readState().let { state ->
                // A new measurement fits the layouts the file names even where
                // the store agrees with the file: a layout kept as written
                // before its rows were known is exactly that (GridRowsSource).
                // An app arrival too: a widget whose provider was missing is
                // kept as written, so after its provider arrives the store
                // agrees with the file and only a forced grid looks it up
                // again (review on #213).
                val forceGrid = trigger == ReloadTrigger.GridMeasured || trigger == ReloadTrigger.AppsChanged
                val compared = if (forceGrid) state.copy(gridInitialized = false) else state
                state to ConfigDiffer.diff(config, compared)
            }
        } catch (e: Exception) {
            return persistUnlessRepeated(
                ReloadReport(
                    success = false,
                    schemaVersion = config.schemaVersion,
                    diagnostics = parseResult.diagnostics + Diagnostic(
                        DiagnosticCode.ReadStateFailed,
                        "",
                        "Could not read current launcher state: " +
                                (e.message ?: e.javaClass.simpleName),
                    ),
                    errorMessage = e.message,
                    configSha256 = configSha256,
                    trigger = trigger,
                )
            )
        }

        val applied = try {
            // The capture is only for the baseline; without a store for one, a plain apply.
            if (baselineStore != null) configStore.applyAndCapture(mutations)
            else ConfigStore.Applied(configStore.apply(mutations), before, emptySet())
        } catch (e: Exception) {
            ConfigStore.Applied(
                diagnostics = listOf(
                    Diagnostic(
                        DiagnosticCode.ApplyFailed,
                        "",
                        "Could not apply config mutations: " +
                                (e.message ?: e.javaClass.simpleName),
                    )
                ),
                written = before,
                sections = emptySet(),
            )
        }
        val applyDiagnostics = applied.diagnostics

        val failedSections = applyDiagnostics
            .filter { it.severity == Severity.Error }
            .map { it.path }
        val appliedSections = mutations
            .map { it.section }
            .distinct()
            .filter { section -> failedSections.none { it.isInSection(section) } }

        // A failure covers a key when the key lies inside the failed path; one
        // with no path is the whole apply's, and covers every key.
        val capabilityDiagnostics = capabilities.of(config, before) { keyPath ->
            failedSections.any { it.isEmpty() || keyPath.isInSection(it) }
        }

        recordBaseline(configSha256, before, applied)
        val report = ReloadReport(
            success = applyDiagnostics.none { it.severity == Severity.Error },
            schemaVersion = config.schemaVersion,
            diagnostics = parseResult.diagnostics + applyDiagnostics + capabilityDiagnostics,
            appliedMutations = appliedSections,
            configSha256 = configSha256,
            trigger = trigger,
        )
        if (report.success) recordAppsForm()
        // A measurement reload that has nothing new to say leaves the last
        // report alone: that report is what a push is waited on by, and it
        // still describes the device. Nothing new means the last report is of
        // this very file with the same diagnostics - a correction or a
        // capability warning that appeared or went away is news, and a failed
        // report differs by its error - only the forced layouts were applied
        // (a grid setting in SetGrid is a correction the differ found), and
        // the fit changed no layout. A measurement reload that met a newly
        // pushed file has to say so (#178 review).
        //
        // An app arrival reload that changed nothing leaves it too: it runs on
        // every package signal while something the file names is absent. Judged
        // by the state read back, not by what was applied - an absent favourite
        // is skipped, never stored, and applied again every time. So does a
        // startup check that says the same as the last report: one that keeps
        // failing runs at every start, the retry that heals the device (#261).
        val noOp = when (trigger) {
            ReloadTrigger.GridMeasured ->
                mutations.all { it.isForcedLayoutsOnly() } && sameAsLast(report) && !changedSince(before) { it.gridLayouts }
            ReloadTrigger.AppsChanged, ReloadTrigger.StartupCheck ->
                sameAsLast(report) && !changedSince(before) { it }
            else -> false
        }
        if (noOp) return report
        return persist(report)
    }

    /**
     * What this file produced once applied, for a write-back to compare the
     * device with (#3 slice 4, D). The rule for every capture point: a value
     * is taken at the moment it was written, never by a later read. So each
     * section the apply wrote comes from the write itself
     * ([ConfigStore.applyAndCapture]) - a clamp or a skipped entry is in it,
     * a change a person makes right after the write is not - and every other
     * section, a failed one included, from the state read [before] the apply
     * (see [baselineOf]).
     */
    private suspend fun recordBaseline(configSha256: String, before: ConfigState, applied: ConfigStore.Applied) {
        val store = baselineStore ?: return
        try {
            val effective = baselineOf(
                effectiveTree(before.toLauncherConfig()),
                effectiveTree(applied.written.toLauncherConfig()),
                applied.sections,
            )
            store.save(AppliedBaseline(configSha256, effective))
        } catch (_: Exception) {
            // Without a baseline a write-back skips and says so; the reload stands.
        }
    }

    /**
     * Write-back waits until the form the file writes its apps in is recorded
     * (AppNaming). An apply of `apps` records it; a reload that went through
     * without one left the device reading the file's apps the same in either
     * form, so the empty record is true then. Made by every such reload, not
     * only the startup one: a fresh install has no file at startup, and its
     * first push may bring no apps to apply (review on #214). Under the lock,
     * so a write-back waiting on it finds the record.
     */
    private suspend fun recordAppsForm() {
        for (naming in namings) {
            try {
                if (!naming.recorded()) naming.replace(emptyMap())
            } catch (e: Exception) {
                // Without the record a write-back skips and says so; the next reload tries again.
                Log.w(TAG, "could not record the apps' form", e)
            }
        }
    }

    private suspend fun lastReport(): ReloadReport? = try {
        reportStore.read()
    } catch (e: Exception) {
        null
    }

    private fun ConfigMutation.isForcedLayoutsOnly(): Boolean =
        this is ConfigMutation.SetGrid && columns == null && locked == null && labels == null

    /**
     * Whether the last report is of this very file and says the same, leaving out
     * write-back's own notes - its skips and kept-value warnings, which it puts on
     * the last report itself (ADR 0003, #261).
     */
    private suspend fun sameAsLast(report: ReloadReport): Boolean {
        val last = lastReport() ?: return false
        return last.configSha256 == report.configSha256 &&
            last.diagnostics.withoutWriteBackNotes() == report.diagnostics.withoutWriteBackNotes()
    }

    private fun List<Diagnostic>.withoutWriteBackNotes() =
        filterNot { diagnosticCodeOf(it.code) == DiagnosticCode.WriteBackSkipped }

    /**
     * A failure before anything was applied - an unreadable file, one that does not
     * parse, an unreadable state - saved unless a reload the launcher started itself
     * found it exactly as before. Nothing was applied, so the device did not change.
     */
    private suspend fun persistUnlessRepeated(report: ReloadReport): ReloadReport =
        if (report.trigger in SelfStarted && sameAsLast(report)) report else persist(report)

    private companion object {
        /** The reloads the launcher starts itself; the others were asked for and always report. */
        val SelfStarted = setOf(ReloadTrigger.StartupCheck, ReloadTrigger.GridMeasured, ReloadTrigger.AppsChanged)
    }

    /** Whether [part] of what the store holds now differs from [before]; unreadable counts as changed. */
    private suspend fun changedSince(before: ConfigState, part: (ConfigState) -> Any): Boolean = try {
        part(configStore.readState()) != part(before)
    } catch (e: Exception) {
        true
    }

    /** The report as saved - numbered by the store - or as built when saving failed. */
    private suspend fun persist(report: ReloadReport): ReloadReport {
        return try {
            reportStore.save(report)
        } catch (_: Exception) {
            // Report persistence must not fail the reload itself.
            report
        }
    }

    private fun String.isInSection(section: String): Boolean {
        return this == section || startsWith("$section.") || startsWith("$section[")
    }
}

private const val TAG = "ConfigReloader"

package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
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
    ): ReloadReport = lock.withLock {
        val text = try {
            withContext(Dispatchers.IO) { file.readText() }
        } catch (e: IOException) {
            return@withLock persist(
                ReloadReport(
                    success = false,
                    diagnostics = listOf(
                        Diagnostic(
                            Severity.Error,
                            "read-failed",
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
            return@withLock persist(
                ReloadReport(
                    success = false,
                    diagnostics = listOf(
                        Diagnostic(
                            Severity.Error,
                            "read-failed",
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
        reloadLocked(text, text.toByteArray(Charsets.UTF_8).sha256Hex(), trigger)
    }

    private suspend fun reloadLocked(
        configText: String,
        configSha256: String,
        trigger: ReloadTrigger?,
    ): ReloadReport {
        val parseResult = ConfigParser.parse(configText)
        val config = parseResult.config
        if (config == null || !parseResult.isSuccess) {
            return persist(
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
            configStore.readState().let { it to ConfigDiffer.diff(config, it) }
        } catch (e: Exception) {
            return persist(
                ReloadReport(
                    success = false,
                    schemaVersion = config.schemaVersion,
                    diagnostics = parseResult.diagnostics + Diagnostic(
                        Severity.Error,
                        "read-state-failed",
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
                        Severity.Error,
                        "apply-failed",
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

        recordBaseline(configSha256, before, applied)
        return persist(
            ReloadReport(
                success = applyDiagnostics.none { it.severity == Severity.Error },
                schemaVersion = config.schemaVersion,
                diagnostics = parseResult.diagnostics + applyDiagnostics,
                appliedMutations = appliedSections,
                configSha256 = configSha256,
                trigger = trigger,
            )
        )
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

    private suspend fun persist(report: ReloadReport): ReloadReport {
        try {
            reportStore.save(report)
        } catch (_: Exception) {
            // Report persistence must not fail the reload itself.
        }
        return report
    }

    private fun String.isInSection(section: String): Boolean {
        return this == section || startsWith("$section.") || startsWith("$section[")
    }
}

package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fork addition (Phase 2, ADR 0003): abstraction over the launcher's mutable
 * state for config convergence. Reads the current effective state as a
 * [ConfigState] and applies the [ConfigMutation]s produced by `ConfigDiffer`.
 *
 * Implementations must keep [apply] idempotent: applying the same mutation
 * list twice must converge to the same state.
 */
interface ConfigStore {

    /**
     * Reads the current effective state across settings, the home grid,
     * the pinned favorites and the managed wallpaper.
     */
    suspend fun readState(): ConfigState

    /**
     * Applies [mutations]. Sections that fail produce [Severity.Error]
     * diagnostics and are skipped; the remaining sections are still applied.
     * Recoverable skips (unknown apps, unsupported widgets) are reported as
     * diagnostics as well.
     */
    suspend fun apply(mutations: List<ConfigMutation>): List<Diagnostic>

    /**
     * Applies [mutations] and says what the apply wrote, for the baseline a
     * write-back compares with (#3 slice 4): [Applied.written] holds each
     * section in [Applied.sections] as it was written, clamps and skipped
     * entries included, captured at the write and not by a later read - a
     * change a person makes on the device right after the write must not be
     * taken for the file's. A section whose write failed is not among them.
     *
     * The default reads the state after the apply, which leaves that window
     * open; the store the launcher runs on overrides it.
     */
    suspend fun applyAndCapture(mutations: List<ConfigMutation>): Applied {
        val diagnostics = apply(mutations)
        val failed = diagnostics.filter { it.severity == Severity.Error }.map { it.path }
        val sections = mutations.map { it.section }.distinct()
            .filter { section -> failed.none { it == section || it.startsWith("$section.") || it.startsWith("$section[") } }
        return Applied(diagnostics, readState(), sections.toSet())
    }

    /** What [applyAndCapture] did: its diagnostics, and the [sections] it wrote as [written] has them. */
    data class Applied(val diagnostics: List<Diagnostic>, val written: ConfigState, val sections: Set<String>)

    /**
     * Emits at least once on collection and then whenever the effective
     * state changes, whoever changed it - the device or a reload - for
     * write-back to follow (#3 slice 4). It may emit for a change that turns
     * out to be none; a write-back then finds nothing to write. The default
     * emits once and never again (#167).
     */
    fun changes(): Flow<Unit> = flowOf(Unit)
}

package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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
     * Emits once on collection and then whenever the effective state
     * changes, whoever changed it - the device or a reload - for write-back
     * to follow (#3 slice 4).
     */
    fun changes(): Flow<Unit> = emptyFlow()
}

package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.SearchActionConfig
import de.mm20.launcher2.searchactions.SearchActionRepository

/**
 * The device's search actions in the contract's form (#106): what the config
 * store reads back and what `search.actions` replaces.
 */
interface SearchActionStore {
    /** The actions in effect, in order. */
    suspend fun read(): List<SearchActionConfig>

    /**
     * Replaces the device's actions with [actions], in order. An entry the
     * device cannot apply is left out and reported at `[basePath][index]`.
     */
    suspend fun replace(actions: List<SearchActionConfig>, basePath: String): List<Diagnostic>
}

/** The store over the launcher's search-action repository. Stub until the fix. */
internal class AndroidSearchActionStore(
    private val context: Context,
    private val repository: SearchActionRepository,
) : SearchActionStore {
    override suspend fun read(): List<SearchActionConfig> = emptyList()

    override suspend fun replace(actions: List<SearchActionConfig>, basePath: String): List<Diagnostic> = emptyList()
}

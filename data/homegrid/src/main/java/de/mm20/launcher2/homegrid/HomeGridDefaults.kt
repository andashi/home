package de.mm20.launcher2.homegrid

/**
 * Remembers that the grid has been given its first content, by the default
 * row below or by a config that applied `home.grid`. Once set, an empty
 * layout stays empty: a user who removed everything, or a config that says
 * `items: []`, is not second-guessed.
 */
interface HomeGridInitFlag {
    suspend fun isInitialized(): Boolean
    suspend fun markInitialized()
}

/**
 * The only default the grid has (decided 2026-09-22, PR 5b): a launcher
 * that starts without any config shows the favorites widget in the bottom
 * row, full width, so the screen is not empty. There is no migration of an
 * older widget column because there was never a stable release to migrate
 * from.
 */
object HomeGridDefaults {
    const val FavoritesId = "dock"

    /**
     * Writes the favorites row into [layout] when the grid has never been
     * initialised and both layouts are empty; a no-op otherwise. Returns the
     * items written (empty when nothing was).
     */
    suspend fun ensureFavoritesRow(
        repository: HomeGridRepository,
        flag: HomeGridInitFlag,
        layout: String,
        columns: Int,
        rows: Int,
    ): List<HomeGridItem> {
        TODO("PR 5b")
    }
}

package de.mm20.launcher2.homegrid

import kotlinx.coroutines.flow.first

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
     * initialised and both layouts are empty; a no-op otherwise. A grid that
     * already holds something is marked initialised without being touched.
     * Returns the items written (empty when nothing was).
     */
    suspend fun ensureFavoritesRow(
        repository: HomeGridRepository,
        flag: HomeGridInitFlag,
        lock: HomeGridInitLock,
        layout: String,
        columns: Int,
        rows: Int,
    ): List<HomeGridItem> {
        if (flag.isInitialized()) return emptyList()
        val populated = listOf(HomeGridLayouts.Phone, HomeGridLayouts.Fold)
            .any { repository.observe(it).first().isNotEmpty() }
        if (populated) {
            flag.markInitialized()
            return emptyList()
        }
        val dock = HomeGridItem(
            layout = layout,
            id = FavoritesId,
            widget = HomeGridWidgets.Favorites,
            x = 0,
            y = (rows - 1).coerceAtLeast(0),
            w = columns,
            h = 1,
            position = 0,
        )
        repository.replace(layout, listOf(dock))
        flag.markInitialized()
        return listOf(dock)
    }
}

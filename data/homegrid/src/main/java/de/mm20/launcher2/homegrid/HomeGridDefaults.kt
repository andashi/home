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
 * that starts without any config shows the favorites widget, so the screen
 * is not empty: on a phone the bottom row, full width; on the fold the right
 * edge column, full height (decided 2026-09-24, #93). There is no migration of an
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
    ): List<HomeGridItem> = lock.withLock {
        // Read, decide and write under the lock a config reload takes too:
        // a first start and a provisioning push can coincide, and a stale
        // "empty" read must not overwrite what the reload just provisioned.
        if (flag.isInitialized()) return@withLock emptyList()
        val populated = listOf(HomeGridLayouts.Phone, HomeGridLayouts.Fold)
            .any { repository.observe(it).first().isNotEmpty() }
        if (populated) {
            flag.markInitialized()
            return@withLock emptyList()
        }
        // The phone's dock is the bottom row. The fold's is the right edge,
        // full height (#93): the right edge of both displays, in the same
        // place when the device opens; its favorites are centred (#111).
        val fold = layout == HomeGridLayouts.Fold
        val dock = HomeGridItem(
            layout = layout,
            id = FavoritesId,
            widget = HomeGridWidgets.Favorites,
            x = if (fold) (columns - 1).coerceAtLeast(0) else 0,
            y = if (fold) 0 else (rows - 1).coerceAtLeast(0),
            w = if (fold) 1 else columns,
            h = if (fold) rows.coerceAtLeast(1) else 1,
            position = 0,
        )
        repository.replace(layout, listOf(dock))
        flag.markInitialized()
        listOf(dock)
    }
}

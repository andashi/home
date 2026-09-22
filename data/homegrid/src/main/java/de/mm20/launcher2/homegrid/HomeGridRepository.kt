package de.mm20.launcher2.homegrid

import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.HomeGridItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * The home grid's persistence, one layout at a time.
 *
 * Bound as a Koin `single` on purpose: there is exactly one writer, so the
 * order of [replace] and the patch calls is the order they were made in. The
 * older `WidgetRepository` is a factory with a per-instance write queue, which
 * is the shape this repository deliberately does not copy.
 */
interface HomeGridRepository {
    /** The items of [layout] ordered by [HomeGridItem.position]; emits on every change. */
    fun observe(layout: String): Flow<List<HomeGridItem>>

    /** Replaces every item of [layout] in one transaction; returns after commit. */
    suspend fun replace(layout: String, items: List<HomeGridItem>)

    /** Changes only the cell rectangle of one item. */
    suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int)

    /** Records (or clears, with null) the device-local AppWidget host id of one item. */
    suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?)

    suspend fun delete(layout: String, id: String)
}

internal class HomeGridRepositoryImpl(
    private val database: AppDatabase,
) : HomeGridRepository {

    override fun observe(layout: String): Flow<List<HomeGridItem>> {
        TODO("PR 2 implementation commit")
    }

    override suspend fun replace(layout: String, items: List<HomeGridItem>) {
        TODO("PR 2 implementation commit")
    }

    override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) {
        TODO("PR 2 implementation commit")
    }

    override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) {
        TODO("PR 2 implementation commit")
    }

    override suspend fun delete(layout: String, id: String) {
        TODO("PR 2 implementation commit")
    }
}

internal fun HomeGridItemEntity.toDomain(): HomeGridItem {
    TODO("PR 2 implementation commit")
}

internal fun HomeGridItem.toEntity(): HomeGridItemEntity {
    TODO("PR 2 implementation commit")
}

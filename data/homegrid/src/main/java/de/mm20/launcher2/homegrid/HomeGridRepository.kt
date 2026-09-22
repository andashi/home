package de.mm20.launcher2.homegrid

import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.HomeGridItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The home grid's persistence, one layout at a time.
 *
 * Bound as a Koin `single` on purpose, and every mutation runs under one
 * [Mutex]: two mutations never interleave, and a caller that awaits one
 * before starting the next sees them applied in that order. What the mutex
 * does not promise is an order between callers that race each other; that
 * is decided by who takes the lock first, as with any lock. The older
 * `WidgetRepository` is a factory with a per-instance write queue, which is
 * the shape this repository deliberately does not copy.
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

    private val dao get() = database.homeGridItemDao()

    /** One writer: mutations are applied whole, never interleaved. */
    private val writes = Mutex()

    override fun observe(layout: String): Flow<List<HomeGridItem>> {
        return dao.queryLayout(layout).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun replace(layout: String, items: List<HomeGridItem>) {
        writes.withLock { dao.replaceLayout(layout, items.map { it.toEntity() }) }
    }

    override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) {
        writes.withLock { dao.patchGeometry(layout, id, x, y, w, h) }
    }

    override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) {
        writes.withLock { dao.setAppWidgetId(layout, id, appWidgetId) }
    }

    override suspend fun delete(layout: String, id: String) {
        writes.withLock { dao.deleteItem(layout, id) }
    }
}

/**
 * Tolerant on read: a key this build does not know is ignored (a newer build
 * may have written it), and a column that does not parse at all yields the
 * defaults. A row is never dropped because of its config.
 */
private val configJson = Json { ignoreUnknownKeys = true }

internal fun HomeGridItemEntity.toDomain(): HomeGridItem {
    val config = config?.takeIf { it.isNotBlank() }?.let {
        try {
            configJson.decodeFromString(HomeGridItemConfig.serializer(), it)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: HomeGridItemConfig()
    return HomeGridItem(
        layout = layout,
        id = id,
        widget = widget,
        profile = profile,
        x = x, y = y, w = w, h = h,
        appWidgetId = appWidgetId,
        config = config,
        position = position,
    )
}

internal fun HomeGridItem.toEntity(): HomeGridItemEntity {
    return HomeGridItemEntity(
        layout = layout,
        id = id,
        widget = widget,
        profile = profile,
        x = x, y = y, w = w, h = h,
        appWidgetId = appWidgetId,
        config = configJson.encodeToString(HomeGridItemConfig.serializer(), config),
        position = position,
    )
}

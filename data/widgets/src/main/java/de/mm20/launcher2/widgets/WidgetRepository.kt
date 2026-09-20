package de.mm20.launcher2.widgets

import androidx.room.withTransaction
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.ktx.jsonObjectOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface WidgetRepository {
    fun get(parent: UUID? = null, limit: Int = 100, offset: Int = 0): Flow<List<Widget>>
    fun update(widget: Widget)
    fun create(widget: Widget, position: Int, parentId: UUID? = null)
    fun delete(widget: Widget)
    fun set(widgets: List<Widget>, parentId: UUID? = null)

    /**
     * Fork addition (Phase 2 config reload, ADR 0003): awaited variant of [set].
     * Replaces all widgets under [parentId] in a single transaction and returns
     * only after it has committed, so the new widget set is visible immediately
     * afterwards. List order is preserved (ascending position).
     */
    suspend fun setAwaited(widgets: List<Widget>, parentId: UUID? = null)

    fun exists(type: String): Flow<Boolean>
    fun count(type: String): Flow<Int>
}

internal class WidgetRepositoryImpl(
    private val database: AppDatabase,
) : WidgetRepository {

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    // Fork addition (Phase 2 config reload): one ordered writer for full
    // replacements. Both set() and setAwaited() enqueue at call time, so a
    // fire-and-forget set(A) issued before setAwaited(B) can never land after
    // B and undo it; setAwaited() returns once its own entry has committed.
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) write()
        }
    }

    override fun get(parent: UUID?, limit: Int, offset: Int): Flow<List<Widget>> {
        val dao = database.widgetDao()
        return if (parent == null) {
            dao.queryRoot(limit, offset)
        } else {
            dao.queryByParent(parent, limit, offset)
        }.map {
            it.mapNotNull { Widget.fromDatabaseEntity(it) }
        }
    }

    override fun update(widget: Widget) {
        val dao = database.widgetDao()
        scope.launch {
            dao.patch(widget.toDatabaseEntity())
        }
    }

    override fun create(widget: Widget, position: Int, parentId: UUID?) {
        val dao = database.widgetDao()
        scope.launch {
            val entity = widget.toDatabaseEntity(position = position, parentId = parentId)
            dao.insert(entity)
        }
    }

    override fun delete(widget: Widget) {
        val dao = database.widgetDao()
        scope.launch {
            dao.delete(widget.id)
        }
    }

    override fun set(widgets: List<Widget>, parentId: UUID?) {
        writes.trySend { setInternal(widgets, parentId) }
    }

    // Fork addition (Phase 2 config reload): awaited variant, see interface.
    override suspend fun setAwaited(widgets: List<Widget>, parentId: UUID?) {
        val done = CompletableDeferred<Unit>()
        writes.trySend {
            try {
                setInternal(widgets, parentId)
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        done.await()
    }

    private suspend fun setInternal(widgets: List<Widget>, parentId: UUID?) {
        val dao = database.widgetDao()
        database.withTransaction {
            if (parentId == null) {
                dao.deleteRoot()
            } else {
                dao.deleteByParent(parentId)
            }
            dao.insert(widgets.mapIndexed { index, widget ->
                widget.toDatabaseEntity(position = index, parentId = parentId)
            })
        }
    }

    override fun exists(type: String): Flow<Boolean> {
        val dao = database.widgetDao()
        return dao.exists(type = type)
    }

    override fun count(type: String): Flow<Int> {
        val dao = database.widgetDao()
        return dao.count(type = type)

    }


}
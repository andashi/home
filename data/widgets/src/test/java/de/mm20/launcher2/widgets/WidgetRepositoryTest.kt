package de.mm20.launcher2.widgets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Characterization tests for the existing fire-and-forget widget API plus tests
 * for the fork's awaited [WidgetRepository.setAwaited].
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: WidgetRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = WidgetRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun <T> awaitValue(
        timeoutMs: Long = 5000,
        block: suspend () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for expected database state")
            }
            delay(20)
        }
    }

    private fun rootIds() = database.widgetDao().queryRoot(100, 0)
    private fun childIds(parent: UUID) = database.widgetDao().queryByParent(parent, 100, 0)

    @Test
    fun `setAwaited after a pending set wins and its result is visible on return`() = runBlocking {
        val a = AppsWidget(UUID.randomUUID())
        val b = MusicWidget(UUID.randomUUID())
        repeat(20) {
            repository.set(listOf(a))
            repository.setAwaited(listOf(b))
            assertEquals(listOf(b.id), rootIds().first().map { it.id })
        }
    }

    // ----- characterization of the existing async API -----

    @Test
    fun create_assignsPositionAndParent() = runBlocking {
        val parent = UUID.randomUUID()
        val widget = AppsWidget(UUID.randomUUID())
        repository.create(widget, position = 3, parentId = parent)
        val entity = awaitValue {
            childIds(parent).firstOrNull()?.firstOrNull { it.id == widget.id }
        }
        assertEquals(3, entity.position)
        assertEquals(parent, entity.parentId)
    }

    @Test
    fun set_replacesRootWidgetsButKeepsChildrenOfOtherParents() = runBlocking {
        val oldRoot = AppsWidget(UUID.randomUUID())
        val parent = UUID.randomUUID()
        val child = MusicWidget(UUID.randomUUID())
        repository.create(oldRoot, position = 0)
        repository.create(child, position = 0, parentId = parent)
        awaitValue { rootIds().firstOrNull()?.firstOrNull { it.id == oldRoot.id } }
        awaitValue { childIds(parent).firstOrNull()?.firstOrNull { it.id == child.id } }

        val newRoot = NotesWidget(UUID.randomUUID())
        repository.set(listOf(newRoot))
        val roots = awaitValue {
            rootIds().firstOrNull()?.takeIf { it.size == 1 && it[0].id == newRoot.id }
        }
        assertEquals(newRoot.id, roots[0].id)
        assertEquals(0, roots[0].position)
        assertEquals(
            listOf(child.id),
            childIds(parent).first().map { it.id },
        )
    }

    @Test
    fun update_patchesConfigButKeepsPosition() = runBlocking {
        val id = UUID.randomUUID()
        repository.create(AppsWidget(id, FavoritesWidgetConfig(customTags = true)), position = 2)
        awaitValue { rootIds().firstOrNull()?.firstOrNull { it.id == id } }

        repository.update(AppsWidget(id, FavoritesWidgetConfig(customTags = false)))
        val updated = awaitValue {
            repository.get().firstOrNull()
                ?.filterIsInstance<AppsWidget>()
                ?.firstOrNull { it.id == id && !it.config.customTags }
        }
        assertEquals(false, updated.config.customTags)
        assertEquals(2, rootIds().first().first { it.id == id }.position)
    }

    // ----- awaited fork API -----

    @Test
    fun setAwaited_writesAreVisibleImmediatelyAfterReturn() = runBlocking {
        val a = AppsWidget(UUID.randomUUID())
        val b = MusicWidget(UUID.randomUUID())
        val c = NotesWidget(UUID.randomUUID())
        repository.setAwaited(listOf(a, b, c))
        // No polling: the transaction must have committed when the call returns.
        val roots = rootIds().first()
        assertEquals(listOf(a.id, b.id, c.id), roots.map { it.id })
        assertEquals(listOf(0, 1, 2), roots.map { it.position })
    }

    @Test
    fun setAwaited_replacesPreviousSet() = runBlocking {
        val old = AppsWidget(UUID.randomUUID())
        repository.setAwaited(listOf(old))
        val new = MusicWidget(UUID.randomUUID())
        repository.setAwaited(listOf(new))
        assertEquals(listOf(new.id), rootIds().first().map { it.id })
    }

    @Test
    fun setAwaited_withParentOnlyReplacesThatParentsChildren() = runBlocking {
        val root = AppsWidget(UUID.randomUUID())
        val parent = UUID.randomUUID()
        val oldChild = MusicWidget(UUID.randomUUID())
        repository.setAwaited(listOf(root))
        repository.setAwaited(listOf(oldChild), parentId = parent)

        val newChild = NotesWidget(UUID.randomUUID())
        repository.setAwaited(listOf(newChild), parentId = parent)

        assertEquals(listOf(root.id), rootIds().first().map { it.id })
        assertEquals(listOf(newChild.id), childIds(parent).first().map { it.id })
    }

    @Test
    fun setAwaited_withEmptyListClearsTarget() = runBlocking {
        repository.setAwaited(listOf(AppsWidget(UUID.randomUUID())))
        repository.setAwaited(emptyList())
        assertTrue(rootIds().first().isEmpty())
    }

    @Test
    fun setAwaited_widgetsRoundTripThroughRepositoryGet() = runBlocking {
        val apps = AppsWidget(UUID.randomUUID(), FavoritesWidgetConfig(customTags = false))
        repository.setAwaited(listOf(apps))
        val widgets = repository.get().first()
        assertEquals(1, widgets.size)
        val restored = widgets[0]
        assertTrue(restored is AppsWidget)
        assertEquals(apps.id, restored.id)
        assertEquals(false, (restored as AppsWidget).config.customTags)
    }
}

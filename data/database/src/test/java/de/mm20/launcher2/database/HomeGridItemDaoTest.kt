package de.mm20.launcher2.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.entities.HomeGridItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeGridItemDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: HomeGridItemDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.homeGridItemDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun item(
        id: String,
        position: Int,
        layout: String = "phone",
        x: Int = 0,
        y: Int = 0,
        w: Int = 2,
        h: Int = 2,
        appWidgetId: Int? = null,
        config: String? = null,
    ) = HomeGridItemEntity(
        layout = layout,
        id = id,
        widget = if (id == "dock") "favorites" else "com.example/.Widget",
        profile = if (id == "dock") null else "personal",
        x = x, y = y, w = w, h = h,
        appWidgetId = appWidgetId,
        config = config,
        position = position,
    )

    @Test
    fun `replace then query returns the items ordered by position`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("b", position = 1), item("a", position = 0)))

        val rows = dao.queryLayout("phone").first()

        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test
    fun `replace drops the previous rows of the layout`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("old", position = 0)))

        dao.replaceLayout("phone", listOf(item("new", position = 0)))

        assertEquals(listOf("new"), dao.queryLayout("phone").first().map { it.id })
    }

    @Test
    fun `replacing one layout leaves the other layout untouched`() = runBlocking {
        dao.replaceLayout("fold", listOf(item("wide", position = 0, layout = "fold", w = 8)))

        dao.replaceLayout("phone", listOf(item("dock", position = 0)))

        assertEquals(listOf("wide"), dao.queryLayout("fold").first().map { it.id })
        assertEquals(listOf("dock"), dao.queryLayout("phone").first().map { it.id })
    }

    @Test
    fun `replace stamps the layout onto the rows it writes`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("stray", position = 0, layout = "fold")))

        assertEquals(listOf("stray"), dao.queryLayout("phone").first().map { it.id })
        assertEquals(emptyList<String>(), dao.queryLayout("fold").first().map { it.id })
    }

    @Test
    fun `patchGeometry changes only the geometry`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("clock", position = 3, appWidgetId = 42, config = "{}")))

        dao.patchGeometry("phone", "clock", x = 1, y = 2, w = 3, h = 4)

        val row = dao.queryLayout("phone").first().single()
        assertEquals(listOf(1, 2, 3, 4), listOf(row.x, row.y, row.w, row.h))
        assertEquals(42, row.appWidgetId)
        assertEquals("{}", row.config)
        assertEquals(3, row.position)
        assertEquals("com.example/.Widget", row.widget)
    }

    @Test
    fun `setAppWidgetId records and clears the host id`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("clock", position = 0)))

        dao.setAppWidgetId("phone", "clock", 7)
        assertEquals(7, dao.queryLayout("phone").first().single().appWidgetId)

        dao.setAppWidgetId("phone", "clock", null)
        assertNull(dao.queryLayout("phone").first().single().appWidgetId)
    }

    @Test
    fun `deleteItem removes one row and keeps the rest`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("a", position = 0), item("b", position = 1)))

        dao.deleteItem("phone", "a")

        assertEquals(listOf("b"), dao.queryLayout("phone").first().map { it.id })
    }

    @Test
    fun `the same id may exist in both layouts`() = runBlocking {
        dao.replaceLayout("phone", listOf(item("dock", position = 0, w = 4)))
        dao.replaceLayout("fold", listOf(item("dock", position = 0, layout = "fold", w = 8)))

        assertEquals(4, dao.queryLayout("phone").first().single().w)
        assertEquals(8, dao.queryLayout("fold").first().single().w)
    }
}

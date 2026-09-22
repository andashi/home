package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.widgets.AppWidget
import de.mm20.launcher2.widgets.AppWidgetConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class HomeGridReconcilerTest {

    private lateinit var database: AppDatabase
    private lateinit var grid: HomeGridRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        grid = HomeGridRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun item(id: String, widget: String, appWidgetId: Int? = null, profile: String? = null, layout: String = HomeGridLayouts.Phone) =
        HomeGridItem(layout, id, widget, profile = profile, x = 0, y = 0, w = 2, h = 2, appWidgetId = appWidgetId, position = 0)

    private fun reconciler(port: FakeAppWidgetHostPort, column: Map<UUID?, List<de.mm20.launcher2.widgets.Widget>> = emptyMap()) =
        HomeGridReconciler(grid, FakeWidgetRepository(column), port)

    @Test
    fun `an item without a host id is bound and gets the id`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("clock", "com.example/.Clock", profile = "work")))
        val port = FakeAppWidgetHostPort(bindable = setOf("com.example/.Clock"))

        val report = reconciler(port).reconcile()

        assertEquals(listOf("clock"), report.bound)
        assertEquals(100, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertEquals(Triple(100, "com.example/.Clock", "work"), port.bindCalls.single())
        assertTrue(report.released.isEmpty())
    }

    @Test
    fun `a refused bind releases the id and reports the item`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("clock", "com.example/.Clock")))
        val port = FakeAppWidgetHostPort(bindable = emptySet())

        val report = reconciler(port).reconcile()

        assertEquals(listOf("clock"), report.failed)
        assertNull(grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertEquals(listOf(100), port.released)
        assertTrue(port.bound.isEmpty())
    }

    @Test
    fun `a bound item whose provider is gone is reported and kept`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("clock", "com.example/.Clock", appWidgetId = 7)))
        val port = FakeAppWidgetHostPort(bound = listOf(7), gone = setOf(7))

        val report = reconciler(port).reconcile()

        assertEquals(listOf("clock"), report.unavailable)
        assertEquals(7, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertTrue(port.released.isEmpty())
    }

    @Test
    fun `host ids nothing references are released, referenced ones stay`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("clock", "com.example/.Clock", appWidgetId = 7)))
        grid.replace(HomeGridLayouts.Fold, listOf(item("weather", "com.example/.Weather", appWidgetId = 8, layout = HomeGridLayouts.Fold)))
        // A widget column page still hosts id 9 (ADR 0001: the pages stay).
        val column = mapOf<UUID?, List<de.mm20.launcher2.widgets.Widget>>(
            WidgetScreenTarget.Widgets2.id to listOf(AppWidget(UUID.randomUUID(), AppWidgetConfig(widgetId = 9, height = 100))),
        )
        val port = FakeAppWidgetHostPort(bound = listOf(7, 8, 9, 10, 11))

        val report = reconciler(port, column).reconcile()

        assertEquals(listOf(10, 11), report.released.sorted())
        assertEquals(setOf(7, 8, 9), port.bound)
    }

    @Test
    fun `the favorites widget is never bound`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("dock", HomeGridWidgets.Favorites)))
        val port = FakeAppWidgetHostPort(bindable = setOf(HomeGridWidgets.Favorites))

        val report = reconciler(port).reconcile()

        assertTrue(port.bindCalls.isEmpty())
        assertEquals(ReconcileReport(), report)
    }

    @Test
    fun `a pass with nothing to do reports nothing`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("clock", "com.example/.Clock", appWidgetId = 7)))
        val port = FakeAppWidgetHostPort(bound = listOf(7))

        assertEquals(ReconcileReport(), reconciler(port).reconcile())
    }

    @Test
    fun `host ids referenced beyond the first page of the widget column are kept`() = runBlocking {
        // The widget repository pages at 100; a column page holding 101
        // AppWidgets must not get its last one released as an orphan.
        val column = (1..101).map { n ->
            AppWidget(UUID.randomUUID(), AppWidgetConfig(widgetId = 1000 + n, height = 100))
        }
        val port = FakeAppWidgetHostPort(bound = (1001..1101).toList())

        val report = reconciler(port, mapOf(WidgetScreenTarget.Widgets2.id to column)).reconcile()

        assertTrue(report.released.isEmpty())
        assertEquals(101, port.bound.size)
    }
}

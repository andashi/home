package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
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

    private fun reconciler(port: FakeAppWidgetHostPort) = HomeGridReconciler(grid, port)

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
        // Nothing else shares the host any more (the widget pages are gone, PR 5b).
        val port = FakeAppWidgetHostPort(bound = listOf(7, 8, 9, 10, 11))

        val report = reconciler(port).reconcile()

        assertEquals(listOf(9, 10, 11), report.released.sorted())
        assertEquals(setOf(7, 8), port.bound)
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

    /**
     * The widget service learns of a new package on its own, and can do so
     * after the launcher hears of it: a bind in between is refused although
     * the provider is about to exist. [readyAfter] binds are refused first.
     */
    private class LateProvider(private val inner: FakeAppWidgetHostPort, private val widget: String, private var readyAfter: Int) :
        AppWidgetHostPort by inner {
        var attempts = 0
        override fun bind(id: Int, widget: String, profile: String?): Boolean {
            if (widget == this.widget) {
                attempts++
                if (attempts <= readyAfter) {
                    inner.bindCalls += Triple(id, widget, profile)
                    return false
                }
            }
            return inner.bind(id, widget, profile)
        }
    }

    /**
     * A widget whose package arrives after the grid named it holds no host id
     * (its bind was refused while the package was missing), and nothing about
     * the item changes when it arrives - so the arrival itself has to bind it
     * (review on #219).
     */
    @Test
    fun `an arrival binds a widget whose provider the service learns of late`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("only", "org.example.only/.Widget")))
        val port = LateProvider(FakeAppWidgetHostPort(bindable = setOf("org.example.only/.Widget")), "org.example.only/.Widget", readyAfter = 2)
        var pauses = 0

        val report = HomeGridReconciler(grid, port).reconcileArrival("org.example.only", attempts = 5) { pauses++ }

        assertEquals(listOf("only"), report.bound)
        assertEquals(3, port.attempts)
        assertEquals("a pause before each retry, none after the bind", 2, pauses)
        assertEquals(100 + 2, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
    }

    /**
     * The failure path: a provider that never becomes bindable is retried a
     * bounded number of times, then stays unbound and reported. No host id is
     * recorded for it, so the next pass - the next arrival, or the grid's own
     * - tries again; nothing claims it bound.
     */
    @Test
    fun `an arrival gives up after its attempts and leaves the item unbound and reported`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("only", "org.example.only/.Widget")))
        val port = LateProvider(FakeAppWidgetHostPort(bindable = setOf("org.example.only/.Widget")), "org.example.only/.Widget", readyAfter = 99)
        var pauses = 0

        val report = HomeGridReconciler(grid, port).reconcileArrival("org.example.only", attempts = 4) { pauses++ }

        assertEquals(listOf("only"), report.failed)
        assertEquals(4, port.attempts)
        assertEquals(3, pauses)
        assertNull(grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertTrue("every refused id is released, none leaks", port.boundIds().isEmpty())
    }

    /** Control: a failure of another package's widget does not keep an arrival retrying. */
    @Test
    fun `an arrival retries only for its own package`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("other", "org.example.other/.Widget")))
        val port = FakeAppWidgetHostPort(bindable = emptySet())
        var pauses = 0

        val report = HomeGridReconciler(grid, port).reconcileArrival("org.example.only", attempts = 5) { pauses++ }

        assertEquals(listOf("other"), report.failed)
        assertEquals(1, port.bindCalls.size)
        assertEquals(0, pauses)
    }
}

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

    /**
     * Uninstalling a provider makes the widget service delete its widgets: the
     * host no longer holds the id, and there is no provider info for it. When
     * the package comes back, nothing binds the item, because it still has an
     * id - even a cold restart leaves the cell broken (#245, cases C and F).
     */
    @Test
    fun `an item whose host id the service deleted is bound anew`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("camera", "org.example.cam/.Widget", appWidgetId = 7)))
        val port = FakeAppWidgetHostPort(bound = emptyList(), gone = setOf(7), bindable = setOf("org.example.cam/.Widget"))

        val report = reconciler(port).reconcile()

        assertEquals(listOf("camera"), report.bound)
        assertEquals(100, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertEquals(Triple(100, "org.example.cam/.Widget", null), port.bindCalls.single())
    }

    /**
     * The package is installed but no longer declares the widget (an update
     * dropped it): the bind fails, the dead id is cleared, and the item is then
     * exactly a declared widget whose provider is missing - retried when its
     * package arrives, not on every pass (review of #245).
     *
     * Passes are driven the way HomeGrid drives them: its LaunchedEffect is
     * keyed on every item's (id, host id), so clearing the id is itself a
     * change and runs one more pass (review on #246). That pass fails too and
     * changes nothing, the key stays the same, and the passes stop: two bind
     * attempts in all, then none.
     */
    @Test
    fun `a deleted id whose provider is no longer declared is cleared and passes stop`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("camera", "org.example.cam/.Widget", appWidgetId = 7)))
        val port = FakeAppWidgetHostPort(bound = emptyList(), gone = setOf(7), bindable = emptySet())
        suspend fun bindingKey() = grid.observe(HomeGridLayouts.Phone).first().map { it.id to it.appWidgetId }

        var key = bindingKey()
        var passes = 0
        do {
            val report = reconciler(port).reconcile()
            assertEquals(listOf("camera"), report.failed)
            passes++
            val before = key
            key = bindingKey()
        } while (key != before && passes < 10)

        assertEquals("passes until nothing changes", 2, passes)
        assertEquals(2, port.bindCalls.size)
        assertNull(grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        // An unrelated package event runs no pass for it.
        assertEquals(ReconcileReport(), reconciler(port).reconcileArrival("org.other", attempts = 1) {})
        assertEquals(2, port.bindCalls.size)
    }

    /** Case C itself: the package arrives again, and its arrival binds the item. */
    @Test
    fun `an arrival binds a widget whose id the service deleted`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("camera", "org.example.cam/.Widget", appWidgetId = 7)))
        val port = FakeAppWidgetHostPort(bound = emptyList(), gone = setOf(7), bindable = setOf("org.example.cam/.Widget"))

        val report = reconciler(port).reconcileArrival("org.example.cam", attempts = 1) {}

        assertEquals(listOf("camera"), report.bound)
        assertEquals(100, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
    }

    /**
     * Control: both signals are needed. An id missing from a host list that
     * came back short, but whose provider info is there, is a widget that
     * exists - rebinding it would lose a configured widget's setup.
     */
    @Test
    fun `an id the host does not list but whose provider is there is kept`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("camera", "org.example.cam/.Widget", appWidgetId = 7)))
        val port = FakeAppWidgetHostPort(bound = emptyList(), bindable = setOf("org.example.cam/.Widget"))

        val report = reconciler(port).reconcile()

        assertTrue(port.bindCalls.isEmpty())
        assertEquals(7, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertTrue(report.bound.isEmpty() && report.failed.isEmpty())
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

    /** The defaults the grid runs with: a refused bind is retried after half a second. */
    @Test
    fun `an arrival with the defaults retries after half a second`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("only", "org.example.only/.Widget")))
        val port = LateProvider(FakeAppWidgetHostPort(bindable = setOf("org.example.only/.Widget")), "org.example.only/.Widget", readyAfter = 1)

        val started = System.nanoTime()
        val report = HomeGridReconciler(grid, port).reconcileArrival("org.example.only")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("only"), report.bound)
        assertTrue("waited ${elapsedMs} ms", elapsedMs >= 500)
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

    /**
     * Control: an arrival no unbound item waits for runs no pass at all. Most
     * package events are updates of apps the grid never names, and each pass
     * holds the grid's reconcile lock (review on #213).
     */
    @Test
    fun `an arrival nothing waits for runs no pass`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("other", "org.example.other/.Widget")))
        val port = FakeAppWidgetHostPort(bindable = emptySet())
        var pauses = 0

        val report = HomeGridReconciler(grid, port).reconcileArrival("org.example.only", attempts = 5) { pauses++ }

        assertEquals(ReconcileReport(), report)
        assertTrue(port.bindCalls.isEmpty())
        assertEquals(0, pauses)
    }

    /**
     * Items are named per layout, so one id can stand for different widgets on
     * the phone and on the fold. A refused bind is found on the item itself,
     * not looked up by id: by id the phone's item answered for the fold's, and
     * the fold's widget got no retry (review on #213).
     */
    @Test
    fun `an arrival retries a fold item whose id the phone layout also uses`() = runBlocking {
        grid.replace(HomeGridLayouts.Phone, listOf(item("clock", "org.example.a/.Widget", appWidgetId = 7)))
        grid.replace(HomeGridLayouts.Fold, listOf(item("clock", "org.example.b/.Widget", layout = HomeGridLayouts.Fold)))
        val port = LateProvider(
            FakeAppWidgetHostPort(bound = listOf(7), bindable = setOf("org.example.b/.Widget")),
            "org.example.b/.Widget", readyAfter = 1,
        )
        var pauses = 0

        val report = HomeGridReconciler(grid, port).reconcileArrival("org.example.b", attempts = 5) { pauses++ }

        assertEquals(listOf("clock"), report.bound)
        assertEquals(1, pauses)
        assertEquals(7, grid.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
        assertTrue(grid.observe(HomeGridLayouts.Fold).first().single().appWidgetId != null)
    }
}

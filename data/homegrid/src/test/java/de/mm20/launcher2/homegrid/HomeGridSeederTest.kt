package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.widgets.AppWidget
import de.mm20.launcher2.widgets.AppWidgetConfig
import de.mm20.launcher2.widgets.AppsWidget
import de.mm20.launcher2.widgets.FavoritesWidgetConfig
import de.mm20.launcher2.widgets.Widget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class HomeGridSeederTest {

    private lateinit var database: AppDatabase
    private lateinit var grid: HomeGridRepository

    // 4 columns of 97 dp, 6 rows: the phone of ADR 0001 / D1.
    private val phone = HomeGridGeometry.derive(FormFactor.Phone, 4, widthDp = 412f, heightDp = 622f)
    private val fold = HomeGridGeometry.derive(FormFactor.Fold, 4, widthDp = 790f, heightDp = 600f)

    private val providers = mapOf(
        11 to "com.example.clock/.Digital",
        12 to "com.example.weather/.Forecast",
    )

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

    private fun seeder(column: List<Widget>, flag: FakeSeedFlag = FakeSeedFlag()) = HomeGridSeeder(
        widgetRepository = FakeWidgetRepository(mapOf(WidgetScreenTarget.Default.id to column)),
        homeGridRepository = grid,
        flag = flag,
        providerOf = { providers[it] },
    )

    private fun appWidget(id: Int, heightDp: Int) =
        AppWidget(UUID.randomUUID(), AppWidgetConfig(widgetId = id, height = heightDp))

    private val favoritesWidget = AppsWidget(UUID.randomUUID(), FavoritesWidgetConfig())

    @Test
    fun `an empty column still gets the dock in the bottom row`() = runBlocking {
        val flag = FakeSeedFlag()

        val written = seeder(emptyList(), flag).seedIfNeeded(phone).items

        assertEquals(1, written.size)
        val dock = written.single()
        assertEquals(HomeGridWidgets.Favorites, dock.widget)
        assertEquals(listOf(0, 5, 4, 1), listOf(dock.x, dock.y, dock.w, dock.h))
        assertEquals(HomeGridLayouts.Phone, dock.layout)
        assertEquals(written, grid.observe(HomeGridLayouts.Phone).first())
        assertTrue(flag.seeded)
    }

    @Test
    fun `a favorites-only column becomes the dock alone`() = runBlocking {
        val written = seeder(listOf(favoritesWidget)).seedIfNeeded(phone).items

        assertEquals(listOf(HomeGridWidgets.Favorites), written.map { it.widget })
        assertEquals(listOf(0, 5, 4, 1), written.single().let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun `AppWidgets become full-width items stacked from the top, the dock last`() = runBlocking {
        val column = listOf(favoritesWidget, appWidget(11, 110), appWidget(12, 250))

        val written = seeder(column).seedIfNeeded(phone).items

        assertEquals(listOf("com.example.clock/.Digital", "com.example.weather/.Forecast", HomeGridWidgets.Favorites), written.map { it.widget })
        val clock = written[0]
        val weather = written[1]
        val dock = written[2]
        // 110 dp on a 97 dp cell with an 8 dp gap needs two cells, 250 dp three.
        assertEquals(listOf(0, 0, 4, 2), listOf(clock.x, clock.y, clock.w, clock.h))
        assertEquals(listOf(0, 2, 4, 3), listOf(weather.x, weather.y, weather.w, weather.h))
        assertEquals(listOf(0, 5, 4, 1), listOf(dock.x, dock.y, dock.w, dock.h))
        assertEquals(11, clock.appWidgetId)
        assertEquals(12, weather.appWidgetId)
        assertEquals(listOf(0, 1, 2), written.map { it.position })
    }

    @Test
    fun `the production seeder asks the AppWidgetManager for providers`() = runBlocking {
        // Nothing is bound in Robolectric's AppWidgetManager, so the widget
        // has no provider and is skipped; the dock is still written.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seeder = HomeGridSeeder(
            context,
            FakeWidgetRepository(mapOf(WidgetScreenTarget.Default.id to listOf(appWidget(11, 110)))),
            grid,
            FakeSeedFlag(),
        )

        val written = seeder.seedIfNeeded(phone).items

        assertEquals(listOf(HomeGridWidgets.Favorites), written.map { it.widget })
    }

    @Test
    fun `an AppWidget whose provider is unknown is skipped`() = runBlocking {
        val column = listOf(appWidget(99, 110))

        val written = seeder(column).seedIfNeeded(phone).items

        assertEquals(listOf(HomeGridWidgets.Favorites), written.map { it.widget })
    }

    @Test
    fun `an AppWidget taller than the free rows is clamped to them`() = runBlocking {
        val column = listOf(appWidget(11, 900))

        val written = seeder(column).seedIfNeeded(phone).items

        val clock = written.first { it.appWidgetId == 11 }
        assertEquals(5, clock.h)
        assertEquals(0, clock.y)
    }

    @Test
    fun `an already seeded device is left alone`() = runBlocking {
        val flag = FakeSeedFlag(seeded = true)

        val written = seeder(listOf(favoritesWidget), flag).seedIfNeeded(phone).items

        assertTrue(written.isEmpty())
        assertTrue(grid.observe(HomeGridLayouts.Phone).first().isEmpty())
        assertEquals(0, flag.marks)
    }

    @Test
    fun `a layout that already holds items is left alone and the flag is set`() = runBlocking {
        val existing = HomeGridItem(HomeGridLayouts.Phone, "mine", HomeGridWidgets.Favorites, x = 0, y = 0, w = 2, h = 2, position = 0)
        grid.replace(HomeGridLayouts.Phone, listOf(existing))
        val flag = FakeSeedFlag()

        val written = seeder(listOf(favoritesWidget), flag).seedIfNeeded(phone).items

        assertTrue(written.isEmpty())
        assertEquals(listOf(existing), grid.observe(HomeGridLayouts.Phone).first())
        assertTrue(flag.seeded)
    }

    @Test
    fun `a fold gets the fold layout at double width and the phone layout too`() = runBlocking {
        val column = listOf(favoritesWidget, appWidget(11, 110))

        val written = seeder(column).seedIfNeeded(fold).items

        assertEquals(HomeGridLayouts.Fold, written.first().layout)
        val dock = written.first { it.isFavorites }
        assertEquals(listOf(0, fold.rows - 1, 8, 1), listOf(dock.x, dock.y, dock.w, dock.h))
        // The dock may span the fold; an AppWidget may not, so it takes the left half.
        assertEquals(4, written.first { it.appWidgetId == 11 }.w)

        val phoneItems = grid.observe(HomeGridLayouts.Phone).first()
        assertEquals(2, phoneItems.size)
        assertEquals(4, phoneItems.first { it.isFavorites }.w)
        assertEquals(4, phoneItems.first { it.appWidgetId == 11 }.w)
    }
}

/** The seeder's handling of several target layouts and of what does not fit. */
@RunWith(RobolectricTestRunner::class)
class HomeGridSeederTargetsTest {

    private lateinit var database: AppDatabase
    private lateinit var grid: HomeGridRepository

    private val fold = HomeGridGeometry.derive(FormFactor.Fold, 4, widthDp = 790f, heightDp = 600f)
    private val phone = HomeGridGeometry.derive(FormFactor.Phone, 4, widthDp = 412f, heightDp = 622f) // 6 rows

    private val providers = mapOf(11 to "com.example/.A", 12 to "com.example/.B", 13 to "com.example/.C")

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

    private fun appWidget(id: Int, heightDp: Int) =
        AppWidget(UUID.randomUUID(), AppWidgetConfig(widgetId = id, height = heightDp))

    private fun seeder(column: List<Widget>, repository: HomeGridRepository = grid, flag: FakeSeedFlag = FakeSeedFlag()) =
        HomeGridSeeder(
            widgetRepository = FakeWidgetRepository(mapOf(WidgetScreenTarget.Default.id to column)),
            homeGridRepository = repository,
            flag = flag,
            providerOf = { providers[it] },
        )

    @Test
    fun `a populated phone layout is left alone while the empty fold layout is seeded`() = runBlocking {
        val mine = HomeGridItem(HomeGridLayouts.Phone, "mine", HomeGridWidgets.Favorites, x = 0, y = 0, w = 2, h = 2, position = 0)
        grid.replace(HomeGridLayouts.Phone, listOf(mine))
        val flag = FakeSeedFlag()

        val result = seeder(listOf(appWidget(11, 110)), flag = flag).seedIfNeeded(fold)

        assertEquals(listOf("com.example/.A", HomeGridWidgets.Favorites), result.items.map { it.widget })
        assertEquals(listOf(mine), grid.observe(HomeGridLayouts.Phone).first())
        assertEquals(result.items, grid.observe(HomeGridLayouts.Fold).first())
        assertTrue(flag.seeded)
    }

    @Test
    fun `the flag stays clear when a target layout could not be written`() = runBlocking {
        val failing = object : HomeGridRepository by grid {
            override suspend fun replace(layout: String, items: List<HomeGridItem>) {
                if (layout == HomeGridLayouts.Phone) error("disk full")
                grid.replace(layout, items)
            }
        }
        val flag = FakeSeedFlag()

        val thrown = runCatching { seeder(listOf(appWidget(11, 110)), failing, flag).seedIfNeeded(fold) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse(flag.seeded)
        assertEquals(0, flag.marks)
        // The fold layout was written before the phone write failed; the next
        // start seeds only what is still empty.
        assertTrue(grid.observe(HomeGridLayouts.Fold).first().isNotEmpty())
    }

    @Test
    fun `widgets that do not fit above the dock are reported as leftovers, the seed is complete`() = runBlocking {
        // Three two-row widgets need six rows; five are free above the dock.
        val column = listOf(appWidget(11, 110), appWidget(12, 110), appWidget(13, 110))
        val flag = FakeSeedFlag()

        val result = seeder(column, flag = flag).seedIfNeeded(phone)

        assertEquals(listOf(11, 12), result.items.mapNotNull { it.appWidgetId })
        assertEquals(listOf(HomeGridSeeder.Leftover(13, "com.example/.C")), result.leftovers)
        assertTrue(flag.seeded)
    }
}

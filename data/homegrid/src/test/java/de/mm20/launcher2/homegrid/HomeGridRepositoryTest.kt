package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.HomeGridItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeGridRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: HomeGridRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HomeGridRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val dock = HomeGridItem(
        layout = HomeGridLayouts.Phone,
        id = "dock",
        widget = HomeGridWidgets.Favorites,
        x = 0, y = 5, w = 4, h = 1,
        position = 0,
    )

    private val clock = HomeGridItem(
        layout = HomeGridLayouts.Phone,
        id = "clock",
        widget = "com.android.deskclock/.DigitalAppWidgetProvider",
        profile = "personal",
        x = 0, y = 2, w = 4, h = 2,
        appWidgetId = 12,
        config = HomeGridItemConfig(borderless = true, background = false, themeColors = false),
        position = 1,
    )

    @Test
    fun `observe emits the items after replace, in position order`() = runBlocking {
        repository.replace(HomeGridLayouts.Phone, listOf(clock, dock))

        val items = repository.observe(HomeGridLayouts.Phone).first()

        assertEquals(listOf(dock, clock), items)
    }

    @Test
    fun `every field round-trips through the database`() = runBlocking {
        repository.replace(HomeGridLayouts.Phone, listOf(clock))

        assertEquals(clock, repository.observe(HomeGridLayouts.Phone).first().single())
    }

    @Test
    fun `a null config column reads back as the default config`() = runBlocking {
        database.homeGridItemDao().replaceLayout(
            HomeGridLayouts.Phone,
            listOf(
                HomeGridItemEntity(
                    layout = HomeGridLayouts.Phone, id = "clock", widget = clock.widget,
                    profile = "personal", x = 0, y = 0, w = 4, h = 2,
                    appWidgetId = null, config = null, position = 0,
                )
            ),
        )

        val item = repository.observe(HomeGridLayouts.Phone).first().single()

        assertEquals(HomeGridItemConfig(), item.config)
    }

    @Test
    fun `an unreadable config column falls back to defaults instead of dropping the row`() =
        runBlocking {
            database.homeGridItemDao().replaceLayout(
                HomeGridLayouts.Phone,
                listOf(
                    HomeGridItemEntity(
                        layout = HomeGridLayouts.Phone, id = "clock", widget = clock.widget,
                        profile = "personal", x = 0, y = 0, w = 4, h = 2,
                        appWidgetId = null, config = "not json at all {", position = 0,
                    )
                ),
            )

            val items = repository.observe(HomeGridLayouts.Phone).first()

            assertEquals(1, items.size)
            assertEquals(HomeGridItemConfig(), items.single().config)
        }

    @Test
    fun `unknown keys in the config column are ignored, known ones are read`() = runBlocking {
        database.homeGridItemDao().replaceLayout(
            HomeGridLayouts.Phone,
            listOf(
                HomeGridItemEntity(
                    layout = HomeGridLayouts.Phone, id = "clock", widget = clock.widget,
                    profile = "personal", x = 0, y = 0, w = 4, h = 2,
                    appWidgetId = null, config = """{"borderless":true,"fromTheFuture":1}""",
                    position = 0,
                )
            ),
        )

        val item = repository.observe(HomeGridLayouts.Phone).first().single()

        assertEquals(HomeGridItemConfig(borderless = true), item.config)
    }

    @Test
    fun `patchGeometry moves and resizes one item and nothing else`() = runBlocking {
        repository.replace(HomeGridLayouts.Phone, listOf(dock, clock))

        repository.patchGeometry(HomeGridLayouts.Phone, "clock", x = 2, y = 3, w = 2, h = 1)

        val items = repository.observe(HomeGridLayouts.Phone).first()
        assertEquals(dock, items[0])
        assertEquals(clock.copy(x = 2, y = 3, w = 2, h = 1), items[1])
    }

    @Test
    fun `setAppWidgetId records and clears the host id`() = runBlocking {
        repository.replace(HomeGridLayouts.Phone, listOf(clock.copy(appWidgetId = null)))

        repository.setAppWidgetId(HomeGridLayouts.Phone, "clock", 99)
        assertEquals(99, repository.observe(HomeGridLayouts.Phone).first().single().appWidgetId)

        repository.setAppWidgetId(HomeGridLayouts.Phone, "clock", null)
        assertNull(repository.observe(HomeGridLayouts.Phone).first().single().appWidgetId)
    }

    @Test
    fun `delete removes one item`() = runBlocking {
        repository.replace(HomeGridLayouts.Phone, listOf(dock, clock))

        repository.delete(HomeGridLayouts.Phone, "dock")

        assertEquals(listOf(clock), repository.observe(HomeGridLayouts.Phone).first())
    }

    @Test
    fun `layouts are independent`() = runBlocking {
        repository.replace(HomeGridLayouts.Phone, listOf(dock))
        repository.replace(HomeGridLayouts.Fold, listOf(dock.copy(layout = HomeGridLayouts.Fold, w = 8)))

        assertEquals(4, repository.observe(HomeGridLayouts.Phone).first().single().w)
        assertEquals(8, repository.observe(HomeGridLayouts.Fold).first().single().w)
    }

    @Test
    fun `isFavorites tells the favorites widget from AppWidgets`() {
        assertEquals(true, dock.isFavorites)
        assertEquals(false, clock.isFavorites)
    }

    @Test
    fun `racing mutations are applied whole and in lock order`() = runBlocking {
        // Control for the one-writer promise: fifty coroutines each replace
        // the phone layout with their own single item and then patch it.
        // Whichever wins, the layout must hold exactly one item whose
        // geometry is the patch of that same writer, never a mix.
        val jobs = (0 until 50).map { n ->
            launch(Dispatchers.IO) {
                repository.replace(HomeGridLayouts.Phone, listOf(dock.copy(id = "w$n", x = 0, y = 0)))
                repository.patchGeometry(HomeGridLayouts.Phone, "w$n", x = n, y = 0, w = 1, h = 1)
            }
        }
        jobs.joinAll()
        val items = repository.observe(HomeGridLayouts.Phone).first()
        assertEquals(1, items.size)
        val winner = items.single()
        assertEquals("w${winner.x}", winner.id)
    }
}

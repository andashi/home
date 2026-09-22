package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
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

/**
 * The one default the grid has: the favorites row on a launcher that has
 * never been configured (PR 5b replaces the seeder with this).
 */
@RunWith(RobolectricTestRunner::class)
class HomeGridDefaultsTest {

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

    private class FakeInitFlag(var initialized: Boolean = false) : HomeGridInitFlag {
        var marks = 0
        override suspend fun isInitialized(): Boolean = initialized
        override suspend fun markInitialized() {
            initialized = true
            marks++
        }
    }

    @Test
    fun `an empty, never initialised grid gets the favorites row in the bottom row`() = runBlocking {
        val flag = FakeInitFlag()

        val written = HomeGridDefaults.ensureFavoritesRow(grid, flag, HomeGridLayouts.Phone, columns = 4, rows = 6)

        val dock = written.single()
        assertEquals(HomeGridDefaults.FavoritesId, dock.id)
        assertTrue(dock.isFavorites)
        assertEquals(listOf(0, 5, 4, 1), listOf(dock.x, dock.y, dock.w, dock.h))
        assertEquals(listOf(dock), grid.observe(HomeGridLayouts.Phone).first())
        assertTrue(flag.initialized)
        assertEquals(1, flag.marks)
    }

    @Test
    fun `on the fold layout the row spans all columns`() = runBlocking {
        val written = HomeGridDefaults.ensureFavoritesRow(grid, FakeInitFlag(), HomeGridLayouts.Fold, columns = 8, rows = 7)

        assertEquals(listOf(0, 6, 8, 1), written.single().let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun `an initialised grid is left alone even when empty`() = runBlocking {
        // A config said `items: []`, or the user removed everything.
        val flag = FakeInitFlag(initialized = true)

        val written = HomeGridDefaults.ensureFavoritesRow(grid, flag, HomeGridLayouts.Phone, columns = 4, rows = 6)

        assertTrue(written.isEmpty())
        assertTrue(grid.observe(HomeGridLayouts.Phone).first().isEmpty())
        assertEquals(0, flag.marks)
    }

    @Test
    fun `a grid with content in either layout is left alone and marked initialised`() = runBlocking {
        grid.replace(
            HomeGridLayouts.Fold,
            listOf(HomeGridItem(HomeGridLayouts.Fold, "clock", "com.example/.Clock", x = 0, y = 0, w = 3, h = 1, position = 0)),
        )
        val flag = FakeInitFlag()

        val written = HomeGridDefaults.ensureFavoritesRow(grid, flag, HomeGridLayouts.Phone, columns = 4, rows = 6)

        assertTrue(written.isEmpty())
        assertTrue(grid.observe(HomeGridLayouts.Phone).first().isEmpty())
        assertTrue(flag.initialized)
    }

    @Test
    fun `it is idempotent`() = runBlocking {
        val flag = FakeInitFlag()
        HomeGridDefaults.ensureFavoritesRow(grid, flag, HomeGridLayouts.Phone, columns = 4, rows = 6)

        val second = HomeGridDefaults.ensureFavoritesRow(grid, flag, HomeGridLayouts.Phone, columns = 4, rows = 6)

        assertTrue(second.isEmpty())
        assertEquals(1, grid.observe(HomeGridLayouts.Phone).first().size)
        assertFalse(flag.marks > 1)
    }
}

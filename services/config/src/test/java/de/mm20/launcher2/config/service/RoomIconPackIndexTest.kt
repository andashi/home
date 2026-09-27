package de.mm20.launcher2.config.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.IconEntity
import de.mm20.launcher2.database.entities.IconPackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `apps[].icon` names a pack icon by pack and drawable only (#3 slice 4,
 * PR 2); what the picker stores besides - the icon's type, a clock's layers,
 * whether it may be themed - comes from the pack's index, as the picker takes
 * it from there.
 */
@RunWith(RobolectricTestRunner::class)
class RoomIconPackIndexTest {

    private lateinit var database: AppDatabase
    private lateinit var index: RoomIconPackIndex

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        index = RoomIconPackIndex(database)
        database.iconDao().installIconPack(IconPackEntity(name = "Pack", packageName = "p.q", version = "1"))
    }

    @After
    fun tearDown() = database.close()

    private fun icons(vararg entities: IconEntity) = runBlocking { database.iconDao().insertAll(entities.toList()) }

    @Test
    fun `an app drawable resolves to its type and themed flag`() = runBlocking {
        icons(IconEntity(type = "app", packageName = "org.a", drawable = "signal", iconPack = "p.q", themed = true))

        assertEquals(IconPackIndex.Resolution.Found("app", "signal", null, true), index.resolve("p.q", "signal"))
    }

    /** A clock's layers are what makes it tick; the file does not carry them, the index does. */
    @Test
    fun `a clock drawable resolves with its layers`() = runBlocking {
        icons(IconEntity(type = "clock", packageName = "org.clock", drawable = "clock", iconPack = "p.q", extras = """{"hourLayer":1}"""))

        assertEquals(IconPackIndex.Resolution.Found("clock", "clock", """{"hourLayer":1}""", false), index.resolve("p.q", "clock"))
    }

    @Test
    fun `a calendar resolves by its list of days`() = runBlocking {
        val days = (1..31).joinToString(",") { "cal_$it" }
        icons(IconEntity(type = "calendar", packageName = "org.cal", drawable = days, iconPack = "p.q"))

        assertEquals(IconPackIndex.Resolution.Found("calendar", days, null, false), index.resolve("p.q", days))
    }

    @Test
    fun `a pack that is not installed is reported as such`() = runBlocking {
        assertEquals(IconPackIndex.Resolution.PackMissing, index.resolve("not.installed", "signal"))
    }

    /**
     * What a waiting `icon-pack-unavailable` reloads on: the index itself,
     * since a pack's package event races its indexing. A pack updated in
     * place is a new entry - the update can bring the drawable a file names.
     */
    @Test
    fun `the indexed packs are each a package at its version, and an update is a new one`() = runBlocking {
        assertEquals(setOf("p.q:1"), index.indexed().first())

        database.iconDao().installIconPack(IconPackEntity(name = "Pack", packageName = "p.q", version = "2"))

        assertEquals(setOf("p.q:2"), index.indexed().first())
    }

    @Test
    fun `a drawable the installed pack does not have is reported as such`() = runBlocking {
        icons(IconEntity(type = "app", packageName = "org.a", drawable = "signal", iconPack = "p.q"))

        assertEquals(IconPackIndex.Resolution.DrawableMissing, index.resolve("p.q", "nothing_like_it"))
    }
}

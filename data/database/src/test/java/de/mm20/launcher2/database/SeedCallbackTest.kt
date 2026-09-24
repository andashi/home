package de.mm20.launcher2.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #106 (decided by Dob, 2026-09-24): a new install gets the launcher's own
 * actions and one neutral web search, which uses the browser's engine; no
 * YouTube, no Google Play.
 */
@RunWith(RobolectricTestRunner::class)
class SeedCallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .addCallback(SeedCallback(context))
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = database.close()

    @Test
    fun `a new database has the built-in actions and only the neutral web search`() = runTest {
        val actions = database.searchActionDao().getSearchActions().first()

        assertEquals(
            listOf("call", "message", "email", "contact", "alarm", "timer", "calendar", "website", "websearch"),
            actions.map { it.type },
        )
        assertEquals((0..8).toList(), actions.map { it.position })
    }
}

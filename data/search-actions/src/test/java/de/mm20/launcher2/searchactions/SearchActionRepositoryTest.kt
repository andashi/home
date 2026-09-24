package de.mm20.launcher2.searchactions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.searchactions.builders.CallActionBuilder
import de.mm20.launcher2.searchactions.builders.CustomWebsearchActionBuilder
import de.mm20.launcher2.searchactions.builders.WebsearchActionBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #106: the config store waits for its write, so the read-back right after sees it. */
@RunWith(RobolectricTestRunner::class)
class SearchActionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val writes = StandardTestDispatcher()
    private val repository = SearchActionRepositoryImpl(context, database, writes)

    @After
    fun close() = database.close()

    /**
     * #116 review: a settings save made just before a config replace must
     * not land after it: writes happen in the order they were made.
     */
    @Test
    fun `a settings save made before a replace does not overwrite it`() = runTest(writes) {
        repository.saveSearchActionBuilders(listOf(CallActionBuilder(context)))
        repository.replaceSearchActionBuilders(listOf(WebsearchActionBuilder(context)))
        advanceUntilIdle()

        assertEquals(listOf("websearch"), repository.getSearchActionBuilders().first().map { it.key })
    }

    @Test
    fun `replace writes before it returns, in order`() = runTest(writes) {
        val builders = listOf(
            CustomWebsearchActionBuilder("Docs", "https://example.org/?q=\${1}", packageName = "org.example.browser"),
            WebsearchActionBuilder(context),
        )

        repository.replaceSearchActionBuilders(builders)

        assertEquals(builders.map { it.key }, repository.getSearchActionBuilders().first().map { it.key })
        assertEquals(builders[0], repository.getSearchActionBuilders().first()[0])
    }

    @Test
    fun `replace with nothing leaves nothing`() = runTest(writes) {
        repository.replaceSearchActionBuilders(listOf(WebsearchActionBuilder(context)))
        repository.replaceSearchActionBuilders(emptyList())

        assertEquals(emptyList<Any>(), repository.getSearchActionBuilders().first())
    }
}

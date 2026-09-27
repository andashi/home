package de.mm20.launcher2.data.customattrs

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.SavableSearchableRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * The fork's awaited label write (#3 slice 4): a label is anchored to its
 * item's row before it is written, because the cleanup removes a label
 * whose item has none (review on #207).
 */
@RunWith(RobolectricTestRunner::class)
class CustomAttributesRepositoryTest {

    private lateinit var database: AppDatabase

    /** Label rows that existed when the anchor was asked for, per call. */
    private val labelsAtAnchor = mutableListOf<Int>()
    private val anchored = mutableListOf<String>()

    /**
     * Only the awaited insert is answered: the fire-and-forget one is what
     * the label write used, and it returned before the row existed.
     */
    private val searchables by lazy {
        Proxy.newProxyInstance(
            SavableSearchableRepository::class.java.classLoader,
            arrayOf(SavableSearchableRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "insertAwaited" -> {
                    labelsAtAnchor += runBlocking { labelRows().size }
                    @Suppress("UNCHECKED_CAST")
                    for (item in args[0] as Collection<SavableSearchable>) {
                        anchored += item.key
                        runBlocking {
                            database.searchableDao().insert(
                                SavedSearchableEntity(
                                    key = item.key, type = item.domain, serializedSearchable = item.key,
                                    launchCount = 0, pinPosition = 0, visibility = 0, weight = 0.0,
                                ),
                            )
                        }
                    }
                    Unit
                }
                "toString" -> "fake SavableSearchableRepository"
                else -> error("unexpected call ${method.name}")
            }
        } as SavableSearchableRepository
    }

    private lateinit var repository: CustomAttributesRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = CustomAttributesRepositoryImpl(database, searchables)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun labelRows() = database.backupDao().exportCustomAttributes(1000, 0).filter { it.type == "label" }

    @Test
    fun `labelled items are anchored before their labels are written`() = runBlocking {
        val a = TestSearchable("app://a:A")
        val b = TestSearchable("app://b:B")

        repository.replaceCustomLabelsAwaited(listOf(a, b), mapOf(a.key to "Chat"))

        assertEquals(listOf(0), labelsAtAnchor)
        assertEquals(listOf(a.key), anchored)
        assertEquals(listOf(a.key to "Chat"), labelRows().map { it.key to it.value })
    }

    /** What the anchor is for: the cleanup right after keeps the label. */
    @Test
    fun `a label written by the config survives the cleanup`() = runBlocking {
        val a = TestSearchable("app://a:A")

        repository.replaceCustomLabelsAwaited(listOf(a), mapOf(a.key to "Chat"))
        database.backupDao().cleanUp()

        assertEquals(listOf("Chat"), labelRows().map { it.value })
    }

    private class TestSearchable(override val key: String) : SavableSearchable {
        override val domain: String = "app"
        override val label: String = key
        override val preferDetailsOverLaunch: Boolean = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?): Boolean = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw NotImplementedError()
        override fun getSerializer(): SearchableSerializer = object : SearchableSerializer {
            override val typePrefix: String = "app"
            override fun serialize(searchable: SavableSearchable): String = searchable.key
        }
    }
}

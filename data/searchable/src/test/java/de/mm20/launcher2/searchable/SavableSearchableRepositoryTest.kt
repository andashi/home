package de.mm20.launcher2.searchable

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization tests for the existing fire-and-forget favorites API plus
 * tests for the fork's awaited [SavableSearchableRepository.replaceManuallySortedAwaited].
 *
 * The repository's deserialization path needs Koin, so read-backs go through the
 * DAO directly; this also asserts on entity columns (pinPosition, launchCount,
 * weight) that the repository's flows do not expose.
 */
@RunWith(RobolectricTestRunner::class)
class SavableSearchableRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: SavableSearchableRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SavableSearchableRepositoryImpl(database, null)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun <T> awaitValue(
        timeoutMs: Long = 5000,
        block: suspend () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for expected database state")
            }
            delay(20)
        }
    }

    private fun pinnedKeys(limit: Int = 10) = database.searchableDao().getKeys(
        manuallySorted = true,
        automaticallySorted = true,
        frequentlyUsed = false,
        unused = false,
        minVisibility = VisibilityLevel.Hidden.value,
        maxVisibility = VisibilityLevel.Default.value,
        limit = limit,
    )

    // ----- characterization of the existing async API -----

    @Test
    fun updateFavorites_assignsDescendingPinPositionsInListOrder() = runBlocking {
        repository.updateFavorites(
            manuallySorted = listOf(TestSearchable("a"), TestSearchable("b")),
            automaticallySorted = listOf(TestSearchable("c")),
        )
        awaitValue { database.searchableDao().getByKey("a").firstOrNull() }
        assertEquals(3, database.searchableDao().getByKey("a").first()!!.pinPosition)
        assertEquals(2, database.searchableDao().getByKey("b").first()!!.pinPosition)
        assertEquals(1, database.searchableDao().getByKey("c").first()!!.pinPosition)
    }

    @Test
    fun updateFavorites_ordersPinnedKeysManuallySortedFirst() = runBlocking {
        repository.updateFavorites(
            manuallySorted = listOf(TestSearchable("a"), TestSearchable("b")),
            automaticallySorted = listOf(TestSearchable("c")),
        )
        val keys = awaitValue {
            pinnedKeys().firstOrNull()?.takeIf { it.size == 3 }
        }
        assertEquals(listOf("a", "b", "c"), keys)
    }

    @Test
    fun updateFavorites_unpinsPreviouslyPinnedItems() = runBlocking {
        repository.updateFavorites(
            manuallySorted = listOf(TestSearchable("a")),
            automaticallySorted = emptyList(),
        )
        awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.pinPosition > 0 }
        }

        repository.updateFavorites(emptyList(), emptyList())
        val entity = awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.pinPosition == 0 }
        }
        assertEquals(0, entity.pinPosition)
    }

    @Test
    fun upsert_preservesExistingValuesWhenParametersAreNull() = runBlocking {
        val item = TestSearchable("a")
        repository.insert(item)
        awaitValue { database.searchableDao().getByKey("a").firstOrNull() }

        repository.upsert(
            item,
            visibility = VisibilityLevel.Hidden,
            pinned = true,
            launchCount = 5,
            weight = 0.5,
        )
        awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.launchCount == 5 }
        }

        item.serialized = "changed"
        repository.upsert(item)
        val entity = awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.serializedSearchable == "changed" }
        }
        assertEquals(VisibilityLevel.Hidden.value, entity.visibility)
        assertEquals(5, entity.launchCount)
        assertEquals(0.5, entity.weight, 0.0)
        assertEquals(1, entity.pinPosition)
    }

    // ----- atomic replacement of one type's manual pins (#3 D4) -----

    private suspend fun pinned(key: String, type: String, pinPosition: Int, serialized: String = key) {
        database.searchableDao().upsert(
            SavedSearchableEntity(
                key = key,
                type = type,
                serializedSearchable = serialized,
                launchCount = 0,
                pinPosition = pinPosition,
                visibility = VisibilityLevel.Default.value,
                weight = 0.0,
            )
        )
    }

    private suspend fun pinPosition(key: String) = database.searchableDao().getByKey(key).first()!!.pinPosition

    @Test
    fun replaceManuallySortedAwaited_keepsOtherTypesInOrderAfterTheItems() = runBlocking {
        // Mixed on the device: other types around an app the new list drops.
        pinned("contact://1", "contact", 6)
        pinned("app://old", "app", 5)
        pinned("shortcut://s", "shortcut", 4)
        pinned("tag://t", "tag", 3)
        pinned("app://auto", "app", 1)

        repository.replaceManuallySortedAwaited(
            types = listOf("app"),
            items = listOf(TestSearchable("app://a", domain = "app"), TestSearchable("app://b", domain = "app")),
        )

        assertEquals(
            listOf("app://a", "app://b", "contact://1", "shortcut://s", "tag://t", "app://auto"),
            pinnedKeys().first(),
        )
        assertEquals(0, pinPosition("app://old"))
        // Automatically sorted pins are not touched.
        assertEquals(1, pinPosition("app://auto"))
    }

    @Test
    fun replaceManuallySortedAwaited_keepsAPinThatNoLongerDeserializes() = runBlocking {
        // A shortcut whose app is gone: the row stays, it is never deserialized.
        pinned("shortcut://gone", "shortcut", 2, serialized = "not a shortcut any more")

        repository.replaceManuallySortedAwaited(types = listOf("app"), items = listOf(TestSearchable("app://a", domain = "app")))

        assertEquals(listOf("app://a", "shortcut://gone"), pinnedKeys().first())
        assertTrue(pinPosition("shortcut://gone") > 1)
    }

    @Test
    fun replaceManuallySortedAwaited_withNoItemsUnpinsOnlyThatType() = runBlocking {
        pinned("app://old", "app", 3)
        pinned("tag://t", "tag", 2)

        repository.replaceManuallySortedAwaited(types = listOf("app"), items = emptyList())

        assertEquals(0, pinPosition("app://old"))
        assertEquals(listOf("tag://t"), pinnedKeys().first())
    }

    // ----- setVisibilitiesAwaited (#3 slice 4) -----

    /** Written when it returns: a reload reads back right after, and a fire-and-forget write races that read. */
    @Test
    fun setVisibilitiesAwaited_isWrittenWhenItReturns() = runBlocking {
        repository.setVisibilitiesAwaited(
            mapOf(TestSearchable("a") to VisibilityLevel.Hidden, TestSearchable("b") to VisibilityLevel.SearchOnly),
        )

        assertEquals(VisibilityLevel.Hidden.value, database.searchableDao().getByKey("a").first()!!.visibility)
        assertEquals(VisibilityLevel.SearchOnly.value, database.searchableDao().getByKey("b").first()!!.visibility)
    }

    /** Only the visibility changes: an item's pin and launch count are its own. */
    @Test
    fun setVisibilitiesAwaited_keepsThePinAndTheLaunchCount() = runBlocking {
        database.searchableDao().insert(
            SavedSearchableEntity(key = "a", type = "test", serializedSearchable = "a", launchCount = 7, pinPosition = 3, visibility = 0, weight = 0.5),
        )

        repository.setVisibilitiesAwaited(mapOf(TestSearchable("a") to VisibilityLevel.Hidden))

        val entity = database.searchableDao().getByKey("a").first()!!
        assertEquals(VisibilityLevel.Hidden.value, entity.visibility)
        assertEquals(7, entity.launchCount)
        assertEquals(3, entity.pinPosition)
        assertEquals(0.5, entity.weight, 0.0)
    }

    /** Back to default on an item that has a row. */
    @Test
    fun setVisibilitiesAwaited_resetsToDefault() = runBlocking {
        repository.setVisibilitiesAwaited(mapOf(TestSearchable("a") to VisibilityLevel.Hidden))

        repository.setVisibilitiesAwaited(mapOf(TestSearchable("a") to VisibilityLevel.Default))

        assertEquals(VisibilityLevel.Default.value, database.searchableDao().getByKey("a").first()!!.visibility)
    }

    // ----- insertAwaited (#3 slice 4) -----

    /**
     * A row exists when it returns: a label written right after is anchored,
     * and the cleanup, which removes labels without a row, cannot take it in
     * between (review on #207).
     */
    @Test
    fun insertAwaited_isWrittenWhenItReturns() = runBlocking {
        // Many, so a fire-and-forget write cannot finish before the count:
        // with two items it did, and this test stayed green without the await.
        val keys = (1..2000).map { "k$it" }

        repository.insertAwaited(keys.map { TestSearchable(it) })

        assertEquals(keys.size, database.searchableDao().getByKeys(keys).first().size)
    }

    /** An existing row is the item's own: its pin, launch count and visibility stay. */
    @Test
    fun insertAwaited_keepsAnExistingRow() = runBlocking {
        database.searchableDao().insert(
            SavedSearchableEntity(key = "a", type = "test", serializedSearchable = "a", launchCount = 7, pinPosition = 3, visibility = 2, weight = 0.5),
        )

        repository.insertAwaited(listOf(TestSearchable("a")))

        val entity = database.searchableDao().getByKey("a").first()!!
        assertEquals(7, entity.launchCount)
        assertEquals(3, entity.pinPosition)
        assertEquals(2, entity.visibility)
    }

    private class TestSearchable(
        override val key: String,
        var serialized: String = key,
        override val domain: String = "test",
    ) : SavableSearchable {
        override val label: String = key
        override val preferDetailsOverLaunch: Boolean = false

        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?): Boolean = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon =
            throw NotImplementedError()

        override fun getSerializer(): SearchableSerializer = object : SearchableSerializer {
            override val typePrefix: String = "test"
            override fun serialize(searchable: SavableSearchable): String =
                (searchable as TestSearchable).serialized
        }
    }
}

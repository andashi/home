package de.mm20.launcher2.searchable

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.CustomAttributeEntity
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.Resolved
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableDeserializer
import de.mm20.launcher2.search.SearchableSerializer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
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
        stopKoin()
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

    // ----- a row is deleted only when its item is known to be gone (#237) -----

    /**
     * Resolves by the serialized form: `found`, `gone` or `unknown`. Its
     * [deserialize] keeps the old contract, null for anything not found, which
     * is what the contacts deserializer answered without its permission - so
     * a repository still reading [deserialize] cannot tell the two apart.
     */
    private val resolvingDeserializer = object : SearchableDeserializer {
        override suspend fun resolve(serialized: String): Resolved = when (serialized) {
            "found" -> Resolved.Found(TestSearchable("resolving://found", domain = "resolving"))
            "gone" -> Resolved.Gone
            "unknown" -> Resolved.Unknown
            else -> throw IllegalArgumentException(serialized)
        }

        override suspend fun deserialize(serialized: String): SavableSearchable? =
            (resolve(serialized) as? Resolved.Found)?.searchable
    }

    private suspend fun resolvingRows() {
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("resolving")) { resolvingDeserializer } }) }
        pinned("resolving://found", "resolving", 3, serialized = "found")
        pinned("resolving://unknown", "resolving", 2, serialized = "unknown")
        pinned("resolving://gone", "resolving", 1, serialized = "gone")
    }

    private suspend fun row(key: String) = database.searchableDao().getByKey(key).first()

    /**
     * Revoking the contacts permission deleted every contact pin on the next
     * favorites load, and left its tags and labels to the orphan cleanup.
     */
    @Test
    fun readingKeepsARowWhoseItemIsUnknown() = runBlocking {
        resolvingRows()

        val read = repository.getByKeys(listOf("resolving://found", "resolving://unknown", "resolving://gone")).first()

        assertEquals(listOf("resolving://found"), read.map { it.key })
        // The deletes are fire-and-forget: the gone row's is the barrier.
        awaitValue { if (row("resolving://gone") == null) true else null }
        delay(500)
        assertNotNull("an item that could not be resolved lost its row", row("resolving://unknown"))
    }

    /** Control, green in both states: a row whose item is gone is still deleted, as for an uninstalled app. */
    @Test
    fun readingDeletesARowWhoseItemIsGone() = runBlocking {
        resolvingRows()

        repository.getByKeys(listOf("resolving://gone")).first()

        awaitValue { if (row("resolving://gone") == null) true else null }
        assertNotNull(row("resolving://found"))
    }

    /**
     * A kept row that does not resolve must not take a slot of a limited read:
     * the limit is applied in SQL, before unresolved rows are dropped, so one
     * Unknown contact ahead of an app left the favorites one short - or empty
     * with a limit of one (review on #241). On main such a row was deleted,
     * so keeping it is what made this reachable.
     */
    @Test
    fun aRowThatDoesNotResolveDoesNotTakeASlotOfALimitedRead() = runBlocking {
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("resolving")) { resolvingDeserializer } }) }
        pinned("resolving://unknown", "resolving", 3, serialized = "unknown")
        pinned("resolving://found", "resolving", 2, serialized = "found")

        val read = repository.get(limit = 1).first()

        assertEquals(listOf("resolving://found"), read.map { it.key })
    }

    /** Control, green in both states: with every row resolving, the limit cuts as before. */
    @Test
    fun aLimitedReadStillStopsAtItsLimit() = runBlocking {
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("resolving")) { resolvingDeserializer } }) }
        pinned("resolving://found", "resolving", 3, serialized = "found")
        pinned("resolving://found2", "resolving", 2, serialized = "found")

        val read = repository.get(limit = 1).first()

        assertEquals(1, read.size)
    }

    /**
     * The worst case of filling a limit: nearly every row stays Unknown (a
     * device with most pinned contacts hidden by Contact Scopes). The read
     * doubles its fetch until the rows run out, so it resolves fewer than
     * three times the rows - the doubled fetches sum to under twice the table,
     * the last one reads it whole - in about log2(rows / limit) + 2 queries.
     */
    @Test
    fun fillingALimitPastManyUnknownRowsIsBounded() = runBlocking {
        val resolves = java.util.concurrent.atomic.AtomicInteger()
        val counting = object : SearchableDeserializer {
            override suspend fun resolve(serialized: String): Resolved {
                resolves.incrementAndGet()
                return resolvingDeserializer.resolve(serialized)
            }
            override suspend fun deserialize(serialized: String): SavableSearchable? =
                (resolve(serialized) as? Resolved.Found)?.searchable
        }
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("resolving")) { counting } }) }
        val rows = 1000
        for (i in 1..rows) pinned("resolving://unknown$i", "resolving", rows + 10 - i, serialized = "unknown")
        pinned("resolving://found", "resolving", 1, serialized = "found")

        val started = System.nanoTime()
        val read = repository.get(limit = 5).first()
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("resolving://found"), read.map { it.key })
        println("filling limit 5 past $rows unknown rows: ${resolves.get()} resolves, $millis ms")
        assertTrue("resolves ${resolves.get()} exceed three times the ${rows + 1} rows", resolves.get() < 3 * (rows + 1))
    }

    /** The debug screen's cleanup took the same path. */
    @Test
    fun cleanupKeepsARowWhoseItemIsUnknown() = runBlocking {
        resolvingRows()

        val removed = repository.cleanupDatabase()

        assertEquals("only the gone row counts as removed", 1, removed)
        awaitValue { if (row("resolving://gone") == null) true else null }
        delay(500)
        assertNotNull("an item that could not be resolved lost its row", row("resolving://unknown"))
        assertNotNull(row("resolving://found"))
    }

    /**
     * A source whose answer changes: Unknown until [visible] turns true, as a
     * contact is without its permission. It announces the change through
     * [SearchableDeserializer.resolveAgain].
     */
    private inner class TogglingDeserializer : SearchableDeserializer {
        val visible = MutableStateFlow(false)
        override suspend fun resolve(serialized: String): Resolved =
            if (visible.value) Resolved.Found(TestSearchable("toggling://a", domain = "toggling")) else Resolved.Unknown

        override suspend fun deserialize(serialized: String): SavableSearchable? =
            (resolve(serialized) as? Resolved.Found)?.searchable

        override val resolveAgain: Flow<Unit> get() = visible.drop(1).map { }
    }

    /**
     * Granting the permission did not bring a contact favorite back: the flow
     * kept its first answer until the database happened to change (review on
     * #241).
     */
    @Test
    fun anItemThatResolvesLaterAppearsWithoutADatabaseChange() = runBlocking {
        val toggling = TogglingDeserializer()
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("toggling")) { toggling } }) }
        pinned("toggling://a", "toggling", 1, serialized = "a")

        val seen = Channel<List<String>>(Channel.UNLIMITED)
        val job = launch { repository.getByKeys(listOf("toggling://a")).collect { seen.send(it.map { s -> s.key }) } }
        try {
            assertEquals(emptyList<String>(), withTimeout(5000) { seen.receive() })
            toggling.visible.value = true
            val next = withTimeout(5000) {
                var got = seen.receive()
                while (got.isEmpty()) got = seen.receive()
                got
            }
            assertEquals(listOf("toggling://a"), next)
            assertNotNull("the row is still there", row("toggling://a"))
        } finally {
            job.cancel()
        }
    }

    /**
     * A deserializer Koin fails to create says nothing about the item: the
     * row stays (review on #241). A type with no deserializer at all is still
     * gone - the control, green in both states.
     */
    @Test
    fun aDeserializerThatCannotBeCreatedKeepsItsRow() = runBlocking {
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("broken")) { throw IllegalStateException("cannot be created") } }) }
        pinned("broken://a", "broken", 2, serialized = "a")
        pinned("undefined://a", "undefined", 1, serialized = "a")

        repository.getByKeys(listOf("broken://a", "undefined://a")).first()

        awaitValue { if (row("undefined://a") == null) true else null }
        delay(500)
        assertNotNull("a deserializer that could not be created cost the row", row("broken://a"))
    }

    // ----- a moved item takes its row and customizations to its new key (#237) -----

    /** `old` has moved to `moving://new`; once there, it is found under it. */
    private val movingDeserializer = object : SearchableDeserializer {
        override suspend fun resolve(serialized: String): Resolved = when (serialized) {
            "old" -> Resolved.Moved(TestSearchable("moving://new", domain = "moving"))
            "moving://new" -> Resolved.Found(TestSearchable("moving://new", domain = "moving"))
            else -> throw IllegalArgumentException(serialized)
        }
        override suspend fun deserialize(serialized: String): SavableSearchable? = when (val r = resolve(serialized)) {
            is Resolved.Found -> r.searchable
            is Resolved.Moved -> r.searchable
            else -> null
        }
    }

    private fun movingKoin(deserializer: SearchableDeserializer = movingDeserializer) {
        stopKoin()
        startKoin { modules(module { factory<SearchableDeserializer>(named("moving")) { deserializer } }) }
    }

    private suspend fun attrs(key: String, type: String) =
        database.customAttrsDao().getCustomAttributes(listOf(key), type).first().map { it.value }.sorted()

    private suspend fun attr(key: String, type: String, value: String) =
        database.customAttrsDao().insertCustomAttributes(listOf(CustomAttributeEntity(key = key, type = type, value = value)))

    private suspend fun awaitMoved() = awaitValue { if (row("moving://old") == null && row("moving://new") != null) true else null }

    @Test
    fun aMovedItemTakesItsRowAndItsCustomizationsToTheNewKey() = runBlocking {
        movingKoin()
        database.searchableDao().insert(
            SavedSearchableEntity(key = "moving://old", type = "moving", serializedSearchable = "old", launchCount = 3, pinPosition = 2, visibility = VisibilityLevel.SearchOnly.value, weight = 0.2),
        )
        attr("moving://old", "tag", "family")
        attr("moving://old", "label", "Mum")
        attr("moving://old", "icon", "icon-a")

        val read = repository.getByKeys(listOf("moving://old")).first()
        awaitMoved()

        assertEquals(listOf("moving://new"), read.map { it.key })
        val moved = row("moving://new")!!
        assertEquals("moving://new", moved.serializedSearchable)
        assertEquals("moving", moved.type)
        assertEquals(listOf(3, 2, VisibilityLevel.SearchOnly.value), listOf(moved.launchCount, moved.pinPosition, moved.visibility))
        assertEquals(0.2, moved.weight, 0.0)
        assertEquals(listOf("family"), attrs("moving://new", "tag"))
        assertEquals(listOf("Mum"), attrs("moving://new", "label"))
        assertEquals(listOf("icon-a"), attrs("moving://new", "icon"))
        for (type in listOf("tag", "label", "icon")) assertEquals("old key kept a $type", emptyList<String>(), attrs("moving://old", type))
    }

    /**
     * Two rows for one item - the old one, and one written under the new key
     * by a launch or a pin in between - become one: launches add up, the
     * higher pin and weight win, the stricter visibility wins (un-hiding by a
     * merge would be the dangerous direction), tags are united without
     * duplicates, and a label or icon fills a gap. When both rows carry a
     * label the target's wins: an arbitrary tiebreak, nothing records which
     * one the person meant last.
     */
    @Test
    fun aCollisionMergesTheTwoRows() = runBlocking {
        movingKoin()
        database.searchableDao().insert(
            SavedSearchableEntity(key = "moving://old", type = "moving", serializedSearchable = "old", launchCount = 3, pinPosition = 5, visibility = VisibilityLevel.Hidden.value, weight = 0.2),
        )
        database.searchableDao().insert(
            SavedSearchableEntity(key = "moving://new", type = "moving", serializedSearchable = "moving://new", launchCount = 4, pinPosition = 1, visibility = VisibilityLevel.Default.value, weight = 0.5),
        )
        attr("moving://old", "tag", "a"); attr("moving://old", "tag", "b")
        attr("moving://new", "tag", "b"); attr("moving://new", "tag", "c")
        attr("moving://old", "label", "Old"); attr("moving://new", "label", "New")
        attr("moving://old", "icon", "icon-old")

        repository.getByKeys(listOf("moving://old")).first()
        awaitMoved()

        val merged = row("moving://new")!!
        assertEquals("launches add up", 7, merged.launchCount)
        assertEquals("the higher pin wins", 5, merged.pinPosition)
        assertEquals("the stricter visibility wins", VisibilityLevel.Hidden.value, merged.visibility)
        assertEquals("the higher weight wins", 0.5, merged.weight, 0.0)
        assertEquals("tags united, no duplicates", listOf("a", "b", "c"), attrs("moving://new", "tag"))
        assertEquals("both labelled: the target's (tiebreak)", listOf("New"), attrs("moving://new", "label"))
        assertEquals("an icon fills the gap", listOf("icon-old"), attrs("moving://new", "icon"))
        for (type in listOf("tag", "label", "icon")) assertEquals("old key kept a $type", emptyList<String>(), attrs("moving://old", type))
    }

    /** The debug screen's cleanup moves a moved item too, rather than leaving or deleting it. */
    @Test
    fun cleanupMovesAMovedItem() = runBlocking {
        movingKoin()
        database.searchableDao().insert(
            SavedSearchableEntity(key = "moving://old", type = "moving", serializedSearchable = "old", launchCount = 3, pinPosition = 2, visibility = 0, weight = 0.2),
        )
        attr("moving://old", "tag", "family")

        val removed = repository.cleanupDatabase()
        awaitMoved()

        assertEquals("a moved item is not removed", 0, removed)
        assertEquals(listOf("family"), attrs("moving://new", "tag"))
    }

    /**
     * A fresh search result carries the current key, and never passes through
     * the repository; the refresh is what moves a drifted row before such a
     * result goes looking for its customizations (#237). Only types whose keys
     * can move are resolved, so apps cost nothing on a resume.
     */
    @Test
    fun theRefreshMovesDriftedRowsOfTypesWhoseKeysMove() = runBlocking {
        val unmovingResolves = java.util.concurrent.atomic.AtomicInteger()
        val moving = object : SearchableDeserializer by movingDeserializer {
            override val storedKeysMove = true
            override suspend fun resolve(serialized: String): Resolved =
                if (serialized == "hidden-by-scopes") Resolved.Unknown else movingDeserializer.resolve(serialized)
        }
        val unmoving = object : SearchableDeserializer {
            override suspend fun resolve(serialized: String): Resolved {
                unmovingResolves.incrementAndGet()
                return Resolved.Moved(TestSearchable("unmoving://new", domain = "unmoving"))
            }
            override suspend fun deserialize(serialized: String): SavableSearchable? = null
        }
        stopKoin()
        startKoin { modules(module {
            factory<SearchableDeserializer>(named("moving")) { moving }
            factory<SearchableDeserializer>(named("unmoving")) { unmoving }
        }) }
        pinned("moving://old", "moving", 0, serialized = "old")
        pinned("moving://scoped", "moving", 0, serialized = "hidden-by-scopes")
        pinned("unmoving://old", "unmoving", 0, serialized = "old")
        attr("moving://old", "tag", "family")

        repository.refreshMovedKeys()

        assertEquals(null, row("moving://old"))
        assertEquals(listOf("family"), attrs("moving://new", "tag"))
        assertNotNull("an Unknown row stays where it is", row("moving://scoped"))
        assertNotNull(row("unmoving://old"))
        assertEquals("a type whose keys do not move is never resolved", 0, unmovingResolves.get())
    }

    /**
     * The one piece whose failure lands on a person (#237). A search result
     * carries the item's current key; a hidden contact whose key moved - a
     * rename changes a lookup key - must still be hidden under the key it has
     * now, not only the one it was hidden under, or it reappears in search.
     * The rows are written the way the customize sheet hides an item, through
     * upsert; the visible one is the control that gives "hidden" a meaning:
     * its current key must not be in the set.
     */
    @Test
    fun aHiddenItemIsHiddenUnderTheKeyItHasNow() = runBlocking {
        val renamed = object : SearchableDeserializer {
            override val storedKeysMove = true
            override suspend fun resolve(serialized: String): Resolved = when (serialized) {
                "bob" -> Resolved.Moved(TestSearchable("moving://bob-renamed", domain = "moving"))
                "alice" -> Resolved.Moved(TestSearchable("moving://alice-renamed", domain = "moving"))
                else -> Resolved.Found(TestSearchable(serialized, domain = "moving"))
            }
            override suspend fun deserialize(serialized: String): SavableSearchable? = null
        }
        movingKoin(renamed)
        repository.upsert(TestSearchable("moving://bob", serialized = "bob", domain = "moving"), visibility = VisibilityLevel.Hidden)
        repository.upsert(TestSearchable("moving://alice", serialized = "alice", domain = "moving"), visibility = VisibilityLevel.Default)
        awaitValue { if (row("moving://bob") != null && row("moving://alice") != null) true else null }

        val hidden = repository.hiddenKeys().first()

        assertTrue("the hidden contact reappears under its new key: $hidden", "moving://bob-renamed" in hidden)
        assertTrue("a visible contact is hidden: $hidden", "moving://alice-renamed" !in hidden && "moving://alice" !in hidden)
    }

    /** Control, green in both states: an item Found under a different key is not moved - apps resolve to aliases. */
    @Test
    fun anItemFoundUnderADifferentKeyStaysWhereItIs() = runBlocking {
        movingKoin(object : SearchableDeserializer {
            override suspend fun resolve(serialized: String): Resolved = Resolved.Found(TestSearchable("moving://new", domain = "moving"))
            override suspend fun deserialize(serialized: String): SavableSearchable? = TestSearchable("moving://new", domain = "moving")
        })
        database.searchableDao().insert(
            SavedSearchableEntity(key = "moving://old", type = "moving", serializedSearchable = "old", launchCount = 3, pinPosition = 2, visibility = 0, weight = 0.2),
        )
        attr("moving://old", "tag", "family")

        repository.getByKeys(listOf("moving://old")).first()
        delay(500)

        assertNotNull(row("moving://old"))
        assertEquals(null, row("moving://new"))
        assertEquals(listOf("family"), attrs("moving://old", "tag"))
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
            override val typePrefix: String = domain
            override fun serialize(searchable: SavableSearchable): String =
                (searchable as TestSearchable).serialized
        }
    }
}

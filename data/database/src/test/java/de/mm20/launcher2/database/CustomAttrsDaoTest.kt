package de.mm20.launcher2.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.entities.CustomAttributeEntity
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Labels as the config writes and reads them (#3 slice 4), and the cleanup
 * that left orphaned icon rows behind forever.
 */
@RunWith(RobolectricTestRunner::class)
class CustomAttrsDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: CustomAttrsDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.customAttrsDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun label(key: String, value: String) = CustomAttributeEntity(key, "label", value)

    private suspend fun rows() = database.backupDao().exportCustomAttributes(1000, 0)
        .map { Triple(it.key, it.type, it.value) }.sortedBy { "${it.first}|${it.second}|${it.third}" }

    // ---- labels ----

    @Test
    fun `replacing labels sets the new ones and clears the others of those keys`() = runBlocking {
        dao.insertCustomAttributes(listOf(label("app://a:A", "Old A"), label("app://b:B", "Old B")))

        dao.replaceLabels(keys = listOf("app://a:A", "app://b:B"), labels = listOf(label("app://a:A", "New A")))

        assertEquals(listOf(Triple("app://a:A", "label", "New A")), rows())
    }

    /** Keys the call does not name are not its business: a contact's name, a shortcut's. */
    @Test
    fun `replacing labels leaves keys it does not name alone`() = runBlocking {
        dao.insertCustomAttributes(listOf(label("contact://1", "Mum"), label("app://a:A", "Old A")))

        dao.replaceLabels(keys = listOf("app://a:A"), labels = emptyList())

        assertEquals(listOf(Triple("contact://1", "label", "Mum")), rows())
    }

    /** Past the most parameters SQLite takes in one statement (32766 since 3.32, 999 before). */
    @Test
    fun `replacing labels handles more keys than one statement takes`() = runBlocking {
        val keys = (1..33000).map { "app://p$it:A" }
        dao.insertCustomAttributes(keys.map { label(it, "x") })

        dao.replaceLabels(keys = keys, labels = listOf(label("app://p1:A", "y")))

        assertEquals(listOf(Triple("app://p1:A", "label", "y")), rows())
    }

    @Test
    fun `the app labels are the labels of app keys only`() = runBlocking {
        dao.insertCustomAttributes(
            listOf(
                label("app://a:A", "Chat"),
                label("contact://1", "Mum"),
                CustomAttributeEntity("app://a:A", "tag", "Work"),
            )
        )

        val labels = dao.getAppLabels().first().map { it.key to it.value }

        assertEquals(listOf("app://a:A" to "Chat"), labels)
    }

    // ---- icons (#3 slice 4, PR 2): the same replace, another type ----

    private fun icon(key: String, json: String) = CustomAttributeEntity(key, "icon", json)

    @Test
    fun `replacing icons sets the new ones and clears the others of those keys`() = runBlocking {
        dao.insertCustomAttributes(listOf(icon("app://a:A", "old-a"), icon("app://b:B", "old-b")))

        dao.replaceAttributes("icon", keys = listOf("app://a:A", "app://b:B"), entities = listOf(icon("app://a:A", "new-a")))

        assertEquals(listOf(Triple("app://a:A", "icon", "new-a")), rows())
    }

    /** One type at a time: an app's icons replaced leave its label and its tags alone. */
    @Test
    fun `replacing icons leaves the other attributes of the same keys alone`() = runBlocking {
        dao.insertCustomAttributes(
            listOf(icon("app://a:A", "old"), label("app://a:A", "Chat"), CustomAttributeEntity("app://a:A", "tag", "Work")),
        )

        dao.replaceAttributes("icon", keys = listOf("app://a:A"), entities = emptyList())

        assertEquals(listOf(Triple("app://a:A", "label", "Chat"), Triple("app://a:A", "tag", "Work")), rows())
    }

    @Test
    fun `the app icons are the icons of app keys only`() = runBlocking {
        dao.insertCustomAttributes(listOf(icon("app://a:A", "a"), icon("tag://Work", "t"), label("app://a:A", "Chat")))

        val icons = dao.getAppAttributes("icon").first().map { it.key to it.value }

        assertEquals(listOf("app://a:A" to "a"), icons)
    }

    /** A tag's icon is keyed `tag://<name>`: what the config's `tags` reads, and nothing of an app's. */
    @Test
    fun `the tag icons are the icons of tag keys only`() = runBlocking {
        dao.insertCustomAttributes(
            listOf(icon("app://a:A", "a"), icon("tag://Work", "t"), CustomAttributeEntity("app://a:A", "tag", "Work")),
        )

        val icons = dao.getTagAttributes("icon").first().map { it.key to it.value }

        assertEquals(listOf("tag://Work" to "t"), icons)
    }

    // ---- the cleanup ----

    private suspend fun searchable(key: String) = database.searchableDao().insert(
        SavedSearchableEntity(key = key, type = "app", serializedSearchable = key, launchCount = 0, pinPosition = 0, visibility = 0, weight = 0.0),
    )

    /**
     * Without a row, an item's label and tags go. Its icon stays: the icon
     * picker writes an icon without giving the item a row (only labels and
     * tags get one), so a missing row says nothing about an icon - it would
     * delete one set on an app never launched or pinned (review on #207).
     */
    @Test
    fun `the cleanup removes an item's labels and tags without a row, never its icon`() = runBlocking {
        dao.insertCustomAttributes(
            listOf(
                label("app://gone:A", "Gone"),
                CustomAttributeEntity("app://gone:A", "tag", "Old"),
                CustomAttributeEntity("app://gone:A", "icon", "{\"type\":\"force_themed_icon\"}"),
            )
        )

        database.backupDao().cleanUp()

        assertEquals(listOf(Triple("app://gone:A", "icon", "{\"type\":\"force_themed_icon\"}")), rows())
    }

    /** A pinned tag has a row of its own, so its icon stays while no item carries the tag. */
    @Test
    fun `the cleanup keeps the icon of a pinned tag nobody carries`() = runBlocking {
        searchable("tag://Pinned")
        dao.insertCustomAttributes(listOf(CustomAttributeEntity("tag://Pinned", "icon", "{\"type\":\"custom_text_icon\"}")))

        database.backupDao().cleanUp()

        assertEquals(1, rows().size)
    }

    /** Control: an item that still has its row keeps everything. */
    @Test
    fun `the cleanup keeps what an item with a row has`() = runBlocking {
        searchable("app://here:A")
        dao.insertCustomAttributes(
            listOf(label("app://here:A", "Here"), CustomAttributeEntity("app://here:A", "icon", "{}")),
        )

        database.backupDao().cleanUp()

        assertEquals(2, rows().size)
    }

    /**
     * A tag's icon is keyed by the tag, and a tag nobody pinned has no row:
     * it is kept as long as some item still carries the tag, and goes with
     * the tag's last use.
     */
    @Test
    fun `the cleanup keeps the icon of a tag still in use and drops one no item carries`() = runBlocking {
        searchable("app://here:A")
        dao.insertCustomAttributes(
            listOf(
                CustomAttributeEntity("app://here:A", "tag", "Work"),
                CustomAttributeEntity("tag://Work", "icon", "{\"type\":\"custom_text_icon\"}"),
                CustomAttributeEntity("tag://Unused", "icon", "{\"type\":\"custom_text_icon\"}"),
            )
        )

        database.backupDao().cleanUp()

        assertEquals(
            listOf(Triple("app://here:A", "tag", "Work"), Triple("tag://Work", "icon", "{\"type\":\"custom_text_icon\"}")),
            rows(),
        )
    }
}

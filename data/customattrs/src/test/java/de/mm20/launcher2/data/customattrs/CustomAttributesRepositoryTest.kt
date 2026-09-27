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

    // ---- the config's icons (#3 slice 4, PR 2) ----

    /**
     * Awaited, as the labels are: a reload reads back right after. The whole
     * state of the items named - one gets its icon, the other loses its own.
     */
    @Test
    fun `replacing icons is the whole state of the items named, written when it returns`() = runBlocking {
        val a = TestSearchable("app://a:A")
        val b = TestSearchable("app://b:B")
        database.customAttrsDao().setCustomAttribute(
            de.mm20.launcher2.database.entities.CustomAttributeEntity(b.key, "icon", """{"type":"force_themed_icon"}"""),
        )

        repository.replaceCustomIconsAwaited(listOf(a, b), mapOf(a.key to UnmodifiedSystemDefaultIcon))

        assertEquals(mapOf(a.key to UnmodifiedSystemDefaultIcon), repository.getAppIcons().first())
    }

    /** What the picker wrote reads back through the same decoder, every form of it. */
    @Test
    fun `the app icons read back every form the picker writes`() = runBlocking {
        val icons = mapOf(
            "app://a:A" to ForceThemedIcon,
            "app://b:B" to UnmodifiedSystemDefaultIcon,
            "app://c:C" to DefaultPlaceholderIcon,
            "app://d:D" to AdaptifiedLegacyIcon(fgScale = 0.7f, bgColor = 1),
            "app://e:E" to CustomIconPackIcon(iconPackPackage = "p.q", type = "app", drawable = "d", extras = null, allowThemed = true),
        )
        repository.replaceCustomIconsAwaited(icons.keys.map { TestSearchable(it) }, icons)

        assertEquals(icons, repository.getAppIcons().first())
    }

    // ---- a stored custom_themed_icon row (#3 slice 4, PR 2) ----

    /**
     * `custom_themed_icon` was a row type nothing wrote any more and whose
     * provider returned null, so a row of it - from an old install or a
     * restored backup - drew the app's normal icon. The type is gone; such a
     * row, stored and decoded the real way, reads as no custom icon, which is
     * what it already drew. Nobody's screen changes.
     */
    @Test
    fun `a stored custom_themed_icon row reads as no custom icon`() = runBlocking {
        val app = TestSearchable("app://a:A")
        database.customAttrsDao().setCustomAttribute(
            de.mm20.launcher2.database.entities.CustomAttributeEntity(
                app.key, "icon", """{"type":"custom_themed_icon","icon":"com.example.pack"}""",
            ),
        )

        assertEquals(null, repository.getCustomIcon(app).first())
    }

    /** Control: the picker's force-themed, a live form, still reads back. */
    @Test
    fun `a stored force_themed_icon row reads as force-themed`() = runBlocking {
        val app = TestSearchable("app://a:A")
        database.customAttrsDao().setCustomAttribute(
            de.mm20.launcher2.database.entities.CustomAttributeEntity(app.key, "icon", """{"type":"force_themed_icon"}"""),
        )

        assertEquals(ForceThemedIcon, repository.getCustomIcon(app).first())
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

package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMigrations
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.GridConfig
import de.mm20.launcher2.config.GridItemConfig
import de.mm20.launcher2.config.GridLayoutConfig
import de.mm20.launcher2.config.JsoncObjectSpan
import de.mm20.launcher2.config.JsoncSpanResult
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.homegrid.HomeGridWidgets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Write-back (ADR 0003 section 5): edit mode writes the grid back into the
 * file, through the engine every section's change goes through (#3 slice 4).
 */
@RunWith(RobolectricTestRunner::class)
class GridWriteBackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File get() = ConfigLocation.configFile(context)!!
    private val reportStore = ReloadReportStore(context)
    private val repository = RecordingHomeGridRepository()

    private val dock = GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1)
    private val clock = GridItemConfig(
        id = "clock", widget = "com.android.deskclock/.DigitalAppWidgetProvider",
        x = 0, y = 0, w = 4, h = 2, borderless = false, background = true, themeColors = true,
    )
    private val gridState = ConfigState(
        gridColumns = 4,
        gridLocked = false,
        gridLayouts = mapOf(HomeGridLayouts.Phone to GridLayoutConfig(listOf(dock, clock))),
    )
    private val store = FakeConfigStore(state = gridState)

    private val items = listOf(
        HomeGridItem(HomeGridLayouts.Phone, "dock", HomeGridWidgets.Favorites, null, 0, 5, 4, 1, position = 0),
        HomeGridItem(HomeGridLayouts.Phone, "clock", clock.widget, null, 0, 0, 4, 2, appWidgetId = 7, position = 1),
    )

    private fun writeBack() = GridWriteBack(context, repository, store, reportStore, ConfigFileLock())

    private fun putFile(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Before
    fun cleanup() {
        file.delete()
        File(context.filesDir, "config/last-reload-report.json").delete()
        File(context.filesDir, "config/applied-baseline.json").delete()
    }

    @After
    fun closeRealStore() {
        real?.close()
    }

    private val documentWithGrid = """
        {
          // note: this comment must survive a write-back untouched
          "schemaVersion": 2,
          "icons": { "themed": true }, // trailing comment with a } brace
          "home": {
            "searchBar": { "position": "bottom" },
            "grid": {
              // this comment lives inside the grid and will be lost
              "columns": 4,
              "layouts": { "phone": { "items": [] } }
            },
            "favorites": [ "com.example.dialer" ]
          }
        }
    """.trimIndent()

    private fun gridOf(text: String): GridConfig =
        ConfigParser.parse(text).config!!.home!!.grid!!

    @Test
    fun `no file means the database is written and the file is skipped`() = runBlocking {
        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals(WriteBackResult.Skipped::class, result::class)
        assertEquals("no-config-file", (result as WriteBackResult.Skipped).code)
        assertEquals(items, repository.layouts[HomeGridLayouts.Phone])
        assertTrue(!file.exists())
        assertNull(reportStore.read())
    }

    @Test
    fun `a malformed file is never overwritten`() = runBlocking {
        val broken = """{ "schemaVersion": 2, "home": { "grid": { """
        putFile(broken)
        val before = file.readBytes()

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals("malformed-config", (result as WriteBackResult.Skipped).code)
        assertArrayEquals(before, file.readBytes())
        assertNull(reportStore.read())
    }

    @Test
    fun `a schemaVersion 1 file is not written back`() = runBlocking {
        // The launcher reads a v1 file through migration but writes only the
        // current schema; splicing a v2 grid next to the old dock keys would
        // leave a mixed shape nobody can regenerate from. Byte for byte
        // untouched, with a code the UI can explain.
        val v1 = """
            {
              "schemaVersion": 1,
              "home": {
                "dock": { "enabled": true, "favorites": [ "com.example.dialer" ] },
                "widgets": { "enabled": false, "widgets": ["apps"] }
              }
            }
        """.trimIndent()
        putFile(v1)
        val before = file.readBytes()

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals("schema-version-outdated", (result as WriteBackResult.Skipped).code)
        assertTrue(result.reason, result.reason.contains("schemaVersion 1"))
        assertArrayEquals(before, file.readBytes())
        assertEquals(items, repository.layouts[HomeGridLayouts.Phone])
        assertNull(reportStore.read())
    }

    @Test
    fun `a locked grid is not written back`() = runBlocking {
        val locked = documentWithGrid.replace("\"columns\": 4,", "\"columns\": 4, \"locked\": true,")
        putFile(locked)
        val before = file.readBytes()

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals("locked", (result as WriteBackResult.Skipped).code)
        assertArrayEquals(before, file.readBytes())
        assertEquals(items, repository.layouts[HomeGridLayouts.Phone])
    }

    @Test
    fun `the database is written before the file`() = runBlocking {
        putFile(documentWithGrid)
        repository.fileTextAtReplace = { file.readText() }

        writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals(documentWithGrid, repository.observedFileText)
        assertEquals(items, repository.layouts[HomeGridLayouts.Phone])
    }

    @Test
    fun `the lock is taken before the database is written`() = runBlocking {
        // Review on #68: with the DB write outside the lock, a reload that
        // already read the old file applies the old grid over the new rows,
        // and the write-back then mirrors the reverted state. The lock
        // comes first, then the database, then the file.
        putFile(documentWithGrid)
        val events = mutableListOf<String>()
        val lock = object : ConfigFileLock() {
            override suspend fun <T> withLock(block: suspend () -> T): T {
                events += "lock"
                return super.withLock { block().also { events += "unlock" } }
            }
        }
        val recording = RecordingHomeGridRepository().apply { onReplace = { events += "replace" } }

        GridWriteBack(context, recording, store, reportStore, lock).write(HomeGridLayouts.Phone, items)

        assertEquals(listOf("lock", "replace", "unlock"), events)
    }

    @Test
    fun `the last result is exposed for the UI`() = runBlocking {
        val writeBack = writeBack()
        assertNull(writeBack.lastResult.value)

        writeBack.write(HomeGridLayouts.Phone, items)

        assertEquals("no-config-file", (writeBack.lastResult.value as WriteBackResult.Skipped).code)
    }

    // ---- the file applied first (#3 slice 4) ----
    //
    // A write-back compares the device with what the file produced once
    // applied, so the tests below apply the file through the real reloader
    // into the real store first, and the baseline is the one production
    // records. The FakeConfigStore above holds only a grid: every other
    // section would serve its defaults, which a write-back for every section
    // rightly reads as edits. It was adequate for a grid-only write-back and
    // is not for a general one; the tests above keep it, because none of
    // them gets as far as comparing.

    private var real: RealConfigStore? = null
    private val baselines = AppliedBaselineStore(context)
    private val lock = ConfigFileLock()

    /** The file put and reloaded, as a device that has applied it; the grid write-back over that device. */
    private suspend fun appliedWriteBack(text: String): Pair<GridWriteBack, RealConfigStore> {
        putFile(text)
        val store = RealConfigStore().also { real = it }
        ConfigReloader(store.store, reportStore, lock, baselines).reload(file)
        return GridWriteBack(context, store.grid, store.store, reportStore, lock, baselineStore = baselines) to store
    }

    /** What the file gets for [items]: the device's items, without the options that are at their default. */
    private val writtenLayouts = mapOf(
        HomeGridLayouts.Phone to GridLayoutConfig(
            listOf(
                GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1),
                GridItemConfig(id = "clock", widget = clock.widget, x = 0, y = 0, w = 4, h = 2),
            )
        )
    )

    @Test
    fun `only the values the edit changed are rewritten, every other byte survives`() = runBlocking {
        val (writeBack, _) = appliedWriteBack(documentWithGrid)
        val span = JsoncObjectSpan.find(documentWithGrid, listOf("home", "grid", "layouts")) as JsoncSpanResult.Found

        val result = writeBack.write(HomeGridLayouts.Phone, items)

        assertTrue(result.toString(), result is WriteBackResult.Written)
        val after = file.readText()
        assertEquals(documentWithGrid.substring(0, span.start), after.substring(0, span.start))
        val oldTail = documentWithGrid.substring(span.endExclusive)
        assertEquals(oldTail, after.substring(after.length - oldTail.length))
        // W1: the file had no `locked`, so it gets none.
        assertEquals(GridConfig(columns = 4, locked = null, layouts = writtenLayouts), gridOf(after))
    }

    /** Was "lost, by design" while the whole grid object was replaced. A comment inside a rewritten list or map still is. */
    @Test
    fun `a comment inside the grid object survives the edit`() = runBlocking {
        val (writeBack, _) = appliedWriteBack(documentWithGrid)

        writeBack.write(HomeGridLayouts.Phone, items)

        assertTrue(file.readText().contains("this comment lives inside the grid"))
    }

    /**
     * W1, removed on purpose (ADR 0003): a file without home.grid used to
     * get one inserted. It is left byte for byte; the edit stays on the
     * device and the skip says how to make the file manage the grid.
     */
    @Test
    fun `a home without a grid is left as it is, and the edit stays on the device`() = runBlocking {
        val text = """
            {
              "schemaVersion": 2,
              "home": {
                "searchBar": { "position": "bottom" } // keep me
              }
            }
        """.trimIndent()
        val (writeBack, store) = appliedWriteBack(text)

        val result = writeBack.write(HomeGridLayouts.Phone, items)

        assertEquals("grid-unmanaged", (result as WriteBackResult.Skipped).code)
        assertEquals(text, file.readText())
        assertEquals(items, store.grid.layouts[HomeGridLayouts.Phone])
    }

    @Test
    fun `a document without home is left as it is, and the edit stays on the device`() = runBlocking {
        val text = """{ "schemaVersion": 2, "icons": { "themed": true } }"""
        val (writeBack, store) = appliedWriteBack(text)

        val result = writeBack.write(HomeGridLayouts.Phone, items)

        assertEquals("grid-unmanaged", (result as WriteBackResult.Skipped).code)
        assertEquals(text, file.readText())
        assertEquals(items, store.grid.layouts[HomeGridLayouts.Phone])
    }

    @Test
    fun `a trailing comma before the closing brace stays valid`() = runBlocking {
        val (writeBack, _) = appliedWriteBack(
            """
            {
              "schemaVersion": 2,
              "home": {
                "searchBar": { "position": "bottom" },
                "grid": { "layouts": { "phone": { "items": [], }, }, },
              },
            }
            """.trimIndent()
        )

        val result = writeBack.write(HomeGridLayouts.Phone, items)

        assertTrue(result.toString(), result is WriteBackResult.Written)
        assertEquals(writtenLayouts, gridOf(file.readText()).layouts)
    }

    @Test
    fun `a result that would exceed the parser limit is not written`() = runBlocking {
        // Just under the limit before, over it once the items are rendered.
        val body = "\"schemaVersion\": 2,\n\"home\": { \"grid\": { \"layouts\": { \"phone\": { \"items\": [] } } } }\n}\n"
        val padding = "// " + "x".repeat(ConfigParser.MaxInputBytes - body.length - 60) + "\n"
        val text = "{\n$padding$body"
        assertTrue(text.toByteArray().size <= ConfigParser.MaxInputBytes)
        val (writeBack, _) = appliedWriteBack(text)
        val before = file.readBytes()

        val result = writeBack.write(HomeGridLayouts.Phone, items)

        assertEquals("too-large", (result as WriteBackResult.Skipped).code)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `the report carries the self-write trigger, the changed path and the hash of the written bytes`() = runBlocking {
        val (writeBack, _) = appliedWriteBack(documentWithGrid)

        val result = writeBack.write(HomeGridLayouts.Phone, items) as WriteBackResult.Written

        val report = reportStore.read()!!
        assertEquals(ReloadTrigger.SelfWrite, report.trigger)
        assertEquals(true, report.success)
        assertEquals(listOf("home.grid.layouts"), report.appliedMutations)
        assertEquals(ConfigMigrations.currentSchemaVersion, report.schemaVersion)
        assertEquals(file.readBytes().sha256Hex(), report.configSha256)
        assertEquals(result.sha256, report.configSha256)
    }

    /**
     * The file gets the device's grid, merged into what it has: the layouts
     * it names and, for the items, every field except the options at their
     * default - no fold layout it did not have, no `borderless: false`.
     * Pushed back, it changes nothing.
     */
    @Test
    fun `the written grid is the device's, merged into the keys the file has`() = runBlocking {
        val (writeBack, _) = appliedWriteBack(documentWithGrid)

        writeBack.write(HomeGridLayouts.Phone, items)

        assertEquals(writtenLayouts, gridOf(file.readText()).layouts)
    }

    /** Records the file's text at the moment [replace] runs, to prove the write order. */
    private class RecordingHomeGridRepository : HomeGridRepository {
        val layouts = mutableMapOf<String, List<HomeGridItem>>()
        var fileTextAtReplace: (() -> String)? = null
        var observedFileText: String? = null
        var onReplace: (() -> Unit)? = null

        override fun observe(layout: String): Flow<List<HomeGridItem>> = flowOf(layouts[layout] ?: emptyList())

        override suspend fun replace(layout: String, items: List<HomeGridItem>) {
            observedFileText = fileTextAtReplace?.invoke()
            onReplace?.invoke()
            layouts[layout] = items
        }

        override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) =
            throw NotImplementedError()

        override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) =
            throw NotImplementedError()

        override suspend fun delete(layout: String, id: String) = throw NotImplementedError()
    }
}

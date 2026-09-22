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
 * Write-back (ADR 0003, D3): edit mode writes `home.grid` back into the
 * file, and only that object's text changes.
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
    fun `only the grid object changes, every other byte survives`() = runBlocking {
        putFile(documentWithGrid)
        val span = JsoncObjectSpan.find(documentWithGrid, listOf("home", "grid")) as JsoncSpanResult.Found

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertTrue(result.toString(), result is WriteBackResult.Written)
        val after = file.readText()
        assertEquals(documentWithGrid.substring(0, span.start), after.substring(0, span.start))
        val oldTail = documentWithGrid.substring(span.endExclusive)
        assertEquals(oldTail, after.substring(after.length - oldTail.length))
        assertTrue(after.contains("// note: this comment must survive a write-back untouched"))
        assertTrue(after.contains("// trailing comment with a } brace"))
        assertEquals(GridConfig(4, false, gridState.gridLayouts), gridOf(after))
    }

    @Test
    fun `a comment inside the old grid object is lost, by design`() = runBlocking {
        putFile(documentWithGrid)

        writeBack().write(HomeGridLayouts.Phone, items)

        assertTrue(!file.readText().contains("this comment lives inside the grid"))
    }

    @Test
    fun `a home without a grid gets one inserted and the file still parses`() = runBlocking {
        val text = """
            {
              "schemaVersion": 2,
              "home": {
                "searchBar": { "position": "bottom" } // keep me
              }
            }
        """.trimIndent()
        putFile(text)

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertTrue(result is WriteBackResult.Written)
        val after = file.readText()
        assertTrue(after.contains("// keep me"))
        assertEquals(GridConfig(4, false, gridState.gridLayouts), gridOf(after))
        assertEquals("bottom", ConfigParser.parse(after).config!!.home!!.searchBar!!.position!!.name.lowercase())
    }

    @Test
    fun `a document without home gets home and grid`() = runBlocking {
        putFile("""{ "schemaVersion": 2, "icons": { "themed": true } }""")

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertTrue(result is WriteBackResult.Written)
        val after = file.readText()
        assertEquals(GridConfig(4, false, gridState.gridLayouts), gridOf(after))
        assertEquals(true, ConfigParser.parse(after).config!!.icons!!.themed)
    }

    @Test
    fun `a trailing comma before the closing brace stays valid`() = runBlocking {
        putFile(
            """
            {
              "schemaVersion": 2,
              "home": {
                "searchBar": { "position": "bottom" },
              },
            }
            """.trimIndent()
        )

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertTrue(result is WriteBackResult.Written)
        assertEquals(GridConfig(4, false, gridState.gridLayouts), gridOf(file.readText()))
    }

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
    fun `a result that would exceed the parser limit is not written`() = runBlocking {
        // Just under the limit before, over it once the grid is rendered.
        val padding = "// " + "x".repeat(ConfigParser.MaxInputBytes - 200) + "\n"
        val text = "{\n$padding\"schemaVersion\": 2,\n\"home\": { \"grid\": {} }\n}\n"
        assertTrue(text.toByteArray().size <= ConfigParser.MaxInputBytes)
        putFile(text)
        val before = file.readBytes()

        val result = writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals("too-large", (result as WriteBackResult.Skipped).code)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `the report carries the self-write trigger and the hash of the written bytes`() = runBlocking {
        putFile(documentWithGrid)

        val result = writeBack().write(HomeGridLayouts.Phone, items) as WriteBackResult.Written

        val report = reportStore.read()!!
        assertEquals(ReloadTrigger.SelfWrite, report.trigger)
        assertEquals(true, report.success)
        assertEquals(listOf("home.grid"), report.appliedMutations)
        assertEquals(ConfigMigrations.currentSchemaVersion, report.schemaVersion)
        assertEquals(file.readBytes().sha256Hex(), report.configSha256)
        assertEquals(result.sha256, report.configSha256)
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
    fun `the last result is exposed for the UI`() = runBlocking {
        val writeBack = writeBack()
        assertNull(writeBack.lastResult.value)

        writeBack.write(HomeGridLayouts.Phone, items)

        assertEquals("no-config-file", (writeBack.lastResult.value as WriteBackResult.Skipped).code)
    }

    @Test
    fun `the written grid comes from the store's state, not from the items`() = runBlocking {
        // The items are what the UI holds; the file gets the effective
        // state, which is complete (every layout, every option), so what
        // provisioning pulls is a document it can push back unchanged.
        putFile(documentWithGrid)

        writeBack().write(HomeGridLayouts.Phone, items)

        assertEquals(gridState.gridLayouts, gridOf(file.readText()).layouts)
    }

    /** Records the file's text at the moment [replace] runs, to prove the write order. */
    private class RecordingHomeGridRepository : HomeGridRepository {
        val layouts = mutableMapOf<String, List<HomeGridItem>>()
        var fileTextAtReplace: (() -> String)? = null
        var observedFileText: String? = null

        override fun observe(layout: String): Flow<List<HomeGridItem>> = flowOf(layouts[layout] ?: emptyList())

        override suspend fun replace(layout: String, items: List<HomeGridItem>) {
            observedFileText = fileTextAtReplace?.invoke()
            layouts[layout] = items
        }

        override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) =
            throw NotImplementedError()

        override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) =
            throw NotImplementedError()

        override suspend fun delete(layout: String, id: String) = throw NotImplementedError()
    }
}

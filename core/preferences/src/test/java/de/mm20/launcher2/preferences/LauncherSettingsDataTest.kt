package de.mm20.launcher2.preferences

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class LauncherSettingsDataTest {

    private val serializer = LauncherSettingsDataSerializer(
        ApplicationProvider.getApplicationContext()
    )

    /**
     * #144: the Context default resolves at every width, the narrowest
     * included, so a test that builds a real LauncherDataStore needs no
     * hand-written settings file. The unqualified value lived in :app:app,
     * which no library module and none of their tests can see.
     */
    @Test
    @Config(qualifiers = "w320dp")
    fun `the default column count on a narrow screen is 4`() {
        assertEquals(4, serializer.defaultValue.gridColumnCount)
    }

    @Test
    @Config(qualifiers = "w400dp")
    fun `the default column count from 400dp is 5`() {
        assertEquals(5, serializer.defaultValue.gridColumnCount)
    }

    @Test
    @Config(qualifiers = "w480dp")
    fun `the default column count from 480dp is 6`() {
        assertEquals(6, serializer.defaultValue.gridColumnCount)
    }

    @Test
    fun `default value survives a write-read round trip`() = runTest {
        val default = serializer.defaultValue
        val out = ByteArrayOutputStream()
        serializer.writeTo(default, out)
        val decoded = serializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(default, decoded)
    }

    @Test
    fun `populated instance survives a write-read round trip`() = runTest {
        val data = LauncherSettingsData(
            gridColumnCount = 7,
            gridIconSize = 42,
            gesturesSwipeDown = GestureAction.QuickSettings,
            gesturesLongPress = GestureAction.Launch("app://de.mm20.launcher2"),
            searchBarStyle = SearchBarStyle.Solid,
            iconsPack = "com.example.iconpack",
        )
        val out = ByteArrayOutputStream()
        serializer.writeTo(data, out)
        val decoded = serializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(data, decoded)
    }

    /**
     * #108: files written before `SearchFilters.categories` became a getter
     * carry the list; they still read, the other settings survive, and the
     * category checks follow the booleans, not the stale list.
     */
    @Test
    fun `a file with the old categories list still reads and follows the booleans`() = runTest {
        val out = ByteArrayOutputStream()
        serializer.writeTo(LauncherSettingsData(gridColumnCount = 7), out)
        val old = out.toString(Charsets.UTF_8).replace(
            Regex(""""searchFilter":\{[^}]*\}"""),
            """"searchFilter":{"hiddenItems":false,"apps":true,"shortcuts":false,"contacts":true,""" +
                    """"categories":[false,false,false,false,false,false,false,false,false]}""",
        )
        assertTrue(old, old.contains("categories"))

        val decoded = serializer.readFrom(ByteArrayInputStream(old.toByteArray()))

        assertEquals(7, decoded.gridColumnCount)
        assertEquals(2, decoded.searchFilter.enabledCategories)
    }

    @Test
    fun `schema version is pinned`() = runTest {
        val out = ByteArrayOutputStream()
        serializer.writeTo(LauncherSettingsData(), out)
        val json = serializer.json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertEquals(6, json.getValue("schemaVersion").jsonPrimitive.content.toInt())
    }

    @Test
    fun `unknown keys are ignored on read`() = runTest {
        val json = """
            {
                "schemaVersion": 6,
                "gridColumnCount": 9,
                "someFutureForkKey": {"enabled": true}
            }
        """.trimIndent()
        val decoded = serializer.readFrom(ByteArrayInputStream(json.toByteArray()))
        assertEquals(9, decoded.gridColumnCount)
    }

    @Test
    fun `an enum value this build no longer knows is skipped, not fatal`() = runTest {
        // Removing a feature removes its filter-bar value (ADR 0008). A settings
        // file written before that still names it, and an unknown value inside a
        // list is not covered by ignoreUnknownKeys or coerceInputValues: without
        // the tolerant list serializer this throws and the corruption handler
        // replaces every setting the user has.
        val json = """
            {"schemaVersion":6,"searchFilterBarItems":["apps","weather","contacts"],"gridColumnCount":7}
        """.trimIndent()

        val decoded = serializer.readFrom(ByteArrayInputStream(json.toByteArray()))

        assertEquals(
            listOf(KeyboardFilterBarItem.Apps, KeyboardFilterBarItem.Contacts),
            decoded.searchFilterBarItems,
        )
        // the rest of the document survives
        assertEquals(7, decoded.gridColumnCount)
    }
}

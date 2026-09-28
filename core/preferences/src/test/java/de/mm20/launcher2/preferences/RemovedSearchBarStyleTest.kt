package de.mm20.launcher2.preferences

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The search bar's `Solid` style is gone (#229): under glass it drew the same
 * pill as `Transparent` with a theme text colour that the wallpaper-coloured
 * glass made illegible. A settings file that still says `Solid` must read as
 * the default style and keep every other setting it holds.
 *
 * A removed field is safe through `ignoreUnknownKeys`; a removed *value* of a
 * field that stays is not covered by it. `coerceInputValues` is what maps it
 * to the property's default. This pins that: were the serializer to throw
 * instead, the corruption handler would replace every setting with defaults.
 */
@RunWith(RobolectricTestRunner::class)
class RemovedSearchBarStyleTest {

    private val serializer = LauncherSettingsDataSerializer()

    /** A settings file as a device writes it today, its search bar style `Solid`. */
    private val stored: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/settings/before-dead-fields-removed.json")!!.readText()
    ).jsonObject.let { JsonObject(it + ("searchBarStyle" to JsonPrimitive("Solid"))) }

    private suspend fun decode(): LauncherSettingsData =
        serializer.readFrom(ByteArrayInputStream(stored.toString().toByteArray()))

    @Test
    fun `a stored Solid search bar style reads as the default style`() = runTest {
        assertEquals(SearchBarStyle.Transparent, decode().searchBarStyle)
    }

    @Test
    fun `every other setting in that file survives`() = runTest {
        val out = ByteArrayOutputStream()
        serializer.writeTo(decode(), out)
        val rewritten = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        // A control as well: it passes whatever the style decodes to, so long
        // as the file is read rather than replaced by defaults.
        for ((key, value) in rewritten) {
            if (key == "searchBarStyle") continue
            assertEquals("value of $key", stored[key], value)
        }
    }
}

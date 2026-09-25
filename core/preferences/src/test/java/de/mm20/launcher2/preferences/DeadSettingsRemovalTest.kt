package de.mm20.launcher2.preferences

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * #3 slice 1: settings that nothing reads or writes any more leave
 * [LauncherSettingsData]. The fixture is a settings file as the build before
 * the removal wrote it, every removed field off its default and a spread of
 * live fields off theirs too. Reading it must keep every live value, and the
 * only keys that disappear on the next write are the removed ones.
 */
@RunWith(RobolectricTestRunner::class)
class DeadSettingsRemovalTest {

    private val serializer = LauncherSettingsDataSerializer(ApplicationProvider.getApplicationContext())

    private val fixture: String = javaClass.getResource("/settings/before-dead-fields-removed.json")!!.readText()

    private val removed = setOf(
        "uiBaseLayout", "wallpaperBlur", "wallpaperBlurRadius", "mediaAllowList", "mediaDenyList",
        "homeScreenDock", "homeScreenDockRows", "contactSearchEnabled", "separateWorkProfile",
        "iconsPackThemed", "easterEgg", "surfacesOpacity", "surfacesBorderWidth",
    )

    private suspend fun rewritten(): JsonObject {
        val decoded = serializer.readFrom(ByteArrayInputStream(fixture.toByteArray()))
        val out = ByteArrayOutputStream()
        serializer.writeTo(decoded, out)
        return Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `an old settings file keeps every live value`() = runTest {
        val before = Json.parseToJsonElement(fixture).jsonObject
        val after = rewritten()

        for ((key, value) in after) {
            assertEquals("value of $key", before[key], value)
        }
    }

    @Test
    fun `exactly the dead settings leave the stored document`() = runTest {
        val before = Json.parseToJsonElement(fixture).jsonObject
        val after = rewritten()

        assertEquals(removed, before.keys - after.keys)
    }

    @Test
    fun `live values off their defaults are read from the old file`() = runTest {
        val decoded = serializer.readFrom(ByteArrayInputStream(fixture.toByteArray()))

        assertEquals(true, decoded.wallpaperDim)
        assertEquals(6, decoded.gridColumnCount)
        assertEquals(56, decoded.gridIconSize)
        assertEquals(true, decoded.searchBarFixed)
        assertEquals(IconShape.Squircle, decoded.iconsShape)
        assertEquals(emptySet<String>(), decoded.contactSearchProviders)
        assertEquals(GestureAction.Notifications, decoded.gesturesSwipeUp)
        assertEquals(5, decoded.homeGridColumns)
        assertEquals(12f, decoded.glassBlur)
    }
}

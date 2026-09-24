package de.mm20.launcher2.themes

import de.mm20.launcher2.themes.shapes.Shapes
import de.mm20.launcher2.themes.typography.Typography
import de.mm20.launcher2.themes.colors.DefaultLightColorScheme
import de.mm20.launcher2.themes.colors.DefaultDarkColorScheme
import de.mm20.launcher2.themes.colors.Colors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Characterization of theme import (#97): theme files exported before the
 * transparency scheme was removed carry a `transparencies` section. Such a
 * file must still import everything else - its name, author and shapes -
 * with the section ignored. Green before and after the removal: it guards
 * that removing the class breaks no existing theme file.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeBundleImportTest {

    private val shapes = Shapes(id = UUID(0L, 42L), name = "Rounded zone")

    // Non-default content, so a serializer that drops or corrupts a field
    // fails here (review on #102): the light and dark schemes swapped, and a
    // font map with one family less than the default.
    private val colors = Colors(
        id = UUID(0L, 43L),
        name = "Lagoon",
        lightColorScheme = DefaultDarkColorScheme,
        darkColorScheme = DefaultLightColorScheme,
    )
    private val typography = Typography(id = UUID(0L, 44L), name = "Grotesk", fonts = mapOf("brand" to null))

    private val bundle = ThemeBundle(
        name = "Zone theme",
        author = "andashi",
        colors = colors,
        typography = typography,
        shapes = shapes,
    )

    /** The bundle's own export, with a transparency section added as older exports had it. */
    private fun withTransparencies(json: String): String {
        val exported = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        return JsonObject(
            exported + ("transparencies" to buildJsonObject {
                put("id", "00000000-0000-0000-0000-000000000009")
                put("builtIn", false)
                put("name", "Semi-transparent copy")
                put("background", 0.4)
                put("surface", 0.65)
                put("elevatedSurface", 0.85)
            })
        ).toString()
    }

    @Test
    fun `a theme file with a transparency section imports everything else`() {
        val imported = ThemeBundle.fromJson(withTransparencies(bundle.toJson()))

        assertNotNull("the file must still import", imported)
        assertEquals("Zone theme", imported!!.name)
        assertEquals("andashi", imported.author)
        assertEquals(colors, imported.colors)
        assertEquals(typography, imported.typography)
        assertEquals(shapes, imported.shapes)
    }

    @Test
    fun `a theme file round-trips through export and import`() {
        assertEquals(bundle.copy(), ThemeBundle.fromJson(bundle.toJson()))
    }
}

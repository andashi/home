package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.config.GlassDefaults
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.RenderedBackdrop
import de.mm20.launcher2.glass.WidgetMute
import de.mm20.launcher2.themes.colors.BlackAndWhiteDarkColorScheme
import de.mm20.launcher2.themes.colors.BlackAndWhiteLightColorScheme
import de.mm20.launcher2.themes.colors.CorePalette
import de.mm20.launcher2.themes.colors.DefaultDarkColorScheme
import de.mm20.launcher2.themes.colors.DefaultLightColorScheme
import de.mm20.launcher2.themes.colors.HighContrastDarkColorScheme
import de.mm20.launcher2.themes.colors.HighContrastLightColorScheme
import de.mm20.launcher2.themes.colors.merge
import de.mm20.launcher2.ui.component.GlassSheetBackground
import de.mm20.launcher2.ui.launcher.search.common.grid.ItemPopupSurface
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import de.mm20.launcher2.ui.theme.LauncherColorSchemes
import de.mm20.launcher2.ui.theme.LocalLauncherColorSchemes
import de.mm20.launcher2.ui.theme.colorscheme.colorSchemeFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Text on an overlay reads whatever lies under it (#249). Without a managed
 * wallpaper there is no backdrop, and a glass surface was its tint alone:
 * under a menu, a sheet or a popup the launcher's content read through. Over
 * the launcher's real schemes 43.8 % of the pairs below met 4.5:1 (29.8 % in
 * the model with Material's baseline colours, measured before the build).
 * Measured on the device, a menu with a backdrop paints the wallpaper under
 * itself; only the null-backdrop case needs the floor.
 *
 * The pairs: what the glass hands its content (onSurface as content colour,
 * onSurfaceVariant) against what the surface paints - read from the surface,
 * not modelled - over 729 colours for whatever lies under it, in every
 * combination of the launcher's real schemes (three built-in colour sets over
 * four device palettes), theme, wallpaper side and contrast level. Blended in
 * gamma-encoded sRGB as Android blends, rounded to 8 bits as a pixel is, and
 * judged with the repository's one WCAG definition ([WidgetMute.contrast]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassOverlayFloorTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Device palettes: violet (Material baseline), a sky blue, a green, an amber. */
    private val palettes = listOf(
        CorePalette(0xFF6750A4.toInt(), 0xFF625B71.toInt(), 0xFF7D5260.toInt(), 0xFF605D62.toInt(), 0xFF605D66.toInt(), 0xFFB3261E.toInt()),
        CorePalette(0xFF3F7CC4.toInt(), 0xFF5F7389.toInt(), 0xFF76688F.toInt(), 0xFF5D6066.toInt(), 0xFF5A6068.toInt(), 0xFFB3261E.toInt()),
        CorePalette(0xFF3F7F4A.toInt(), 0xFF5E7260.toInt(), 0xFF3F7078.toInt(), 0xFF5D605C.toInt(), 0xFF5B615A.toInt(), 0xFFB3261E.toInt()),
        CorePalette(0xFF946F1D.toInt(), 0xFF7A6E57.toInt(), 0xFF5A7657.toInt(), 0xFF635F58.toInt(), 0xFF655F54.toInt(), 0xFFB3261E.toInt()),
    )

    /** The built-in colour sets, merged with the defaults as lightColorSchemeOf does. */
    private val colourSets = listOf(
        DefaultLightColorScheme to DefaultDarkColorScheme,
        HighContrastLightColorScheme to HighContrastDarkColorScheme,
        BlackAndWhiteLightColorScheme to BlackAndWhiteDarkColorScheme,
    )

    private data class Case(val schemes: LauncherColorSchemes, val darkContentOverWallpaper: Boolean, val contrast: Contrast) {
        override fun toString() = "${if (schemes.darkTheme) "dark" else "light"} theme, " +
            "${if (darkContentOverWallpaper) "light" else "dark"} wallpaper, $contrast"
    }

    private val cases = colourSets.flatMap { (light, dark) ->
        palettes.flatMap { palette ->
            listOf(false, true).flatMap { darkTheme ->
                val schemes = LauncherColorSchemes(
                    light = colorSchemeFrom(light.merge(DefaultLightColorScheme), palette),
                    dark = colorSchemeFrom(dark.merge(DefaultDarkColorScheme), palette),
                    darkTheme = darkTheme,
                )
                listOf(false, true).flatMap { side -> Contrast.entries.map { Case(schemes, side, it) } }
            }
        }
    }

    private fun style(contrast: Contrast) =
        GlassStyle.resolve(GlassInputs(GlassDefaults.Blur, GlassDefaults.Tint, GlassDefaults.Radius, contrast))

    private class Seen(var content: Color = Color.Unspecified, var variant: Color = Color.Unspecified)

    /** Composes one surface per case and reads what it painted and what it handed its content. */
    private fun survey(overlay: Boolean, backdrop: RenderedBackdrop<ImageBitmap>? = null): List<Pair<GlassSurfaceInfo, Seen>> {
        val seen = cases.map { Seen() }
        composeRule.setContent {
            Column {
                cases.forEachIndexed { i, case ->
                    CompositionLocalProvider(
                        LocalLauncherColorSchemes provides case.schemes,
                        LocalPreferDarkContentOverWallpaper provides case.darkContentOverWallpaper,
                        LocalGlassStyle provides style(case.contrast),
                        LocalGlassBackdrop provides backdrop,
                    ) {
                        MaterialTheme(colorScheme = case.schemes.theme) {
                            GlassSurface(Modifier.size(4.dp).testTag("case$i"), overlay = overlay) {
                                seen[i].content = LocalContentColor.current
                                seen[i].variant = MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return cases.indices.map { i -> infoOf(hasTestTag("case$i")) to seen[i] }
    }

    private fun infoOf(matcher: SemanticsMatcher): GlassSurfaceInfo =
        composeRule.onNode(matcher.and(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))).fetchSemanticsNode().config[GlassSurfaceKey]

    /** The tint alone, in the theme's surface: what glass is over the wallpaper. */
    private fun assertThemeTintNoFloor(surveyed: List<Pair<GlassSurfaceInfo, Seen>>) {
        surveyed.forEachIndexed { i, (info, _) ->
            val case = cases[i]
            assertNull("$case", info.layers.floor)
            assertEquals("$case", case.schemes.theme.surface.copy(alpha = style(case.contrast).tint), info.layers.tint)
        }
    }

    @Test
    fun `text on an overlay without a backdrop reads over anything`() {
        var met = 0
        var total = 0
        val failing = mutableListOf<String>()
        survey(overlay = true).forEachIndexed { i, (info, seen) ->
            var low = Double.MAX_VALUE
            for (under in Under) {
                val bg = composite(info, under)
                for (text in listOf(seen.content, seen.variant)) {
                    val r = WidgetMute.contrast(text.toArgb(), bg)
                    total++
                    if (r >= 4.5) met++
                    low = minOf(low, r)
                }
            }
            if (low < 4.5) failing += "${cases[i]}: %.2f".format(low)
        }
        assertEquals("pairs meeting 4.5:1: $met of $total; lowest per failing case: ${failing.take(6)}", total, met)
    }

    @Test
    fun `a card without a backdrop is its tint alone, as before`() {
        // Control: a card sits over the wallpaper the person picked; that is
        // wallpaperDim's territory, not this fix's.
        assertThemeTintNoFloor(survey(overlay = false))
    }

    @Test
    fun `an overlay with a backdrop keeps its glass`() {
        // Control: measured on the device, a menu over a managed wallpaper
        // paints the wallpaper under itself; the floor would hide it.
        assertThemeTintNoFloor(survey(overlay = true, backdrop = RenderedBackdrop(BackdropKey("0", 100, 100, 3), ImageBitmap(8, 8))))
    }

    @Test
    fun `the overlays ask for the floor`() {
        val schemes = cases.first().schemes
        composeRule.setContent {
            CompositionLocalProvider(LocalLauncherColorSchemes provides schemes) {
                MaterialTheme(colorScheme = schemes.theme) {
                    Column {
                        GlassMenuGroup(Modifier.testTag("menu")) {}
                        GlassSheetBackground(glass = true) { Box(Modifier.size(4.dp).testTag("sheet")) }
                        ItemPopupSurface(Modifier.testTag("popup")) { Box(Modifier.size(4.dp)) }
                        GlassSurface(Modifier.size(4.dp).testTag("card")) {}
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertNotNull("menu", infoOf(hasTestTag("menu")).layers.floor)
        assertNotNull("sheet", infoOf(hasAnyDescendant(hasTestTag("sheet"))).layers.floor)
        assertNotNull("popup", infoOf(hasTestTag("popup")).layers.floor)
        assertNull("card", infoOf(hasTestTag("card")).layers.floor)
    }

    private companion object {
        val Levels = (0..8).map { it * 255 / 8f }
        val Under = Levels.flatMap { r -> Levels.flatMap { g -> Levels.map { b -> Color(r / 255f, g / 255f, b / 255f) } } }

        /** What lies under the text, as a pixel: [under], the floor over it if any, the tint, the scrim. */
        fun composite(info: GlassSurfaceInfo, under: Color): Int {
            var c = info.layers.tint.compositeOver(info.layers.floor ?: under)
            if (info.scrimAlpha > 0f) c = Color.Black.copy(alpha = info.scrimAlpha).compositeOver(c)
            return c.toArgb()
        }
    }
}

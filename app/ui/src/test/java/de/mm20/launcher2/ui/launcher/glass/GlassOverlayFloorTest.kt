package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.RenderedBackdrop
import de.mm20.launcher2.config.GlassDefaults
import de.mm20.launcher2.themes.colors.BlackAndWhiteDarkColorScheme
import de.mm20.launcher2.themes.colors.BlackAndWhiteLightColorScheme
import de.mm20.launcher2.themes.colors.CorePalette
import de.mm20.launcher2.themes.colors.DefaultDarkColorScheme
import de.mm20.launcher2.themes.colors.DefaultLightColorScheme
import de.mm20.launcher2.themes.colors.HighContrastDarkColorScheme
import de.mm20.launcher2.themes.colors.HighContrastLightColorScheme
import de.mm20.launcher2.themes.colors.merge
import de.mm20.launcher2.ui.component.GlassSheetBackground
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Text on an overlay reads whatever lies under it (#249). Without a managed
 * wallpaper there is no backdrop, and a glass surface was its tint alone:
 * under a menu or a sheet the home grid read through, and 29.8 % of the pairs
 * below met 4.5:1 (Material baseline colours, measured before the build).
 * Measured on the device, a menu with a backdrop paints the wallpaper under
 * itself; only the null-backdrop case needs the floor.
 *
 * The pairs: what the glass hands its content (onSurface as content colour,
 * onSurfaceVariant) against what the surface paints - read from the surface,
 * not modelled - over 729 colours for whatever lies under it, in every
 * combination of the launcher's real schemes (three built-in colour sets over
 * four device palettes), theme, wallpaper side and contrast level. Blended in
 * gamma-encoded sRGB as Android blends; luminance gamma-decoded, as WCAG has it.
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

    private data class Case(val schemes: LauncherColorSchemes, val darkContentOverWallpaper: Boolean, val contrast: Contrast)

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
    private fun survey(floor: Boolean, backdrop: RenderedBackdrop<ImageBitmap>? = null): List<Pair<GlassLayers, Seen>> {
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
                            GlassSurface(Modifier.size(4.dp).testTag("case$i"), floor = floor) {
                                seen[i].content = LocalContentColor.current
                                seen[i].variant = MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return cases.indices.map { i -> layersOf("case$i") to seen[i] }
    }

    private fun layersOf(tag: String): GlassLayers {
        val node = composeRule.onNode(hasTestTag(tag).and(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))).fetchSemanticsNode()
        return node.config[GlassSurfaceKey].layers!!
    }

    @Test
    fun `text on an overlay without a backdrop reads over anything`() {
        val surveyed = survey(floor = true)
        var met = 0
        var total = 0
        val worst = mutableListOf<String>()
        surveyed.forEachIndexed { i, (layers, seen) ->
            var low = Double.MAX_VALUE
            for (under in Under) {
                val bg = composite(layers, under)
                for (text in listOf(seen.content, seen.variant)) {
                    val r = ratio(text.rgb(), bg)
                    total++
                    if (r >= 4.5) met++
                    low = min(low, r)
                }
            }
            if (low < 4.5) worst += "${cases[i].let { "${if (it.schemes.darkTheme) "dark" else "light"} theme, " +
                "${if (it.darkContentOverWallpaper) "light" else "dark"} wallpaper, ${it.contrast}" }}: %.2f".format(low)
        }
        assertEquals(
            "pairs meeting 4.5:1: $met of $total; lowest per failing case: ${worst.take(6)}",
            1.0, met.toDouble() / total, 0.0,
        )
    }

    @Test
    fun `a card without a backdrop is its tint alone, as before`() {
        // Control: a card sits over the wallpaper the person picked; that is
        // wallpaperDim's territory, not this fix's.
        survey(floor = false).forEachIndexed { i, (layers, _) ->
            val case = cases[i]
            assertNull("case $i", layers.floor)
            assertEquals("case $i", case.schemes.theme.surface.copy(alpha = style(case.contrast).tint), layers.tint)
        }
    }

    @Test
    fun `an overlay with a backdrop keeps its glass`() {
        // Control: measured on the device, a menu over a managed wallpaper
        // paints the wallpaper under itself; the floor would hide it.
        val backdrop = RenderedBackdrop(BackdropKey("0", 100, 100, 3), ImageBitmap(8, 8))
        survey(floor = true, backdrop = backdrop).forEachIndexed { i, (layers, _) ->
            val case = cases[i]
            assertNull("case $i", layers.floor)
            assertEquals("case $i", case.schemes.theme.surface.copy(alpha = style(case.contrast).tint), layers.tint)
        }
    }

    @Test
    fun `the overlays ask for the floor`() {
        val schemes = cases.first().schemes
        composeRule.setContent {
            CompositionLocalProvider(LocalLauncherColorSchemes provides schemes) {
                MaterialTheme(colorScheme = schemes.theme) {
                    Column {
                        GlassMenuGroup(Modifier.testTag("menu")) {}
                        GlassSheetBackground(glass = true) { androidx.compose.foundation.layout.Box(Modifier.size(4.dp).testTag("sheet")) }
                        GlassSurface(Modifier.size(4.dp).testTag("card")) {}
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertNotNull("menu", layersOf("menu").floor)
        val sheet = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(GlassSurfaceKey).and(androidx.compose.ui.test.hasAnyDescendant(hasTestTag("sheet")))
        ).fetchSemanticsNode().config[GlassSurfaceKey].layers!!
        assertNotNull("sheet", sheet.floor)
        assertNull("card", layersOf("card").floor)
    }

    private companion object {
        val Levels = (0..8).map { (it * 255 / 8.0).toInt() }
        val Under = Levels.flatMap { r -> Levels.flatMap { g -> Levels.map { b -> doubleArrayOf(r.toDouble(), g.toDouble(), b.toDouble()) } } }

        fun Color.rgb(): DoubleArray {
            val argb = toArgb()
            return doubleArrayOf(((argb shr 16) and 255).toDouble(), ((argb shr 8) and 255).toDouble(), (argb and 255).toDouble())
        }

        fun blend(top: DoubleArray, bottom: DoubleArray, alpha: Double) =
            DoubleArray(3) { top[it] * alpha + bottom[it] * (1 - alpha) }

        /** What lies under the text: [under], the floor over it if any, the tint, the scrim. */
        fun composite(layers: GlassLayers, under: DoubleArray): DoubleArray {
            var c = layers.floor?.rgb() ?: under
            c = blend(layers.tint.copy(alpha = 1f).rgb(), c, layers.tint.alpha.toDouble())
            if (layers.scrimAlpha > 0f) c = blend(doubleArrayOf(0.0, 0.0, 0.0), c, layers.scrimAlpha.toDouble())
            return c
        }

        fun luminance(c: DoubleArray): Double {
            fun ch(v: Double): Double {
                val s = v / 255
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
        }

        fun ratio(a: DoubleArray, b: DoubleArray): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}

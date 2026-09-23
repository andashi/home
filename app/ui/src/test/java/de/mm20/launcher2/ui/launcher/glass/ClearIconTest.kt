package de.mm20.launcher2.ui.launcher.glass

import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.icons.ClockLayer
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.LauncherIconLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TextLayer
import de.mm20.launcher2.icons.TintedClockLayer
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.icons.TransparentLayer
import de.mm20.launcher2.icons.VectorLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Which icons become white glyphs and which the desaturated original (#76). */
@RunWith(AndroidJUnit4::class)
class ClearIconTest {

    private val drawable = ColorDrawable(0xFFE53935.toInt())
    private fun icon(fg: LauncherIconLayer, bg: LauncherIconLayer = ColorLayer(0xFF1E88E5.toInt())) =
        StaticLauncherIcon(foregroundLayer = fg, backgroundLayer = bg)

    @Test
    fun `a monochrome layer is a glyph, without its color and without the app's background`() {
        val clear = ClearIcon.of(icon(TintedIconLayer(drawable, scale = 1.5f, color = 0xFF00FF00.toInt())))

        assertTrue(clear is ClearIcon.Glyph)
        val fg = clear.icon.foregroundLayer as TintedIconLayer
        assertEquals(0, fg.color)
        assertEquals(1.5f, fg.scale)
        assertEquals(TransparentLayer, clear.icon.backgroundLayer)
    }

    @Test
    fun `a pack entry is a glyph too`() {
        // IconPackManager and the themed placeholder both produce unforced tinted layers.
        val clear = ClearIcon.of(icon(TintedIconLayer(drawable, color = 0), ColorLayer(0)))
        assertTrue(clear is ClearIcon.Glyph)
    }

    /**
     * enforceThemed replaced the background before Clear sees the icon; an
     * adaptive icon's foreground is often a white logo that only reads on
     * its colored background, so the fallback must be the whole original
     * (review on #84).
     */
    @Test
    fun `a forced silhouette is the desaturated original, background and all`() {
        val original = icon(StaticIconLayer(drawable, scale = 1.5f), ColorLayer(0xFF1E88E5.toInt()))
        val forced = icon(TintedIconLayer(drawable, scale = 1.5f / 1.2f, forced = true, original = original), ColorLayer(0))

        val clear = ClearIcon.of(forced)

        assertTrue(clear is ClearIcon.Desaturated)
        assertEquals(original, clear.icon)
    }

    @Test
    fun `a forced silhouette without its original falls back to the foreground at the original scale`() {
        val clear = ClearIcon.of(icon(TintedIconLayer(drawable, scale = 1.5f / 1.2f, forced = true), ColorLayer(0)))

        assertTrue(clear is ClearIcon.Desaturated)
        val fg = clear.icon.foregroundLayer as StaticIconLayer
        assertEquals(drawable, fg.icon)
        assertEquals(1.5f, fg.scale, 1e-4f)
    }

    @Test
    fun `an icon without any themed layer is the desaturated original, background and all`() {
        val original = icon(StaticIconLayer(drawable, scale = 1.5f))
        val clear = ClearIcon.of(original)

        assertTrue(clear is ClearIcon.Desaturated)
        assertEquals(original, clear.icon)
    }

    @Test
    fun `clocks tick in white, tinted or not`() {
        val tinted = ClearIcon.of(icon(TintedClockLayer(emptyList(), scale = 1f, color = 0xFF00FF00.toInt())))
        val plain = ClearIcon.of(icon(ClockLayer(emptyList(), scale = 1f)))

        assertTrue(tinted is ClearIcon.Glyph)
        assertTrue(plain is ClearIcon.Glyph)
        assertEquals(0, (tinted.icon.foregroundLayer as TintedClockLayer).color)
        assertEquals(0, (plain.icon.foregroundLayer as TintedClockLayer).color)
    }

    @Test
    fun `vector and text placeholders are glyphs without color`() {
        val vector = ClearIcon.of(icon(VectorLayer(android.R.drawable.ic_menu_search, color = 0xFF00FF00.toInt())))
        val text = ClearIcon.of(icon(TextLayer("A", color = 0xFF00FF00.toInt())))

        assertEquals(0, (vector.icon.foregroundLayer as VectorLayer).color)
        assertEquals(0, (text.icon.foregroundLayer as TextLayer).color)
        assertTrue(vector is ClearIcon.Glyph && text is ClearIcon.Glyph)
    }

    /** "A colored icon never appears": every glyph carries no color of its own. */
    @Test
    fun `no glyph keeps a color`() {
        val colored = 0xFF00FF00.toInt()
        val inputs = listOf(
            TintedIconLayer(drawable, color = colored),
            TintedClockLayer(emptyList(), scale = 1f, color = colored),
            VectorLayer(android.R.drawable.ic_menu_search, color = colored),
            TextLayer("A", color = colored),
        )
        for (fg in inputs) {
            val clear = ClearIcon.of(icon(fg, ColorLayer(colored)))
            assertTrue("$fg", clear is ClearIcon.Glyph)
            assertEquals("$fg", TransparentLayer, clear.icon.backgroundLayer)
        }
    }
}

package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #78: a muted widget is grey, and exactly as legible as before. The
 * contrast bound is WCAG's 4.5:1 for text (the issue's "bound the glass
 * scrim uses" referenced nothing: the scrim is a fixed alpha per level).
 */
class WidgetMuteTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    /** A grid over the sRGB cube: every channel in 18 steps. */
    private val colours = (0..255 step 15).flatMap { r -> (0..255 step 15).flatMap { g -> (0..255 step 15).map { b -> rgb(r, g, b) } } }

    @Test
    fun `a muted colour is grey, and alpha is kept`() {
        for (c in colours) {
            val g = WidgetMute.grey(c)
            val r = g shr 16 and 0xff
            assertTrue("%08x".format(c), r == (g shr 8 and 0xff) && r == (g and 0xff))
        }
        assertEquals(0x80, WidgetMute.grey(0x80ff0000.toInt()) ushr 24)
    }

    /** The point of the shader: each pixel keeps its own luminance, up to 8-bit rounding. */
    @Test
    fun `a muted colour keeps its own luminance`() {
        for (c in colours) {
            val before = WidgetMute.luminance(c)
            val after = WidgetMute.luminance(WidgetMute.grey(c))
            assertEquals("%08x".format(c), before, after, 0.005)
        }
    }

    /**
     * The method keeps each pixel's luminance exactly; what moves a ratio is
     * the grey's 8-bit rounding. Half an 8-bit step, relative to (L + 0.05),
     * is at most 0.79% for one colour (measured over the cube in steps of 5),
     * so a pair of them moves by at most [PairBound] = 1.6%. A pair that met
     * 4.5:1 therefore still does unless it sat within 1.6% of it (4.55:1 can
     * land on 4.50:1): 0.5% of such pairs, by a hair. A gamma-space matrix
     * drops 6.1% below the bound, by up to two thirds.
     */
    @Test
    fun `every ratio moves by at most the rounding bound, so 4,5 to 1 holds outside it`() {
        var pairs = 0
        val worst = colours.flatMap { a -> colours.filterIndexed { i, _ -> i % 11 == 0 }.map { b -> a to b } }
            .filter { (a, b) -> WidgetMute.contrast(a, b) >= 4.5 }
            .onEach { pairs++ }
            .maxOf { (a, b) ->
                val before = WidgetMute.contrast(a, b)
                val after = WidgetMute.contrast(WidgetMute.grey(a), WidgetMute.grey(b))
                assertTrue("%08x on %08x: %.3f -> %.3f".format(a, b, before, after), after >= 4.5 || before < 4.5 / (1 - PairBound))
                kotlin.math.abs(after - before) / before
            }
        assertTrue("pairs checked: $pairs", pairs > 10_000)
        assertTrue("worst relative change ${"%.4f".format(worst)}", worst < PairBound)
    }

    private companion object {
        /** Two colours' 8-bit rounding of their grey, relative to the ratio. */
        const val PairBound = 0.016
    }

    /** The case that broke the matrix: saturated red on near-black stays readable. */
    @Test
    fun `red on near-black keeps its contrast`() {
        val red = rgb(255, 0, 0)
        val nearBlack = rgb(17, 17, 17)
        val before = WidgetMute.contrast(red, nearBlack)
        assertEquals(before, WidgetMute.contrast(WidgetMute.grey(red), WidgetMute.grey(nearBlack)), before * PairBound)
    }

    @Test
    fun `the shader samples its content and converts through linear sRGB`() {
        for (part in listOf("uniform shader content", "content.eval", "toLinearSrgb", "fromLinearSrgb", "0.2126, 0.7152, 0.0722")) {
            assertTrue(part, part in WidgetMute.Shader)
        }
    }
}

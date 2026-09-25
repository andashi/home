package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Segments of one card (#91): a lazily laid out result list is drawn as row
 * slices, and where two slices meet there must be no corner and no rim, or
 * the card reads as a stack of cards. Drawn into a bitmap with the surface's
 * own outline and rim, as the rim test does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSegmentTest {

    private val w = 200
    private val h = 80

    private fun draw(block: DrawScope.() -> Unit): PixelMap {
        val image = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.Black)
            block()
        }
        return image.toPixelMap()
    }

    private fun PixelMap.brightest(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var m = 0f
        for (y in y0..y1) for (x in x0..x1) m = maxOf(m, this[x, y].red)
        return m
    }

    /** The outline filled white: a rounded corner leaves the corner pixel black. */
    private fun filled(openEdges: Set<GlassEdge>) = draw {
        drawOutline(
            glassOutline(radiusDp = 28f, pill = false, shape = null, openEdges = openEdges)
                .createOutline(size, layoutDirection, this),
            Color.White,
        )
    }

    private fun rim(openEdges: Set<GlassEdge>) = draw {
        drawGlassRim(glassRimStroke(glassOutline(28f, pill = false, shape = null, openEdges = openEdges), openEdges, size, layoutDirection, this))
    }

    @Test
    fun `a closed surface has rounded corners at the top and the bottom`() {
        val pixels = filled(emptySet())
        assertTrue(pixels[0, 0].red < 0.1f)
        assertTrue(pixels[0, h - 1].red < 0.1f)
    }

    @Test
    fun `an open bottom edge is square, the top stays rounded`() {
        val pixels = filled(setOf(GlassEdge.Bottom))
        assertTrue("bottom-left filled", pixels[0, h - 1].red > 0.9f)
        assertTrue("bottom-right filled", pixels[w - 1, h - 1].red > 0.9f)
        assertTrue("top-left still rounded", pixels[0, 0].red < 0.1f)
    }

    @Test
    fun `an open top edge is square`() {
        val pixels = filled(setOf(GlassEdge.Top))
        assertTrue(pixels[0, 0].red > 0.9f)
        assertTrue(pixels[0, h - 1].red < 0.1f)
    }

    @Test
    fun `no rim runs along an open edge, the sides reach it`() {
        val pixels = rim(setOf(GlassEdge.Bottom))
        val seam = pixels.brightest(40, h - 2, w - 40, h - 1)
        val side = pixels.brightest(0, h - 4, 2, h - 1)
        assertTrue("no rim along the seam: $seam", seam < 0.02f)
        assertTrue("the side rim runs to the seam: $side", side > 0.05f)
    }

    @Test
    fun `a closed edge keeps its rim`() {
        val pixels = rim(emptySet())
        assertTrue(pixels.brightest(40, h - 2, w - 40, h - 1) > 0.02f)
    }
}

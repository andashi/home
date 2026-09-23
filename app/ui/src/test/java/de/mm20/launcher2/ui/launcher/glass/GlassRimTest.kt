package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.GlassLook
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The rim as pixels (#82): on a wide pill the ends must stay faint. A
 * diagonal linear gradient lit the whole left cap, because on a wide shape
 * the side midpoints sit next to the gradient's end stops.
 *
 * Drawn straight into a bitmap with the surface's own brush, not captured
 * from a window: a window capture came back near-white in CI and correct
 * locally, and what is under test is the brush, not the capture.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassRimTest {

    /** The rim over black, one pixel per dp, as the brightest red value in a rectangle. */
    private class Rim(private val pixels: PixelMap) {
        fun max(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            var m = 0f
            for (y in y0.coerceIn(0, pixels.height - 1)..y1.coerceIn(0, pixels.height - 1))
                for (x in x0.coerceIn(0, pixels.width - 1)..x1.coerceIn(0, pixels.width - 1)) m = maxOf(m, pixels[x, y].red)
            return m
        }
    }

    private fun rim(width: Int, height: Int, shape: Shape): Rim {
        val image = ImageBitmap(width, height)
        val size = Size(width.toFloat(), height.toFloat())
        val density = Density(1f)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), size) {
            drawRect(Color.Black)
            drawOutline(
                shape.createOutline(size, LayoutDirection.Ltr, density),
                RimBrush,
                style = Stroke(GlassLook.RimWidthDp.dp.toPx()),
            )
        }
        val rim = Rim(image.toPixelMap())
        assertTrue("a rim is drawn at all", rim.max(0, 0, width - 1, height - 1) > 0.1f)
        return rim
    }

    @Test
    fun `on a wide pill the ends are faint and the top-left is lit`() {
        val rim = rim(400, 60, RoundedCornerShape(percent = 50))
        val leftEnd = rim.max(0, 20, 3, 40)
        val rightEnd = rim.max(396, 20, 399, 40)
        val topLeft = rim.max(30, 0, 80, 3)
        val values = "left end $leftEnd, right end $rightEnd, top-left $topLeft"
        assertTrue(values, leftEnd < topLeft)
        assertTrue(values, rightEnd < topLeft)
    }

    @Test
    fun `on a square card the top-left corner is brighter than the bottom-right`() {
        val rim = rim(200, 200, RoundedCornerShape(28.dp))
        val topLeft = rim.max(0, 0, 30, 30)
        val bottomRight = rim.max(169, 169, 199, 199)
        assertTrue("top-left $topLeft vs bottom-right $bottomRight", topLeft > bottomRight)
    }
}

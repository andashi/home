package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rim as pixels (#82): on a wide pill the ends must stay faint. A
 * diagonal linear gradient lit the whole left cap, because on a wide shape
 * the side midpoints sit next to the gradient's end stops.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Wide enough for the 400 dp pill (Robolectric's default window is 320 dp),
// mdpi so a dp is a pixel.
@Config(qualifiers = "w480dp-h900dp-mdpi")
class GlassRimTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The rim over black, as the brightest red value in a rectangle of the
     * captured image. Regions rather than single pixels: the stroke is
     * anti-aliased, and which exact pixel carries it is not the point.
     */
    private class Rim(private val pixels: androidx.compose.ui.graphics.PixelMap) {
        val width = pixels.width
        val height = pixels.height
        fun max(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            var m = 0f
            for (y in y0.coerceIn(0, height - 1)..y1.coerceIn(0, height - 1))
                for (x in x0.coerceIn(0, width - 1)..x1.coerceIn(0, width - 1)) m = maxOf(m, pixels[x, y].red)
            return m
        }
    }

    private fun rim(width: Int, height: Int, pill: Boolean): Rim {
        val shape = if (pill) RoundedCornerShape(percent = 50) else RoundedCornerShape(28.dp)
        composeRule.setContent {
            Box(
                Modifier
                    .size(width.dp, height.dp)
                    .testTag("rim")
                    .background(Color.Black)
                    .glassRim(shape)
            )
        }
        val rim = Rim(composeRule.onNodeWithTag("rim").captureToImage().toPixelMap())
        assertEquals("captured size", width to height, rim.width to rim.height)
        assertTrue("a rim is drawn at all", rim.max(0, 0, width - 1, height - 1) > 0.1f)
        return rim
    }

    @Test
    fun `on a wide pill the ends are faint and the top-left is lit`() {
        val rim = rim(400, 60, pill = true)
        // The ends: the outermost columns around the vertical middle.
        val leftEnd = rim.max(0, 20, 3, 40)
        val rightEnd = rim.max(396, 20, 399, 40)
        // The top-left of a pill: the top edge just right of the left cap.
        val topLeft = rim.max(30, 0, 80, 3)
        val values = "left end $leftEnd, right end $rightEnd, top-left $topLeft"
        assertTrue(values, leftEnd < topLeft)
        assertTrue(values, rightEnd < topLeft)
    }

    @Test
    fun `on a square card the top-left corner is brighter than the bottom-right`() {
        val rim = rim(200, 200, pill = false)
        val topLeft = rim.max(0, 0, 30, 30)
        val bottomRight = rim.max(169, 169, 199, 199)
        assertTrue("top-left $topLeft vs bottom-right $bottomRight", topLeft > bottomRight)
    }
}

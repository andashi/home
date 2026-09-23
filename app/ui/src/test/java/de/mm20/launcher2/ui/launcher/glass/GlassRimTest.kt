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
@Config(qualifiers = "mdpi")
class GlassRimTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Brightness of the rim over black at (x, y), 0..1. */
    private fun rim(width: Int, height: Int, pill: Boolean): (Int, Int) -> Float {
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
        val pixels = composeRule.onNodeWithTag("rim").captureToImage().toPixelMap()
        return { x, y -> pixels[x, y].red }
    }

    @Test
    fun `on a wide pill the ends are faint and the top-left is lit`() {
        val at = rim(400, 60, pill = true)
        val leftEnd = at(0, 30)
        val rightEnd = at(399, 30)
        // The top edge a little right of where the left cap begins: the
        // top-left of a pill.
        val topLeft = at(40, 0)
        assertTrue("left end $leftEnd vs top-left $topLeft", leftEnd < topLeft)
        assertTrue("right end $rightEnd vs top-left $topLeft", rightEnd < topLeft)
    }

    @Test
    fun `on a square card the top-left corner is brighter than the bottom-right`() {
        val at = rim(200, 200, pill = false)
        // Just inside the rounded corners, on the rim.
        val topLeft = at(9, 9)
        val bottomRight = at(190, 190)
        assertTrue("top-left $topLeft vs bottom-right $bottomRight", topLeft > bottomRight)
    }
}

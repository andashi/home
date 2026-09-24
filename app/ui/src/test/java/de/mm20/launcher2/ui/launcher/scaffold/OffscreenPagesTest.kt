package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
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
 * The pages the scaffold keeps alive out of the viewport (#122). On unfold
 * the window changes size and everything is laid out and recorded anew; a
 * page nobody can see is neither recorded nor laid out at the new size
 * while the display switches. The hidden search page's relayout - its app
 * grid gaining columns - was most of the work before the first frame on
 * the inner display (simpleperf, #122).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class OffscreenPagesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a page kept offscreen is composed and laid out but never drawn`() {
        var compositions = 0
        var measured = IntSize.Zero
        var draws = 0
        composeRule.setContent {
            Box(Modifier.size(100.dp)) {
                OffscreenPages(offsetX = { 1000 }) {
                    SideEffect { compositions++ }
                    Box(Modifier.fillMaxSize().onSizeChanged { measured = it }.drawBehind { draws++ })
                }
            }
        }
        composeRule.onRoot().captureToImage()
        composeRule.runOnIdle {
            assertTrue("composed", compositions > 0)
            assertTrue("laid out", measured.width > 0)
            assertEquals("drawn", 0, draws)
        }
    }

    @Test
    fun `a window size change lays the page out anew without drawing it`() {
        var side by mutableStateOf(100.dp)
        var measured = IntSize.Zero
        var draws = 0
        composeRule.setContent {
            Box(Modifier.size(side)) {
                OffscreenPages(offsetX = { 1000 }) {
                    Box(Modifier.fillMaxSize().onSizeChanged { measured = it }.drawBehind { draws++ })
                }
            }
        }
        composeRule.onRoot().captureToImage()
        side = 200.dp
        composeRule.onRoot().captureToImage()
        val expected = with(composeRule.density) { 200.dp.roundToPx() }
        composeRule.runOnIdle {
            assertEquals("laid out at the new size", expected, measured.width)
            assertEquals("drawn", 0, draws)
        }
    }

    /** Control: the same page in the viewport is drawn, so a zero above is not a test that sees nothing. */
    @Test
    fun `the same page in the viewport is drawn`() {
        var draws = 0
        composeRule.setContent {
            Box(Modifier.size(100.dp)) {
                Box(Modifier.fillMaxSize().drawBehind { draws++ })
            }
        }
        composeRule.onRoot().captureToImage()
        composeRule.runOnIdle { assertTrue("drawn", draws > 0) }
    }
}

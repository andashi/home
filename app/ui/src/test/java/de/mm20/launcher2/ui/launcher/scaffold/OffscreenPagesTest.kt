package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
        composeRule.mainClock.advanceTimeBy(OffscreenPagesSettleMillis)
        composeRule.onRoot().captureToImage()
        val expected = with(composeRule.density) { 200.dp.roundToPx() }
        composeRule.runOnIdle {
            assertEquals("laid out at the new size", expected, measured.width)
            assertEquals("drawn", 0, draws)
        }
    }

    /**
     * The frame in which the window changes size - the first frame after an
     * unfold - lays out only what can be seen. A page nobody can see keeps
     * its size for that frame and takes the new one in a later frame (#122).
     */
    @Test
    fun `a window size change reaches the page after the frame of the change`() {
        var side by mutableStateOf(100.dp)
        var window = IntSize.Zero
        var measured = IntSize.Zero
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(Modifier.size(side).onSizeChanged { window = it }) {
                OffscreenPages(offsetX = { 1000 }) {
                    Box(Modifier.fillMaxSize().onSizeChanged { measured = it })
                }
            }
        }
        val before = with(composeRule.density) { 100.dp.roundToPx() }
        val after = with(composeRule.density) { 200.dp.roundToPx() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { assertEquals("first layout at its size", before, measured.width) }

        side = 200.dp
        // Frame by frame up to the one in which the window has the new size.
        var frames = 0
        while (composeRule.runOnIdle { window.width } != after) {
            check(++frames < 10) { "the window never changed size" }
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.runOnIdle { assertEquals("the frame of the change", before, measured.width) }

        // The display switch: the inner display stays dark until every
        // window has drawn, and a relayout here is another frame it waits
        // for (#122: two frames later was still inside it, 3-6 launcher
        // frames before screen-on instead of 1-2).
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.runOnIdle { assertEquals("during the display switch", before, measured.width) }

        composeRule.mainClock.advanceTimeBy(OffscreenPagesSettleMillis)
        composeRule.runOnIdle { assertEquals("after it", after, measured.width) }
    }

    /**
     * Folding makes the window narrower while a page keeps its wider size
     * until it settles. It must stay out of the viewport meanwhile - not
     * drawn, but still hit-testable - also right to left, where a relative
     * offset moves the box the other way (review on #128).
     */
    @Test
    fun `a page wider than the window after a fold stays out of the viewport, right to left`() =
        assertOutOfViewportAfterFold(LayoutDirection.Rtl)

    /** Control: left to right the box sits right of the window either way. */
    @Test
    fun `a page wider than the window after a fold stays out of the viewport, left to right`() =
        assertOutOfViewportAfterFold(LayoutDirection.Ltr)

    private fun assertOutOfViewportAfterFold(direction: LayoutDirection) {
        var side by mutableStateOf(200.dp)
        var window = IntSize.Zero
        var left = 0f
        var width = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            // The window's width as state, as the scaffold passes state.size.
            val widthPx = with(LocalDensity.current) { side.roundToPx() }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(side).onSizeChanged { window = it }) {
                    OffscreenPages(offsetX = { widthPx }) {
                        Box(
                            Modifier.fillMaxSize().onGloballyPositioned {
                                // Not boundsInRoot: that is clipped to the root.
                                left = it.positionInRoot().x
                                width = it.size.width
                            }
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        side = 100.dp
        val narrow = with(composeRule.density) { 100.dp.roundToPx() }
        var frames = 0
        while (composeRule.runOnIdle { window.width } != narrow) {
            check(++frames < 10) { "the window never changed size" }
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.runOnIdle {
            assertTrue("the page keeps its size ($width vs ${window.width})", width > window.width)
            val right = left + width
            assertTrue("page $left..$right overlaps the window 0..${window.width}", left >= window.width || right <= 0f)
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

package de.mm20.launcher2.ui.launcher.glass

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A popup's glass on the real renderer, with a backdrop or a style that
 * changes while the popup is open. A popup is its own composition; a change
 * - the first render, a wallpaper change, a fold, a new tint from the config -
 * has to reach the glass inside it, not only the host's.
 *
 * Without a backdrop the popup is an overlay with an opaque floor (#249); the
 * tests before that used the host showing through as their "nothing drawn
 * yet" signal, and one of them asserted it - the defect, pinned as a control.
 */
@RunWith(AndroidJUnit4::class)
class GlassPopupSequenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val source = object : GlassBackdropSource {
        override val image = MutableStateFlow<BackdropImage?>(null)
        override suspend fun refresh() = Unit
    }

    private val glass = MutableStateFlow(GlassInputs(24f, 0f, 28f, Contrast.Medium))

    private val controller = GlassBackdropController(
        source,
        glass,
        CoroutineScope(Dispatchers.Main.immediate),
    ) { _, key ->
        val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
        // Red to blue top to bottom, no green: red + blue = 255 in every pixel.
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                val red = (255f * y / (h - 1)).toInt()
                for (x in 0 until w) setPixel(x, y, android.graphics.Color.rgb(red, 0, 255 - red))
            }
        }.asImageBitmap()
    }

    /** The floor the popup paints without a backdrop: the theme's surface, as no zone schemes are provided. */
    private var floor = 0

    private fun setPopupContent() {
        composeRule.setContent {
            MaterialTheme {
                floor = MaterialTheme.colorScheme.surface.toArgb()
                ProvideGlassBackdrop(controller) {
                    // Pure green: neither backdrop nor rim or specular.
                    Box(Modifier.fillMaxSize().background(Color(0xFF00FF00))) {
                        val offset = with(LocalDensity.current) { IntOffset(200.dp.roundToPx(), 420.dp.roundToPx()) }
                        Popup(offset = offset) {
                            GlassMenuGroup(Modifier.size(120.dp).testTag("popup")) {}
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The screen at the popup's centre, as the user sees it. */
    private fun centre(): Int {
        val node = composeRule.onNodeWithTag("popup", useUnmergedTree = true).fetchSemanticsNode()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val x = (node.positionOnScreen.x + node.size.width / 2f).toInt()
        val y = (node.positionOnScreen.y + node.size.height / 2f).toInt()
        return screen.getPixel(x, y)
    }

    /**
     * The centre once [done] holds, or after a second: the render and the
     * redraw are asynchronous.
     */
    private fun centreOnce(done: (Int) -> Boolean): Int {
        var pixel = centre()
        repeat(10) {
            if (done(pixel)) return pixel
            Thread.sleep(100)
            pixel = centre()
        }
        return pixel
    }

    private fun Int.describe() = "#" + Integer.toHexString(this)
    private fun Int.isFloor() = listOf(
        android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue,
    ).all { ch -> kotlin.math.abs(ch(this) - ch(floor)) <= 3 }
    private fun Int.isBackdrop() = android.graphics.Color.green(this) < 16 &&
        android.graphics.Color.red(this) + android.graphics.Color.blue(this) > 200

    /**
     * Without a backdrop a popup's glass paints its floor on the real
     * renderer, so the host does not read through (#249). Until then this was
     * a control asserting the opposite - host green at the centre - and it
     * pinned the defect as the expected look. The floor is what the unit
     * tests only read from the surface's semantics; this is the pixel.
     */
    @Test
    fun aPopupGlassWithoutABackdropPaintsItsFloorOverTheHost() {
        setPopupContent()

        // Waited for: the popup's window draws after the host's idle, and a
        // first read can still see the host - which the old control, asserting
        // host green, could not tell from the defect.
        val pixel = centreOnce { it.isFloor() }
        assertTrue("popup centre without a backdrop is ${pixel.describe()}, not the floor ${floor.describe()}", pixel.isFloor())
    }

    /**
     * The backdrop arrives after the popup's first frame and must reach the
     * popup's window on screen. With LocalGlassBackdrop a static local the
     * host recomposed but the glass inside the popup kept drawing nothing:
     * the centre stayed host green.
     */
    @Test
    fun aBackdropThatArrivesAfterThePopupsFirstFrameReachesTheScreen() {
        setPopupContent()
        val before = centreOnce { it.isFloor() }
        assertTrue("before the backdrop: ${before.describe()}, not the floor", before.isFloor())

        composeRule.runOnIdle { source.image.value = BackdropImage("/w/zone.jpg", "sha1") }
        composeRule.waitForIdle()

        val after = centreOnce { it.isBackdrop() }
        assertTrue("after the backdrop arrived: ${after.describe()}, not the backdrop", after.isBackdrop())
    }

    /**
     * The glass style changes while the popup is open - high contrast from
     * the config - and must reach the popup's glass: its scrim darkens the
     * floor. With LocalGlassStyle a static local the popup kept its old style.
     * (A tint change no longer shows here: over a floor of the same colour it
     * is invisible, so the scrim carries the signal.)
     */
    @Test
    fun aStyleChangeWhileThePopupIsOpenReachesItsGlass() {
        setPopupContent()
        val before = centreOnce { it.isFloor() }
        assertTrue("before the style change: ${before.describe()}, not the floor", before.isFloor())

        composeRule.runOnIdle { glass.value = glass.value.copy(contrast = Contrast.High) }
        composeRule.waitForIdle()

        val after = centreOnce { !it.isFloor() }
        assertTrue(
            "after the style changed: ${after.describe()}, not darker than the floor ${floor.describe()}",
            android.graphics.Color.green(after) < android.graphics.Color.green(floor) - 10,
        )
    }
}

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

    private fun setPopupContent() {
        composeRule.setContent {
            MaterialTheme {
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

    private fun Int.describe() = "#" + Integer.toHexString(this)
    private fun Int.isHost() = android.graphics.Color.green(this) > 200 &&
        android.graphics.Color.red(this) < 40 && android.graphics.Color.blue(this) < 40
    private fun Int.isBackdrop() = android.graphics.Color.green(this) < 16 &&
        android.graphics.Color.red(this) + android.graphics.Color.blue(this) > 200

    /**
     * Control: a popup glass drawn without a backdrop shows the host through
     * it (tint 0, no background, a translucent window). Passes with and
     * without the fix; it pins what "before" looks like in the test below.
     */
    @Test
    fun aPopupGlassDrawnWithoutABackdropShowsTheHostThrough() {
        setPopupContent()

        val pixel = centre()
        assertTrue("popup centre without a backdrop is ${pixel.describe()}, not host green", pixel.isHost())
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
        val before = centre()
        assertTrue("before the backdrop: ${before.describe()}, not host green", before.isHost())

        composeRule.runOnIdle { source.image.value = BackdropImage("/w/zone.jpg", "sha1") }
        composeRule.waitForIdle()

        // The render and the redraw are asynchronous; give them a second.
        var after = centre()
        repeat(10) {
            if (after.isBackdrop()) return@repeat
            Thread.sleep(100)
            after = centre()
        }
        assertTrue("after the backdrop arrived: ${after.describe()}, not the backdrop", after.isBackdrop())
    }

    /**
     * The glass style changes while the popup is open - a new tint from the
     * config - and must reach the popup's glass: at full tint the surface
     * colour covers the host. With LocalGlassStyle a static local the popup
     * kept tint 0 and the host green showed through.
     */
    @Test
    fun aStyleChangeWhileThePopupIsOpenReachesItsGlass() {
        setPopupContent()
        val before = centre()
        assertTrue("before the tint: ${before.describe()}, not host green", before.isHost())

        composeRule.runOnIdle { glass.value = glass.value.copy(tint = 1f) }
        composeRule.waitForIdle()

        var after = centre()
        repeat(10) {
            if (!after.isHost()) return@repeat
            Thread.sleep(100)
            after = centre()
        }
        assertTrue("after the tint changed: still host green ${after.describe()}", !after.isHost())
    }
}

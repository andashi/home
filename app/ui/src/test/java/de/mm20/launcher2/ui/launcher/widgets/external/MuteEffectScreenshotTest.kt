package de.mm20.launcher2.ui.launcher.widgets.external

import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.glass.WidgetMute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #78: the widget host's own mute effect, rendered. A red widget stays red
 * without it and becomes the grey of red's own luminance with it - the pixel
 * is compared with [WidgetMute.grey], the reference the core tests check over
 * the colour cube, so the AGSL and the Kotlin cannot drift apart unnoticed.
 * The device step in l4-config measures the same on a hosted AppWidget.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MuteEffectScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val red = Color.rgb(0xE5, 0x39, 0x35)

    private fun widget(muted: Boolean) = @androidx.compose.runtime.Composable {
        AndroidView(
            modifier = Modifier.size(width = 120.dp, height = 56.dp).padding(4.dp).testTag(if (muted) "muted" else "plain"),
            factory = { context ->
                TextView(context).apply {
                    setBackgroundColor(red)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    text = "widget"
                    setRenderEffect(if (muted) muteEffect else null)
                }
            },
        )
    }

    private fun corner(tag: String): Int {
        val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[3, 3].toArgb()
    }

    @Test
    fun `a muted widget is the grey of its own luminance, an unmuted one keeps its colour`() {
        composeRule.setContent {
            Row {
                widget(muted = false)()
                widget(muted = true)()
            }
        }

        assertEquals("%08x".format(red), "%08x".format(corner("plain")))
        assertEquals("%08x".format(WidgetMute.grey(red)), "%08x".format(corner("muted")))
        composeRule.onRoot().captureRoboImage()
    }
}

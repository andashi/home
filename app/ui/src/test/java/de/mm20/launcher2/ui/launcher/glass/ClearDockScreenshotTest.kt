package de.mm20.launcher2.ui.launcher.glass

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.ui.component.ShapedLauncherIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 goldens of the Clear look (#76): a dock of four, three white glyphs and
 * one fallback (a colored original, drawn desaturated), on the phone and on
 * the fold's inner width. The Mauritius goldens of the whole screen are #77.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearDockScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun glyph(res: Int) = StaticLauncherIcon(
        TintedIconLayer(ContextCompat.getDrawable(context, res)!!, scale = 0.6f),
        ColorLayer(0),
    )

    /** A loud original: red to yellow, the kind of icon that must never show in color. */
    private val fallback = StaticLauncherIcon(
        StaticIconLayer(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt()))),
        ColorLayer(0xFF1E88E5.toInt()),
    )

    private val icons = listOf(
        glyph(android.R.drawable.ic_menu_call),
        glyph(android.R.drawable.ic_dialog_email),
        fallback,
        glyph(android.R.drawable.ic_menu_search),
    )

    private fun dock() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClearIcons provides true) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0xFF5B8DD6), Color(0xFF3E4A3A)))),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        GlassSurface(Modifier.fillMaxWidth().height(96.dp).padding(8.dp)) {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for (icon in icons) ShapedLauncherIcon(size = 56.dp, icon = { icon })
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w412dp-h320dp-420dpi")
    fun phone() {
        dock()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    @Config(qualifiers = "w840dp-h320dp-420dpi")
    fun foldInner() {
        dock()
        composeRule.onRoot().captureRoboImage()
    }
}

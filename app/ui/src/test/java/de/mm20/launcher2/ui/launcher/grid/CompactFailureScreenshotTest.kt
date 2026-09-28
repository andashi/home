package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 goldens of a failed one-row widget cell (#245): the compact form on a
 * glass card, in the light theme, over a black wallpaper - the case the
 * device showed the icon barely visible in, because the card handed it the
 * theme's dark onSurface - and over a bright one, where a fixed white would
 * fail the other way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class CompactFailureScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactFailureOnDarkWallpaper() = golden(Color.Black, preferDarkContent = false)

    @Test
    fun compactFailureOnBrightWallpaper() = golden(Color(0xFFF2F0E8), preferDarkContent = true)

    private fun golden(wallpaper: Color, preferDarkContent: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPreferDarkContentOverWallpaper provides preferDarkContent) {
                    Box(Modifier.size(200.dp, 120.dp).background(wallpaper), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(84.dp, 72.dp)) {
                            AppWidgetCell(
                                item = gridItem("camera", 0, 0, 1, 1),
                                onRemove = {},
                                onReplace = { _, _ -> },
                                onAllow = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}

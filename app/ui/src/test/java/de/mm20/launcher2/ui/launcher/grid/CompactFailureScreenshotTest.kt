package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 golden of a failed one-row widget cell (#245): the compact form on a
 * glass card over a black wallpaper, in the light theme - the case the
 * device showed the icon barely visible in, because the card handed it the
 * theme's dark onSurface.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class CompactFailureScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactFailureOnDarkWallpaper() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp, 120.dp).background(Color.Black), contentAlignment = Alignment.Center) {
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
        composeRule.onRoot().captureRoboImage()
    }
}

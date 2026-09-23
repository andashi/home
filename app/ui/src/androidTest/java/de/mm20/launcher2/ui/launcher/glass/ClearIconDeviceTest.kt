package de.mm20.launcher2.ui.launcher.glass

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.ui.component.ShapedLauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Clear chip with a real app icon on a device (#76, L2): Settings ships
 * an adaptive icon with a monochrome layer on current Android, which is the
 * glyph path; its colored layers are the fallback path.
 */
@RunWith(AndroidJUnit4::class)
class ClearIconDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun settingsIcon(): AdaptiveIconDrawable? =
        ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
            .getApplicationIcon("com.android.settings") as? AdaptiveIconDrawable

    private fun render(icon: StaticLauncherIcon): ClearIconKind {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClearIcons provides true) {
                    ShapedLauncherIcon(size = 56.dp, icon = { icon })
                }
            }
        }
        composeRule.waitForIdle()
        return composeRule.onNode(SemanticsMatcher.keyIsDefined(ClearIconKey)).fetchSemanticsNode().config[ClearIconKey]
    }

    @Test
    fun aRealMonochromeLayerIsAGlyph() {
        val mono = settingsIcon()?.monochrome
        assumeTrue("this image's Settings icon has no monochrome layer", mono != null)
        assertEquals(ClearIconKind.Glyph, render(StaticLauncherIcon(TintedIconLayer(mono!!, scale = 1.5f), ColorLayer(0))))
    }

    @Test
    fun aRealColoredIconIsDesaturated() {
        val adaptive = settingsIcon()
        assumeTrue("this image's Settings icon is not adaptive", adaptive != null)
        val icon = StaticLauncherIcon(StaticIconLayer(adaptive!!.foreground, scale = 1.5f), StaticIconLayer(adaptive.background!!, scale = 1.5f))
        assertEquals(ClearIconKind.Desaturated, render(icon))
    }
}

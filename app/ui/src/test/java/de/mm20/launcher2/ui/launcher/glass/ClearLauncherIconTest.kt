package de.mm20.launcher2.ui.launcher.glass

import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.ui.component.ShapedLauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ShapedLauncherIcon inside the launcher: a glass chip with a glyph or the desaturated original (#76). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearLauncherIconTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val glyph = StaticLauncherIcon(TintedIconLayer(ColorDrawable(0xFFFFFFFF.toInt()), scale = 1.5f), ColorLayer(0))
    private val colored = StaticLauncherIcon(StaticIconLayer(ColorDrawable(0xFFE53935.toInt())), ColorLayer(0xFF1E88E5.toInt()))

    private fun show(icon: StaticLauncherIcon, clear: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClearIcons provides clear) {
                    ShapedLauncherIcon(size = 48.dp, icon = { icon })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun kind() = composeRule.onNode(SemanticsMatcher.keyIsDefined(ClearIconKey))
        .fetchSemanticsNode().config[ClearIconKey]

    @Test
    fun `a monochrome icon is a white glyph on a glass chip`() {
        show(glyph, clear = true)
        assertEquals(ClearIconKind.Glyph, kind())
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertExists()
    }

    @Test
    fun `an icon without a glyph is the desaturated original on the same chip`() {
        show(colored, clear = true)
        assertEquals(ClearIconKind.Desaturated, kind())
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertExists()
    }

    @Test
    fun `outside the launcher, the settings for instance, icons stay as they are`() {
        show(colored, clear = false)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ClearIconKey)).assertDoesNotExist()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertDoesNotExist()
    }
}

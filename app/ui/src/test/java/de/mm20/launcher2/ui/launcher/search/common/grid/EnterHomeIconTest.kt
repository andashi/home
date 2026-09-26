package de.mm20.launcher2.ui.launcher.search.common.grid

import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.ui.launcher.glass.ClearIconKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The icon an app shrinks back into when it closes to the home screen. It is
 * composed by the host activity, outside the block that makes launcher icons
 * Clear, so it drew as a shaped Material icon while the icon it lands on is a
 * Clear squircle: an icon could change shape mid-animation. It carries its
 * item's own Clear setting now, as the item popups do.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnterHomeIconTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val glyph = StaticLauncherIcon(TintedIconLayer(ColorDrawable(0xFFFFFFFF.toInt()), scale = 1.5f), ColorLayer(0))

    private fun show(clear: Boolean) {
        // No LocalClearIcons provider here: the host composes it without one.
        composeRule.setContent {
            MaterialTheme {
                EnterHomeIcon(clear = clear, size = 48.dp, icon = { glyph })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the icon of a Clear item stays Clear where the host composes it`() {
        show(clear = true)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ClearIconKey)).assertExists()
    }

    /** Control: an item outside the launcher surfaces keeps its normal icon. */
    @Test
    fun `the icon of an item that was not Clear stays as it was`() {
        show(clear = false)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ClearIconKey)).assertDoesNotExist()
    }
}

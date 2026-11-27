package de.mm20.launcher2.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.glass.GlassSurfaceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The search bar's content keeps 16 dp from the container's edge, on both
 * sides.
 *
 * The bar is a pill when search is closed, so its corner radius is half its
 * height: 24 dp on a 48 dp bar. At the 12 dp this used to be, the icons sat
 * inside the turn of that curve and read as flush with the edge - which is
 * what a person looking at the device reported, before any of this was
 * measured.
 *
 * The number is written here as a literal rather than read from
 * [SearchBarDefaults]: a test that asks the production constant what the
 * production constant is passes in both states and guards nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchBarContentInsetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val inset = 16f
    private val iconSize = 24f

    /** What a Material icon button already keeps between its edge and its glyph. */
    private val iconButtonGlyphInset = 12f

    private val placeholder: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.search_bar_placeholder)

    private fun show(menu: @Composable () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                SearchBar(
                    style = SearchBarStyle.Transparent,
                    level = SearchBarLevel.Resting,
                    value = "",
                    onValueChange = {},
                    glass = true,
                    menu = { menu() },
                )
            }
        }
    }

    private fun barBounds() = composeRule
        .onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))
        .getUnclippedBoundsInRoot()

    @Test
    fun `the leading icon keeps 16 dp from the left edge`() {
        show()
        val bar = barBounds()
        val field = composeRule
            .onNodeWithContentDescription(placeholder, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // The field starts where the leading icon's box ends: inset, icon, inset.
        assertEquals(inset + iconSize + inset, (field.left - bar.left).value, 0.5f)
    }

    @Test
    fun `the trailing content keeps 16 dp from the right edge`() {
        show(menu = { Box(Modifier.testTag("menu-item").size(48.dp)) })
        val bar = barBounds()
        val item = composeRule
            .onNodeWithTag("menu-item", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // A menu item is a 48 dp icon button whose glyph already sits 12 dp
        // inside it, so the bar supplies only the remainder.
        assertEquals(inset - iconButtonGlyphInset, (bar.right - item.right).value, 0.5f)
    }
}

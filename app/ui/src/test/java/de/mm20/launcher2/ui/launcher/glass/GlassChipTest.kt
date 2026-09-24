package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.preferences.KeyboardFilterBarItem
import de.mm20.launcher2.search.SearchFilters
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.search.filters.KeyboardFilterBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Chips on the search screen (#91): glass pills, a selected one visibly stronger. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a chip is a glass pill and selected raises its tint`() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    GlassChip("Apps", onClick = {}, modifier = Modifier.testTag("off"))
                    GlassChip("Apps", onClick = {}, modifier = Modifier.testTag("on"), selected = true)
                }
            }
        }
        fun info(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[GlassSurfaceKey]
        val off = info("off")
        val on = info("on")

        assertEquals(true, off.pill)
        assertEquals(DefaultStyle.tint, off.tint, 1e-4f)
        assertTrue("selected ${on.tint} > unselected ${off.tint}", on.tint >= off.tint + 0.15f)
    }

    /**
     * The icon is the caller's: the Favorites chip keeps its star while
     * selected, only its tint rises. A filter chip passes no icon while
     * selected and gets the check (below).
     */
    @Test
    fun `a selected chip keeps the icon its caller passes`() {
        composeRule.setContent {
            MaterialTheme {
                GlassChip(
                    "Favorites",
                    onClick = {},
                    selected = true,
                    leadingIcon = { Icon(painterResource(R.drawable.apps_20px), null, Modifier.testTag("icon")) },
                )
            }
        }

        composeRule.onNodeWithTag("icon", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(GlassChipCheckTag, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * #108: the filter bar's chips all carry an icon, so a selected one showed
     * only a tint a shade darker; it shows the check in its icon's place.
     */
    @Test
    fun `a selected filter shows the check in its icon's place`() {
        composeRule.setContent {
            MaterialTheme {
                KeyboardFilterBar(
                    filters = SearchFilters(apps = false, shortcuts = true, contacts = false),
                    onFiltersChange = {},
                    items = listOf(KeyboardFilterBarItem.Apps, KeyboardFilterBarItem.Shortcuts),
                )
            }
        }

        composeRule.onAllNodesWithTag(GlassChipCheckTag, useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * The best match among the search actions is highlighted, not selected:
     * its icon stays, and accessibility does not call it checked.
     */
    @Test
    fun `a highlighted chip keeps its icon and is not a selection`() {
        composeRule.setContent {
            MaterialTheme {
                GlassChip(
                    "Web search",
                    onClick = {},
                    modifier = Modifier.testTag("chip"),
                    highlighted = true,
                    leadingIcon = { Icon(painterResource(R.drawable.apps_20px), null, Modifier.testTag("icon")) },
                )
            }
        }

        composeRule.onNodeWithTag("icon", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(GlassChipCheckTag, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Web search").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        val tint = composeRule.onNodeWithTag("chip").fetchSemanticsNode().config[GlassSurfaceKey].tint
        assertTrue("highlighted tint $tint", tint >= DefaultStyle.tint + 0.15f)
    }

    /** Control: unselected, the icon stays and there is no check. */
    @Test
    fun `an unselected chip keeps its icon`() {
        composeRule.setContent {
            MaterialTheme {
                GlassChip(
                    "Apps",
                    onClick = {},
                    leadingIcon = { Icon(painterResource(R.drawable.apps_20px), null, Modifier.testTag("icon")) },
                )
            }
        }

        composeRule.onNodeWithTag("icon", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(GlassChipCheckTag, useUnmergedTree = true).assertDoesNotExist()
    }

    /** #108: the state is on the node you tap, so TalkBack and uiautomator see it. */
    @Test
    fun `the filter bar reports the selected category on the chip you tap`() {
        composeRule.setContent {
            MaterialTheme {
                KeyboardFilterBar(
                    filters = SearchFilters(apps = false, shortcuts = true, contacts = false),
                    onFiltersChange = {},
                    items = listOf(KeyboardFilterBarItem.Apps, KeyboardFilterBarItem.Shortcuts, KeyboardFilterBarItem.Contacts),
                )
            }
        }

        composeRule.onNodeWithText("App shortcuts").assertIsSelected()
        composeRule.onNodeWithText("Apps").assertIsNotSelected()
        composeRule.onNodeWithText("Contacts").assertIsNotSelected()
    }
}

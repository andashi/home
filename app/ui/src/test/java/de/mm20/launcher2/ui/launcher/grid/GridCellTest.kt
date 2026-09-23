package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.ui.R
import org.junit.Assert.assertEquals
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import de.mm20.launcher2.ui.launcher.glass.GlassSurfaceKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The AppWidget cell without a host id: the banner with Replace and Remove,
 * the state a refused bind or a vanished provider leaves behind.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class GridCellTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `an item without a host id shows the banner and Remove calls back`() {
        var removed = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 200.dp)) {
                    AppWidgetCell(
                        item = gridItem("clock", 0, 0, 3, 2),
                        onRemove = { removed++ },
                        onReplace = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.app_widget_loading_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.widget_action_replace)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.widget_action_remove)).performClick()

        assertEquals(1, removed)
    }

    @Test
    fun `an item whose provider is gone shows the same banner`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 200.dp)) {
                    AppWidgetCell(
                        item = gridItem("clock", 0, 0, 3, 2, appWidgetId = 4711),
                        onRemove = {},
                        onReplace = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.app_widget_loading_failed)).assertIsDisplayed()
    }

    @Test
    fun `a card is glass, and a widget that asked for no background gets no surface at all`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(100.dp).testTag("transparent")) { GridCard(transparent = true) {} }
                Box(Modifier.size(100.dp).testTag("card")) { GridCard(transparent = false) {} }
            }
        }

        composeRule.onNode(
            hasTestTag("card").and(androidx.compose.ui.test.hasAnyDescendant(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)))
        ).assertExists()
        composeRule.onNode(hasTestTag("transparent").and(androidx.compose.ui.test.hasAnyDescendant(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))))
            .assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertCountEquals(1)
    }

    @Test
    fun `an unbound item whose provider is installed offers Allow`() {
        // Binding without a dialog needs the bind-widget grant, which only the
        // system's "always allow" dialog gives a third-party launcher; the
        // cell offers to open it.
        var allowed = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 200.dp)) {
                    AppWidgetCell(
                        item = gridItem("clock", 0, 0, 3, 2),
                        onRemove = {},
                        onReplace = { _, _ -> },
                        onAllow = { allowed++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.widget_action_allow)).performClick()

        assertEquals(1, allowed)
    }
}

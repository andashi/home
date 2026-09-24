package de.mm20.launcher2.ui.launcher.searchbar

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.searchactions.actions.WebsearchAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #116 review: automation finds the search-action row by a test tag exposed
 * as its resource id, not by a content description a screen reader would
 * announce in every locale.
 */
@RunWith(AndroidJUnit4::class)
class SearchBarActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the row is tagged for automation and says nothing to a screen reader`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SearchBarActions(actions = listOf(WebsearchAction("Web search", "q")), highlightedAction = null)
                }
            }
        }

        composeRule.onNodeWithTag("search-actions").assertExists()
        composeRule.onNodeWithContentDescription("search-actions").assertDoesNotExist()
    }
}

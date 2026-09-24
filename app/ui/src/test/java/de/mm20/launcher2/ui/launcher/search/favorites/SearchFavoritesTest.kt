package de.mm20.launcher2.ui.launcher.search.favorites

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.search.Tag
import de.mm20.launcher2.ui.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #105: search shows no favorites card while there is nothing in it. */
@RunWith(AndroidJUnit4::class)
class SearchFavoritesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Fake(override val key: String) : SavableSearchable {
        override val domain = "test"
        override val label = key
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw UnsupportedOperationException()
        override fun getSerializer(): SearchableSerializer = throw UnsupportedOperationException()
    }

    private fun string(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(favorites: List<SavableSearchable>, pinnedTags: List<Tag>, selectedTag: String?) {
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    SearchFavorites(
                        favorites = favorites,
                        pinnedTags = pinnedTags,
                        selectedTag = selectedTag,
                        compactTags = false,
                        tagsExpanded = false,
                        onExpandTags = {},
                        onSelectTag = {},
                        editButton = false,
                        reverse = false,
                    )
                }
            }
        }
    }

    @Test
    fun `no favorites and no tags show no card`() {
        show(favorites = emptyList(), pinnedTags = emptyList(), selectedTag = null)

        composeRule.onNodeWithText(string(R.string.favorites_empty)).assertDoesNotExist()
    }

    /** Control: a tag the user picked says it is empty, so there is a way back. */
    @Test
    fun `a selected empty tag still says so`() {
        show(favorites = emptyList(), pinnedTags = emptyList(), selectedTag = "work")

        composeRule.onNodeWithText(string(R.string.favorites_empty_tag)).assertExists()
    }

    @Test
    fun `the card shows once there is something in it`() {
        assertFalse(showFavoritesCard(favorites = emptyList(), pinnedTags = emptyList(), selectedTag = null))
        assertTrue(showFavoritesCard(favorites = listOf(Fake("calc")), pinnedTags = emptyList(), selectedTag = null))
        assertTrue(showFavoritesCard(favorites = emptyList(), pinnedTags = listOf(Tag("work")), selectedTag = null))
        assertTrue(showFavoritesCard(favorites = emptyList(), pinnedTags = emptyList(), selectedTag = "work"))
    }
}

package de.mm20.launcher2.ui.launcher.search

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.ui.launcher.glass.GlassEdge
import de.mm20.launcher2.ui.launcher.glass.GlassSurfaceKey
import de.mm20.launcher2.ui.launcher.search.common.grid.GridResults
import de.mm20.launcher2.ui.launcher.search.common.list.ListResults
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Result sections on the search screen are glass (#91): each lazily laid out
 * slice is a glass segment, open where it meets the next slice of the same
 * section, so a section reads as one card.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchResultSegmentsTest {

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

    private fun items(n: Int) = List(n) { Fake("item-$it") }

    /** The open edges of every glass segment, top to bottom. */
    private fun segments(content: LazyListScope.() -> Unit): List<Set<GlassEdge>> {
        composeRule.setContent {
            MaterialTheme { LazyColumn(content = content) }
        }
        return composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[GlassSurfaceKey].openEdges }
    }

    @Test
    fun `a grid section with a header is one card of three segments`() {
        val edges = segments {
            GridResults(
                key = "apps",
                items = items(6),
                itemContent = { Box(Modifier.size(40.dp)) },
                before = { Text("header") },
                columns = 4,
            )
        }
        assertEquals(
            listOf(setOf(GlassEdge.Bottom), setOf(GlassEdge.Top, GlassEdge.Bottom), setOf(GlassEdge.Top)),
            edges,
        )
    }

    @Test
    fun `a grid section of one row is a closed card`() {
        val edges = segments {
            GridResults(key = "apps", items = items(3), itemContent = { Box(Modifier.size(40.dp)) }, columns = 4)
        }
        assertEquals(listOf(emptySet<GlassEdge>()), edges)
    }

    @Test
    fun `a list section is one card, open between its items`() {
        val edges = segments {
            ListResults(
                key = "contacts",
                items = items(3),
                itemContent = { _, _, _ -> Box(Modifier.size(40.dp)) },
            )
        }
        assertEquals(
            listOf(setOf(GlassEdge.Bottom), setOf(GlassEdge.Top, GlassEdge.Bottom), setOf(GlassEdge.Top)),
            edges,
        )
    }

    @Test
    fun `an expanded list item is a card of its own and closes its neighbours`() {
        val edges = segments {
            ListResults(
                key = "contacts",
                items = items(3),
                itemContent = { _, _, _ -> Box(Modifier.size(40.dp)) },
                selectedIndex = 1,
            )
        }
        assertEquals(listOf(emptySet<GlassEdge>(), emptySet(), emptySet()), edges)
    }

    /** Review on #98: a header narrower than the list made the card a narrow slice over full rows. */
    @Test
    fun `a narrow header segment is as wide as the rows`() {
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    GridResults(
                        key = "apps",
                        items = items(6),
                        itemContent = { Box(Modifier.size(40.dp)) },
                        before = { Text("h") },
                        columns = 4,
                    )
                }
            }
        }
        val widths = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))
            .fetchSemanticsNodes()
            .map { it.boundsInRoot.width }
        assertEquals("header and rows: $widths", 1, widths.distinct().size)
    }
}

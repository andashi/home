package de.mm20.launcher2.ui.launcher.search

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridGeometry
import de.mm20.launcher2.homegrid.SearchLayout
import de.mm20.launcher2.ui.locals.LocalGridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Search on the home grid (#91): its columns, and two panes at the fold's seam. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchPanesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `search reads the home grid's columns, not upstream's grid setting`() {
        var columns = 0
        composeRule.setContent {
            // Upstream's default is 5 (4 on narrow screens); the home grid says 4.
            ProvideSearchGrid(SearchLayout.Single(4)) {
                columns = LocalGridSettings.current.columnCount
            }
        }
        composeRule.waitForIdle()
        assertEquals(4, columns)
    }

    /** Where a node is, in dp from the root's left edge. */
    private fun spanDp(tag: String): ClosedFloatingPointRange<Float> {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        return (node.boundsInRoot.left / density)..(node.boundsInRoot.right / density)
    }

    private fun showPanes(layout: SearchLayout, widthDp: Float) {
        composeRule.setContent {
            // Centered, as SearchComponent places search: the panes must fill
            // the grid area, not shrink to one pane and be centered (#91).
            Box(Modifier.width(widthDp.dp), contentAlignment = Alignment.Center) {
                SearchPanes(
                    layout = layout,
                    appsState = rememberLazyListState(),
                    resultsState = rememberLazyListState(),
                    contentPadding = PaddingValues(),
                    reverse = false,
                    userScrollEnabled = true,
                    apps = { item { Box(Modifier.fillMaxWidth().height(80.dp).testTag("apps")) } },
                    results = { item { Box(Modifier.fillMaxWidth().height(80.dp).testTag("contacts")) { Text("Carla") } } },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w790dp-h820dp-420dpi")
    fun `on the fold's inner display the apps and the other results meet at the seam, not across it`() {
        // Built by hand, so this pins the arrangement, not SearchLayout.from.
        val g = HomeGridGeometry.derive(FormFactor.Fold, 4, 774f, 800f)
        val half = g.cellDp * 4 + g.gapDp * 3
        val layout = SearchLayout.TwoPane(
            columns = 4,
            apps = SearchLayout.Pane(0f, half),
            results = SearchLayout.Pane(half + g.gapDp, 774f - half - g.gapDp),
        )
        showPanes(layout, 774f)

        val apps = spanDp("apps")
        val contacts = spanDp("contacts")
        val seam = layout.apps.endDp + g.gapDp / 2
        assertEquals(layout.apps.startDp, apps.start, 1f)
        assertEquals(layout.apps.endDp, apps.endInclusive, 1f)
        assertEquals(layout.results.startDp, contacts.start, 1f)
        assertEquals(layout.results.endDp, contacts.endInclusive, 1f)
        assertTrue("apps end ${apps.endInclusive} before the seam $seam", apps.endInclusive <= seam)
        assertTrue("results start ${contacts.start} after the seam $seam", contacts.start >= seam)
    }

    /** Control: one column of results on a phone - both sections full width. */
    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun `on a phone both sections are full width`() {
        showPanes(SearchLayout.Single(4), 396f)
        assertEquals(396f, spanDp("apps").endInclusive, 1f)
        assertEquals(396f, spanDp("contacts").endInclusive, 1f)
    }
}

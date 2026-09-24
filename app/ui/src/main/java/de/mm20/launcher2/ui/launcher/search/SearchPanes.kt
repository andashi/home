package de.mm20.launcher2.ui.launcher.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.homegrid.SearchLayout
import de.mm20.launcher2.ui.locals.LocalGridSettings

/**
 * Search shows the home grid's columns (#91), not upstream's grid setting:
 * with the same outer and inner margins as the dock, an app in search sits in
 * the same column as it would on the home screen, at the dock's icon size.
 */
@Composable
fun ProvideSearchGrid(layout: SearchLayout, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGridSettings provides LocalGridSettings.current.copy(columnCount = layout.columns),
        content = content,
    )
}

/**
 * Search's results in [layout] (#91): one list, or on a fold's inner display
 * two lists at the seam - [apps] in the cover's half, [results] in the other,
 * each exactly as wide as the home grid's cells on its side. Nothing crosses
 * the hinge.
 */
@Composable
fun SearchPanes(
    layout: SearchLayout,
    appsState: LazyListState,
    resultsState: LazyListState,
    contentPadding: PaddingValues,
    reverse: Boolean,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
    apps: LazyListScope.() -> Unit,
    results: LazyListScope.() -> Unit,
) {
    when (layout) {
        is SearchLayout.Single -> LazyColumn(
            modifier = modifier,
            state = appsState,
            userScrollEnabled = userScrollEnabled,
            contentPadding = contentPadding,
            reverseLayout = reverse,
        ) {
            apps()
            results()
        }

        // The whole grid area: the panes are placed by offset, which does not
        // widen the Box, so a Box sized to its content would be one pane
        // wide and a centering parent would shift both panes.
        is SearchLayout.TwoPane -> Box(modifier.fillMaxSize()) {
            Pane(layout.apps, appsState, contentPadding, reverse, userScrollEnabled, apps)
            Pane(layout.results, resultsState, contentPadding, reverse, userScrollEnabled, results)
        }
    }
}

@Composable
private fun Pane(
    pane: SearchLayout.Pane,
    state: LazyListState,
    contentPadding: PaddingValues,
    reverse: Boolean,
    userScrollEnabled: Boolean,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .offset(x = pane.startDp.dp)
            .width(pane.widthDp.dp)
            .fillMaxHeight(),
        state = state,
        userScrollEnabled = userScrollEnabled,
        contentPadding = contentPadding,
        reverseLayout = reverse,
        content = content,
    )
}

/** Whether search's lists can scroll further, in either direction (#91). */
data class PaneScroll(val forward: Boolean, val backward: Boolean)

/**
 * The scroll state of search as a whole: the apps list, and the results list
 * while two panes are shown. A results list that is not composed keeps its
 * last state and must not count (review on #99: after folding to the cover
 * it held the search bar in the wrong position).
 */
fun searchScroll(apps: PaneScroll, results: PaneScroll, twoPane: Boolean): PaneScroll =
    if (!twoPane) apps
    else PaneScroll(apps.forward || results.forward, apps.backward || results.backward)

package de.mm20.launcher2.ui.launcher.search

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

        is SearchLayout.TwoPane -> Box(modifier) {
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

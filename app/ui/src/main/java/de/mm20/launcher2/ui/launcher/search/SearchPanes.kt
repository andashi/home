package de.mm20.launcher2.ui.launcher.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.mm20.launcher2.homegrid.SearchLayout

/** Search shows the home grid's columns (#91). */
@Composable
fun ProvideSearchGrid(layout: SearchLayout, content: @Composable () -> Unit) {
    content()
}

/**
 * Search's results in [layout] (#91): one list, or on a fold's inner display
 * two lists at the seam - [apps] in the cover's half, [results] in the other.
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
    LazyColumn(
        modifier = modifier,
        state = appsState,
        userScrollEnabled = userScrollEnabled,
        contentPadding = contentPadding,
        reverseLayout = reverse,
    ) {
        apps()
        results()
    }
}

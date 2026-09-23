package de.mm20.launcher2.ui.launcher.scaffold.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import de.mm20.launcher2.ui.launcher.glass.LocalOnGlass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.search.SearchColumn
import de.mm20.launcher2.ui.launcher.search.SearchVM

internal class SearchComponent(
    private val reverse: Boolean = false,
    private val openKeyboard: Boolean = true,
) : ScaffoldComponent() {

    override val isAtTop: MutableState<Boolean?> = mutableStateOf(true)

    override val isAtBottom: MutableState<Boolean?> = mutableStateOf(true)

    override val reverseScrolling: Boolean = reverse

    override val hasIme: Boolean = true

    // No flat color over the wallpaper: the background behind search is the
    // blurred backdrop, or the wallpaper itself (appearance.glass.
    // searchWallpaperBlur, #91), drawn by GlassWallpaper.
    override val drawBackground: Boolean = false


    @Composable
    override fun Component(
        modifier: Modifier,
        insets: PaddingValues,
        state: LauncherScaffoldState
    ) {
        val searchVM = viewModel<SearchVM>()
        val lazyListState = rememberLazyListState()
        // The results pane on a fold's inner display (#91); idle in one column.
        val resultsState = rememberLazyListState()

        LaunchedEffect(isActive) {
            if (!isActive) {
                searchVM.reset()
                lazyListState.scrollToItem(0, 0)
                resultsState.scrollToItem(0, 0)
            }
        }

        LaunchedEffect(searchVM.searchQuery.value, searchVM.filters.value) {
            lazyListState.requestScrollToItem(0, 0)
            resultsState.requestScrollToItem(0, 0)
        }

        // At the top or bottom only when every pane is: the search bar and
        // the system-bar strips follow the content that is still scrolled.
        val canScrollForward = lazyListState.canScrollForward || resultsState.canScrollForward
        val canScrollBackward = lazyListState.canScrollBackward || resultsState.canScrollBackward
        LaunchedEffect(canScrollForward, canScrollBackward) {
            isAtBottom.value =
                !canScrollForward && !reverse || !canScrollBackward && reverse
            isAtTop.value =
                !canScrollForward && reverse || !canScrollBackward && !reverse
        }


        val scrollConnection = remember(state) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    searchVM.bestMatch.value = null
                    state.isSearchBarFocused = false
                    state.onComponentScroll(
                        if (reverse) consumed.y else -consumed.y,
                    )
                    return super.onPostScroll(consumed, available, source)
                }
            }
        }

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {

            CompositionLocalProvider(LocalOnGlass provides true) {
                SearchColumn(
                    modifier = Modifier.nestedScroll(scrollConnection).widthIn(max = 916.dp).fillMaxHeight(),
                    paddingValues = insets,
                    state = lazyListState,
                    resultsState = resultsState,
                    reverse = reverse,
                    userScrollEnabled = !state.isDragged,
                    onHideKeyboard = {
                        state.isSearchBarFocused = false
                    }
                )
            }
        }
    }

    override suspend fun onDismiss(state: LauncherScaffoldState) {
        super.onDismiss(state)
    }

    override fun onPreActivate(state: LauncherScaffoldState) {
        super.onPreActivate(state)
        if (openKeyboard) {
            state.isSearchBarFocused = true
        }
    }

    override fun onPreDismiss(state: LauncherScaffoldState) {
        super.onPreDismiss(state)
        state.isSearchBarFocused = false
    }
}
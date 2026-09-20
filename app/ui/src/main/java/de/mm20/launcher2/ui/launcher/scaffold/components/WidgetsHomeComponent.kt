package de.mm20.launcher2.ui.launcher.scaffold.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.widgets.WidgetColumn
import kotlinx.coroutines.launch

/**
 * The home surface: a scrollable column of widgets, and nothing else.
 *
 * The built-in clock used to sit above this column and, with the widget column
 * switched off, was the entire home screen. It was dropped with the other
 * built-in widgets (ADR 0008), so a home screen with no widgets configured is
 * deliberately empty: wallpaper, dock and search bar. ADR 0001's HomeGrid
 * replaces this component.
 */
internal object WidgetsHomeComponent : ScaffoldComponent() {
    private var editMode by mutableStateOf(false)
    private val scrollState = ScrollState(0)

    override val isAtTop: State<Boolean?> = derivedStateOf {
        !scrollState.canScrollBackward
    }

    override val isAtBottom: State<Boolean?> = derivedStateOf {
        !scrollState.canScrollForward || scrollState.value == 0
    }

    override val drawBackground: Boolean = false

    override val showSearchBar: Boolean = false

    @Composable
    override fun Component(
        modifier: Modifier,
        insets: PaddingValues,
        state: LauncherScaffoldState
    ) {
        val scope = rememberCoroutineScope()

        val topPadding by animateDpAsState(if (editMode) 80.dp else 0.dp)
        val previousScroll = remember { mutableIntStateOf(scrollState.value) }

        LaunchedEffect(
            scrollState.value,
            scrollState.canScrollForward,
            scrollState.canScrollBackward
        ) {
            val delta = scrollState.value - previousScroll.intValue
            previousScroll.intValue = scrollState.value
            if (!editMode) {
                state.onComponentScroll(delta.toFloat())
            }
        }

        Column(
            modifier = modifier
                .verticalScroll(scrollState, enabled = !state.isDragged)
                .padding(horizontal = 8.dp)
                .padding(top = topPadding)
                .padding(insets),
        ) {
            WidgetColumn(
                editMode = editMode,
                onEditModeChange = {
                    scope.launch { state.lock(hideSearchBar = true) }
                    editMode = it
                },
                parentId = WidgetScreenTarget.Default.id.toString(),
            )
        }
        if (editMode) {
            BackHandler {
                editMode = false
                scope.launch { state.unlock() }
            }
        }
        AnimatedVisibility(
            editMode,
            modifier = Modifier.zIndex(10f),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.menu_edit_widgets)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            editMode = false
                            scope.launch { state.unlock() }
                        }
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24px),
                            stringResource(R.string.action_done)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    }

    override suspend fun onDismiss(state: LauncherScaffoldState) {
        super.onDismiss(state)
        scrollState.scrollTo(0)
    }
}

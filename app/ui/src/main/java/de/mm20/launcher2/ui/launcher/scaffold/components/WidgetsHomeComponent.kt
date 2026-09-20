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
import androidx.compose.runtime.collectAsState
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.widgets.WidgetColumn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The home surface: a scrollable column of widgets, and nothing else.
 *
 * The built-in clock used to sit above this column and, with the widget column
 * switched off, was the entire home screen. It was dropped with the other
 * built-in widgets (ADR 0008), so a home screen with no widgets configured is
 * deliberately empty: wallpaper, dock and search bar. ADR 0001's HomeGrid
 * replaces this component.
 *
 * `home.widgets.enabled` still decides whether widgets appear here at all. It
 * used to do that by choosing between this component and the clock-only one;
 * with the clock gone there is nothing to choose between, so the flag is read
 * here instead. Dropping it would silently ignore a key of the public config
 * contract (ADR 0002).
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

    @Composable
    override fun Component(
        modifier: Modifier,
        insets: PaddingValues,
        state: LauncherScaffoldState
    ) {
        val scope = rememberCoroutineScope()

        val uiSettings: UiSettings = koinInject()
        val widgetsOnHomeScreen by uiSettings.homeScreenWidgets.collectAsState(null)
        if (widgetsOnHomeScreen != true) return

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

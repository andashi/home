package de.mm20.launcher2.ui.launcher.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.scaffold.components.ScaffoldComponent
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The home surface (ADR 0001): one page, the [HomeGrid], nothing else. It
 * replaces `WidgetsHomeComponent`, which stays compiled but unreferenced until
 * the grid has settled (ADR 0001, consequences).
 *
 * `home.widgets.enabled` still decides whether anything is drawn: the key is
 * part of the public contract (ADR 0002) and also gates gesture targets, so
 * it is read here exactly as the column read it.
 *
 * While the grid is in edit mode the scaffold is locked with the search bar
 * hidden, as the old column did, and Back leaves edit mode (which writes the
 * layout back) instead of doing whatever Back does on the home page.
 */
internal object HomeGridComponent : ScaffoldComponent() {

    // The grid does not scroll: it is always at its top and its bottom, so
    // the scaffold's swipe gestures stay available in both directions.
    override val isAtTop: State<Boolean?> = mutableStateOf(true)
    override val isAtBottom: State<Boolean?> = mutableStateOf(true)

    override val drawBackground: Boolean = false

    @Composable
    override fun Component(
        modifier: Modifier,
        insets: PaddingValues,
        state: LauncherScaffoldState,
    ) {
        val uiSettings: UiSettings = koinInject()
        val widgetsOnHomeScreen by uiSettings.homeScreenWidgets.collectAsState(null)
        if (widgetsOnHomeScreen != true) return

        val viewModel: HomeGridVM = viewModel(factory = HomeGridVM.factory())
        val editing by viewModel.editing.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        LaunchedEffect(editing) {
            if (editing) state.lock(hideSearchBar = true) else state.unlock()
        }
        if (editing) {
            BackHandler { scope.launch { viewModel.exitEdit() } }
        }

        HomeGrid(
            modifier = modifier
                .padding(insets)
                .padding(horizontal = 8.dp),
            viewModel = viewModel,
        )
    }
}

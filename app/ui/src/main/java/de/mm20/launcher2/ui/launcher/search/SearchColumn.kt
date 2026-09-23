package de.mm20.launcher2.ui.launcher.search

import de.mm20.launcher2.homegrid.SearchLayout
import de.mm20.launcher2.homegrid.HomeGridGeometry
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.AppShortcut
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.Contact
import de.mm20.launcher2.ui.launcher.search.apps.AppResults
import de.mm20.launcher2.ui.launcher.search.contacts.ContactResults
import de.mm20.launcher2.ui.launcher.search.favorites.SearchFavorites
import de.mm20.launcher2.ui.launcher.search.favorites.SearchFavoritesVM
import de.mm20.launcher2.ui.launcher.search.filters.SearchFilters
import de.mm20.launcher2.ui.launcher.search.shortcut.ShortcutResults
import de.mm20.launcher2.ui.launcher.sheets.HiddenItemsSheet
import de.mm20.launcher2.ui.launcher.sheets.LocalBottomSheetManager
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.ui.launcher.glass.GlassSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.zip

@Composable
fun SearchColumn(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    /** The results pane's list on a fold's inner display (#91); unused in one column. */
    resultsState: LazyListState = rememberLazyListState(),
    reverse: Boolean = false,
    userScrollEnabled: Boolean = true,
    onHideKeyboard: () -> Unit = {},
) {

    val showList = LocalGridSettings.current.showList
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel: SearchVM = viewModel()
    val homeGridColumns by viewModel.homeGridColumns.collectAsState(4)

    val favoritesVM: SearchFavoritesVM = viewModel()
    val favorites by favoritesVM.favorites.collectAsState(emptyList())

    val hideFavs by viewModel.hideFavorites
    val favoritesEnabled by viewModel.favoritesEnabled.collectAsState(false)
    val allAppsEnabled by viewModel.allAppsEnabled.collectAsState(false)

    val apps = viewModel.appResults
    val workApps = viewModel.workAppResults
    val privateApps = viewModel.privateSpaceAppResults
    val profiles by viewModel.profiles.collectAsState(emptyList())
    val profileStates by viewModel.profileStates.collectAsState(emptyMap())

    val appShortcuts = viewModel.appShortcutResults
    val contacts = viewModel.contactResults
    val hiddenResults = viewModel.hiddenResults

    val bestMatch by viewModel.bestMatch

    val query by viewModel.searchQuery
    val isSearchEmpty by viewModel.isSearchEmpty

    val missingShortcutsPermission by viewModel.missingAppShortcutPermission.collectAsState(false)
    val missingContactsPermission by viewModel.missingContactsPermission.collectAsState(false)
    val hasProfilesPermission by viewModel.hasProfilesPermission.collectAsState(false)

    val pinnedTags by favoritesVM.pinnedTags.collectAsState(emptyList())
    val selectedTag by favoritesVM.selectedTag.collectAsState(null)
    val compactTags by favoritesVM.compactTags.collectAsState(false)
    val favoritesEditButton by favoritesVM.showEditButton.collectAsState(false)
    val favoritesTagsExpanded by favoritesVM.tagsExpanded.collectAsState(false)

    val expandedCategory: SearchCategory? by viewModel.expandedCategory

    var selectedAppProfileIndex by remember { mutableIntStateOf(-1) }
    var selectedAppIndex: Int by remember(query) { mutableIntStateOf(-1) }
    var selectedContactIndex: Int by remember(query) { mutableIntStateOf(-1) }
    var selectedShortcutIndex: Int by remember(query) { mutableIntStateOf(-1) }

    val showFilters by viewModel.showFilters

    LaunchedEffect(profiles) {
        var previousState: Profile.State? = null
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.profileStates.map { it[Profile.Type.Private] }.collect {

                if (it == previousState) return@collect
                previousState = it

                viewModel.search("")

                if (it?.locked == false && !it.hidden) {
                    val index = profiles.indexOfFirst { p -> p.type == Profile.Type.Private }
                    if (index != -1) {
                        selectedAppProfileIndex = index
                    }
                }
            }
        }
    }

    // Search lays out on the home grid (#91): the same width the home grid
    // derives its geometry from - this column's inner width, minus the
    // system insets - gives the same columns at the same pitch.
    BoxWithConstraints(modifier.padding(horizontal = 8.dp)) {
        val layoutDirection = LocalLayoutDirection.current
        val insetStart = paddingValues.calculateStartPadding(layoutDirection)
        val insetEnd = paddingValues.calculateEndPadding(layoutDirection)
        val layout = remember(viewModel.formFactor, homeGridColumns, maxWidth, insetStart, insetEnd) {
            SearchLayout.from(
                HomeGridGeometry.derive(
                    viewModel.formFactor,
                    homeGridColumns,
                    (maxWidth - insetStart - insetEnd).value,
                    maxHeight.value,
                )
            )
        }
        val twoPane = layout is SearchLayout.TwoPane
        // The panes are placed in the grid area; the vertical insets stay
        // content padding, so results scroll under the system bars.
        val verticalPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding(),
        )
        ProvideSearchGrid(layout) {
            val columns = LocalGridSettings.current.columnCount
            AnimatedContent(showFilters && !twoPane) { fullScreenFilters ->
                if (fullScreenFilters) {
                    BackHandler {
                        viewModel.showFilters.value = false
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = if (reverse) Alignment.BottomCenter else Alignment.TopCenter,
                    ) {
                        GlassSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            SearchFilters(
                                modifier = Modifier.padding(12.dp),
                                filters = viewModel.filters.value,
                                onFiltersChange = {
                                    viewModel.setFilters(it)
                                }
                            )
                        }
                    }
                } else {
                    SearchPanes(
                        layout = layout,
                        appsState = state,
                        resultsState = resultsState,
                        contentPadding = verticalPadding,
                        reverse = reverse,
                        userScrollEnabled = userScrollEnabled,
                        modifier = Modifier.padding(start = insetStart, end = insetEnd),
                        apps = {
            if (!hideFavs && favoritesEnabled) {
                SearchFavorites(
                    favorites = favorites,
                    selectedTag = selectedTag,
                    pinnedTags = pinnedTags,
                    tagsExpanded = favoritesTagsExpanded,
                    onSelectTag = { favoritesVM.selectTag(it) },
                    reverse = reverse,
                    onExpandTags = {
                        favoritesVM.setTagsExpanded(it)
                    },
                    compactTags = compactTags,
                    editButton = favoritesEditButton
                )
            } else {
                // Empty item to maintain scroll position
                item(key = "favorites") {
                }
            }

            if (isSearchEmpty && profiles.size > 1 && allAppsEnabled) {
                val visibleProfiles by derivedStateOf {
                    profiles.filter { profileStates[it.type]?.hidden == false }
                }
                val selectedProfile = visibleProfiles.getOrNull(selectedAppProfileIndex) ?: visibleProfiles.firstOrNull()
                AppResults(
                    apps = when (selectedProfile?.type) {
                        Profile.Type.Private -> privateApps
                        Profile.Type.Work -> workApps
                        else -> apps
                    },
                    highlightedItem = bestMatch as? Application,
                    profiles = visibleProfiles,
                    profileStates = profileStates,
                    selectedProfile = visibleProfiles.getOrNull(selectedAppProfileIndex),
                    onProfileSelected = {
                        selectedAppProfileIndex = visibleProfiles.indexOf(it)
                        onHideKeyboard()
                    },
                    onProfileLockChange = { p, l ->
                        viewModel.setProfileLock(p, l)
                    },
                    columns = columns,
                    reverse = reverse,
                    showProfileLockControls = hasProfilesPermission,
                    showList = showList,
                    selectedIndex = selectedAppIndex,
                    onSelect = { selectedAppIndex = it },
                )
            } else if (!isSearchEmpty || allAppsEnabled) {
                AppResults(
                    apps = apps,
                    highlightedItem = bestMatch as? Application,
                    columns = columns,
                    reverse = reverse,
                    showList = showList,
                    selectedIndex = selectedAppIndex,
                    onSelect = { selectedAppIndex = it },
                )
            }
                        },
                        results = {
                            if (twoPane && showFilters) {
                                // On a fold's inner display the filters take
                                // the results pane, not both halves.
                                item(key = "filters") {
                                    BackHandler {
                                        viewModel.showFilters.value = false
                                    }
                                    GlassSurface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                    ) {
                                        SearchFilters(
                                            modifier = Modifier.padding(12.dp),
                                            filters = viewModel.filters.value,
                                            onFiltersChange = {
                                                viewModel.setFilters(it)
                                            }
                                        )
                                    }
                                }
                            } else if (!isSearchEmpty) {
                ShortcutResults(
                    shortcuts = appShortcuts,
                    missingPermission = missingShortcutsPermission,
                    onPermissionRequest = {
                        viewModel.requestAppShortcutPermission(context as AppCompatActivity)
                    },
                    onPermissionRequestRejected = {
                        viewModel.disableAppShortcutSearch()
                    },
                    reverse = reverse,
                    selectedIndex = selectedShortcutIndex,
                    onSelect = { selectedShortcutIndex = it },
                    highlightedItem = bestMatch as? AppShortcut,
                    truncate = expandedCategory != SearchCategory.Shortcuts,
                    onShowAll = {
                        viewModel.expandCategory(SearchCategory.Shortcuts)
                    },
                )

                ContactResults(
                    contacts = contacts,
                    missingPermission = missingContactsPermission,
                    onPermissionRequest = {
                        viewModel.requestContactsPermission(context as AppCompatActivity)
                    },
                    onPermissionRequestRejected = {
                        viewModel.disableContactsSearch()
                    },
                    reverse = reverse,
                    selectedIndex = selectedContactIndex,
                    onSelect = { selectedContactIndex = it },
                    highlightedItem = bestMatch as? Contact,
                    truncate = expandedCategory != SearchCategory.Contacts,
                    onShowAll = {
                        viewModel.expandCategory(SearchCategory.Contacts)
                    },
                )
                            }
                        },
                    )
                }
            }
        }
    }


    val sheetManager = LocalBottomSheetManager.current
    HiddenItemsSheet(
        expanded = sheetManager.hiddenItemsSheetShown.value,
        items = hiddenResults,
        onDismiss = { sheetManager.dismissHiddenItemsSheet() })
}




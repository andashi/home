package de.mm20.launcher2.ui.launcher

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.preferences.ColorScheme
import de.mm20.launcher2.FeatureFlags
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.ScreenOrientation
import de.mm20.launcher2.preferences.SearchBarColors
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.preferences.ui.GestureSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.search.SavableSearchable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LauncherScaffoldVM : ViewModel(), KoinComponent {

    private val uiSettings: UiSettings by inject()
    private val gestureSettings: GestureSettings by inject()
    private val searchableRepository: SavableSearchableRepository by inject()

    private var isSystemInDarkMode = MutableStateFlow(false)

    private val dimBackgroundState = combine(
        uiSettings.dimWallpaper,
        uiSettings.colorScheme,
        isSystemInDarkMode
    ) { dim, theme, systemDarkMode ->
        dim && (theme == ColorScheme.Dark || theme == ColorScheme.System && systemDarkMode)
    }
    val dimBackground = dimBackgroundState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val statusBarColor = uiSettings.statusBarColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    val navBarColor = uiSettings.navigationBarColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val hideNavBar = uiSettings.hideNavigationBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val hideStatusBar = uiSettings.hideStatusBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun setSystemInDarkMode(darkMode: Boolean) {
        isSystemInDarkMode.value = darkMode
    }

    val bottomSearchBar = uiSettings.bottomSearchBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val bottomSearchBarInSearch = uiSettings.bottomSearchBarInSearch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    val reverseSearchResults = uiSettings.reverseSearchResults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val fixedSearchBar = uiSettings.fixedSearchBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val fixedRotation = uiSettings.orientation
        .map { it != ScreenOrientation.Auto }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val widgetsOnHomeScreen = uiSettings.homeScreenWidgets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val autoFocusSearch = uiSettings.openKeyboardOnSearch

    val fillClockHeight = uiSettings.clockFillScreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val searchBarColor = uiSettings.searchBarColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SearchBarColors.Auto)
    val searchBarStyle = uiSettings.searchBarStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SearchBarStyle.Transparent)

    val gestureState: StateFlow<GestureState?> = gestureSettings.map { settings ->
            val swipeLeftAction = settings.swipeLeft.inThisBuild()
            val swipeRightAction = settings.swipeRight.inThisBuild()
            val swipeDownAction = settings.swipeDown.inThisBuild()
            val swipeUpAction = settings.swipeUp.inThisBuild()
            val longPressAction = settings.longPress.inThisBuild()
            val doubleTapAction = settings.doubleTap.inThisBuild()
            val homeButtonAction = settings.homeButton.inThisBuild()

            val swipeLeftAppKey = (swipeLeftAction as? GestureAction.Launch)?.key
            val swipeRightAppKey = (swipeRightAction as? GestureAction.Launch)?.key
            val swipeDownAppKey = (swipeDownAction as? GestureAction.Launch)?.key
            val swipeUpAppKey = (swipeUpAction as? GestureAction.Launch)?.key
            val longPressAppKey = (longPressAction as? GestureAction.Launch)?.key
            val doubleTapAppKey = (doubleTapAction as? GestureAction.Launch)?.key
            val homeButtonAppKey = (homeButtonAction as? GestureAction.Launch)?.key
            // By the key each gesture stores, not the item's own: a merged
            // contact carries the merged key (#237).
            val apps = listOfNotNull(
                swipeLeftAppKey,
                swipeRightAppKey,
                swipeDownAppKey,
                swipeUpAppKey,
                longPressAppKey,
                doubleTapAppKey,
                homeButtonAppKey,
            ).let { searchableRepository.getByStoredKeys(it).first() }

            GestureState(
                swipeLeftAction = swipeLeftAction,
                swipeRightAction = swipeRightAction,
                swipeDownAction = swipeDownAction,
                swipeUpAction = swipeUpAction,
                longPressAction = longPressAction,
                doubleTapAction = doubleTapAction,
                homeButtonAction = homeButtonAction,
                swipeLeftApp = swipeLeftAppKey?.let { apps[it] },
                swipeRightApp = swipeRightAppKey?.let { apps[it] },
                swipeDownApp = swipeDownAppKey?.let { apps[it] },
                swipeUpApp = swipeUpAppKey?.let { apps[it] },
                longPressApp = longPressAppKey?.let { apps[it] },
                doubleTapApp = doubleTapAppKey?.let { apps[it] },
                homeButtonApp = homeButtonAppKey?.let { apps[it] },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * This action as the build can perform it: the feed only where it exists
 * ([FeatureFlags.feed]). The settings screen never offers it elsewhere, but a
 * stored value - restored, migrated, or written by another build - must not
 * open what this build hides (#3 slice 2).
 */
internal fun GestureAction.inThisBuild(feedEnabled: Boolean = FeatureFlags.feed): GestureAction =
    if (this is GestureAction.Feed && !feedEnabled) GestureAction.NoAction else this

data class GestureState(
    val swipeLeftAction: GestureAction = GestureAction.NoAction,
    val swipeRightAction: GestureAction = GestureAction.NoAction,
    val swipeDownAction: GestureAction = GestureAction.NoAction,
    val swipeUpAction: GestureAction = GestureAction.NoAction,
    val longPressAction: GestureAction = GestureAction.NoAction,
    val doubleTapAction: GestureAction = GestureAction.NoAction,
    val homeButtonAction: GestureAction = GestureAction.NoAction,
    val swipeLeftApp: SavableSearchable? = null,
    val swipeRightApp: SavableSearchable? = null,
    val swipeDownApp: SavableSearchable? = null,
    val swipeUpApp: SavableSearchable? = null,
    val longPressApp: SavableSearchable? = null,
    val doubleTapApp: SavableSearchable? = null,
    val homeButtonApp: SavableSearchable? = null,
)


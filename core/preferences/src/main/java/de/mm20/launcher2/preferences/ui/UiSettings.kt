package de.mm20.launcher2.preferences.ui

import de.mm20.launcher2.config.GlassContrast

import de.mm20.launcher2.preferences.ColorScheme
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.IconShape
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.ScreenOrientation
import de.mm20.launcher2.preferences.SearchBarColors
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.preferences.SystemBarColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class CardStyle(
    val opacity: Float = 1f,
    val borderWidth: Int = 0,
)

data class GridSettings(
    val columnCount: Int = 5,
    val iconSize: Int = 48,
    val showLabels: Boolean = true,
    val showList: Boolean = false,
    val showListIcons: Boolean = true,
)

class UiSettings internal constructor(
    private val launcherDataStore: LauncherDataStore,
) {
    val favoritesEnabled
        get() = launcherDataStore.data.map { it.favoritesEnabled }

    val iconShape
        get() = launcherDataStore.data.map {
            it.iconsShape
        }

    fun setIconShape(iconShape: IconShape) {
        launcherDataStore.update {
            it.copy(iconsShape = iconShape)
        }
    }

    val gridSettings
        get() = launcherDataStore.data.map {
            GridSettings(
                showLabels = it.gridLabels,
                showList = it.gridList,
                showListIcons = it.gridListIcons,
                iconSize = it.gridIconSize,
                columnCount = it.gridColumnCount,
            )
        }

    fun setGridColumnCount(columnCount: Int) {
        launcherDataStore.update {
            it.copy(gridColumnCount = columnCount)
        }
    }

    fun setGridIconSize(iconSize: Int) {
        launcherDataStore.update {
            it.copy(gridIconSize = iconSize)
        }
    }

    fun setGridShowLabels(showLabels: Boolean) {
        launcherDataStore.update {
            it.copy(gridLabels = showLabels)
        }
    }

    fun setGridShowList(showList: Boolean) {
        launcherDataStore.update {
            it.copy(gridList = showList)
        }
    }

    fun setGridShowListIcons(showIcons: Boolean) {
        launcherDataStore.update {
            it.copy(gridListIcons = showIcons)
        }
    }

    val dimWallpaper
        get() = launcherDataStore.data.map {
            it.wallpaperDim
        }

    fun setDimWallpaper(dimWallpaper: Boolean) {
        launcherDataStore.update {
            it.copy(wallpaperDim = dimWallpaper)
        }
    }

    val colorScheme
        get() = launcherDataStore.data.map {
            it.uiColorScheme
        }.distinctUntilChanged()

    val compatModeColors
        get() = launcherDataStore.data.map {
            it.uiCompatModeColors
        }.distinctUntilChanged()

    fun setCompatModeColors(enabled: Boolean) {
        launcherDataStore.update {
            it.copy(uiCompatModeColors = enabled)
        }
    }

    val statusBarColor
        get() = launcherDataStore.data.map {
            it.systemBarsStatusColors
        }.distinctUntilChanged()

    val hideStatusBar
        get() = launcherDataStore.data.map {
            it.systemBarsHideStatus
        }.distinctUntilChanged()

    val hideNavigationBar
        get() = launcherDataStore.data.map {
            it.systemBarsHideNav
        }.distinctUntilChanged()

    fun setHideStatusBar(hideStatusBar: Boolean) {
        launcherDataStore.update {
            it.copy(systemBarsHideStatus = hideStatusBar)
        }
    }

    fun setHideNavigationBar(hideNavigationBar: Boolean) {
        launcherDataStore.update {
            it.copy(systemBarsHideNav = hideNavigationBar)
        }
    }

    val navigationBarColor
        get() = launcherDataStore.data.map {
            it.systemBarsNavColors
        }.distinctUntilChanged()

    fun setStatusBarColor(statusBarColor: SystemBarColors) {
        launcherDataStore.update {
            it.copy(systemBarsStatusColors = statusBarColor)
        }
    }

    fun setNavigationBarColor(navigationBarColor: SystemBarColors) {
        launcherDataStore.update {
            it.copy(systemBarsNavColors = navigationBarColor)
        }
    }

    val clockFillScreen
        get() = launcherDataStore.data.map {
            it.homeScreenWidgets
        }.distinctUntilChanged()

    val searchBarStyle
        get() = launcherDataStore.data.map {
            it.searchBarStyle
        }.distinctUntilChanged()

    fun setSearchBarStyle(searchBarStyle: SearchBarStyle) {
        launcherDataStore.update {
            it.copy(searchBarStyle = searchBarStyle)
        }
    }

    val searchBarColor
        get() = launcherDataStore.data.map {
            it.searchBarColors
        }.distinctUntilChanged()

    fun setSearchBarColor(color: SearchBarColors) {
        launcherDataStore.update {
            it.copy(searchBarColors = color)
        }
    }

    val bottomSearchBar
        get() = launcherDataStore.data.map {
            it.searchBarBottom
        }.distinctUntilChanged()

    fun setBottomSearchBar(bottomSearchBar: Boolean) {
        launcherDataStore.update {
            it.copy(searchBarBottom = bottomSearchBar)
        }
    }

    /** The search bar while search is open (#107): null follows [bottomSearchBar]. */
    val bottomSearchBarInSearch
        get() = launcherDataStore.data.map {
            it.searchBarBottomInSearch
        }.distinctUntilChanged()

    val reverseSearchResults
        get() = launcherDataStore.data.map {
            it.searchResultsReversed
        }.distinctUntilChanged()

    fun setReverseSearchResults(reverseSearchResults: Boolean) {
        launcherDataStore.update {
            it.copy(searchResultsReversed = reverseSearchResults)
        }
    }

    val fixedSearchBar
        get() = launcherDataStore.data.map {
            it.searchBarFixed
        }.distinctUntilChanged()

    fun setFixedSearchBar(fixedSearchBar: Boolean) {
        launcherDataStore.update {
            it.copy(searchBarFixed = fixedSearchBar)
        }
    }

    val openKeyboardOnSearch
        get() = launcherDataStore.data.map {
            it.searchBarKeyboard
        }.distinctUntilChanged()

    val orientation
        get() = launcherDataStore.data.map {
            it.uiOrientation
        }.distinctUntilChanged()

    fun setOrientation(orientation: ScreenOrientation) {
        launcherDataStore.update {
            it.copy(uiOrientation = orientation)
        }
    }

    val colorsId
        get() = launcherDataStore.data.map {
            it.uiColorsId
        }.distinctUntilChanged()

    fun setColorsId(colorsId: UUID) {
        launcherDataStore.update {
            it.copy(uiColorsId = colorsId)
        }
    }

    val shapesId
        get() = launcherDataStore.data.map {
            it.uiShapesId
        }.distinctUntilChanged()

    fun setShapesId(shapesId: UUID) {
        launcherDataStore.update {
            it.copy(uiShapesId = shapesId)
        }
    }

    val typographyId
        get() = launcherDataStore.data.map {
            it.uiTypographyId
        }.distinctUntilChanged()

    fun setTypographyId(typographyId: UUID) {
        launcherDataStore.update {
            it.copy(uiTypographyId = typographyId)
        }
    }

    fun setColorScheme(colorScheme: ColorScheme) {
        launcherDataStore.update {
            it.copy(uiColorScheme = colorScheme)
        }
    }

    val homeScreenWidgets
        get() = launcherDataStore.data.map {
            it.homeScreenWidgets
        }.distinctUntilChanged()

    /** `home.grid.columns` (ADR 0001, D1). Config-only: no settings screen writes it. */
    val homeGridColumns
        get() = launcherDataStore.data.map {
            it.homeGridColumns
        }.distinctUntilChanged()

    /** `home.grid.labels` (ADR 0004): a label under every grid item but the dock. */
    val homeGridLabels: Flow<Boolean>
        get() = launcherDataStore.data.map { it.homeGridLabels }.distinctUntilChanged()

    /** `home.grid.locked` (D3): edit mode is refused while true. */
    val homeGridLocked
        get() = launcherDataStore.data.map {
            it.homeGridLocked
        }.distinctUntilChanged()

    /**
     * `appearance.glass` (ADR 0004, #24). Config-only, like the grid keys: no
     * settings screen writes it. Emits again only when a value changes.
     */
    val glass: Flow<GlassSettings>
        get() = launcherDataStore.data.map {
            GlassSettings(
                it.glassBlur, it.glassTint, it.glassRadius, it.glassContrast,
                it.glassWallpaperBlur, it.glassSearchWallpaperBlur,
            )
        }.distinctUntilChanged()

    /** Whether the grid was given its first content (the default favorites row, or a config); see HomeGridDefaults. */
    val homeGridInitialized
        get() = launcherDataStore.data.map {
            it.homeGridInitialized
        }.distinctUntilChanged()

    fun setHomeGridInitialized(initialized: Boolean) {
        launcherDataStore.update {
            it.copy(homeGridInitialized = initialized)
        }
    }

    fun setHomeScreenWidgets(widgets: Boolean) {
        launcherDataStore.update {
            it.copy(homeScreenWidgets = widgets)
        }
    }

}

/** The glass values in effect; see `appearance.glass` in ADR 0002. */
data class GlassSettings(
    val blur: Float,
    val tint: Float,
    val radius: Float,
    val contrast: GlassContrast,
    /** `appearance.glass.wallpaperBlur`: the home background is the blurred backdrop (#82). */
    val wallpaperBlur: Boolean = true,
    /** `appearance.glass.searchWallpaperBlur`: behind search, whatever [wallpaperBlur] says (#91). */
    val searchWallpaperBlur: Boolean = true,
)

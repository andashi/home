package de.mm20.launcher2.preferences.config

import de.mm20.launcher2.config.SearchState
import de.mm20.launcher2.config.SearchResultLayout
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.SearchBarPosition
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import kotlinx.coroutines.flow.first

/**
 * Fork addition (Phase 2): settings-backed projection of [ConfigState] and
 * applier for the settings-backed [ConfigMutation]s produced by
 * `ConfigDiffer`. Lives in `:core:preferences` because [LauncherSettingsData]
 * has internal visibility.
 *
 * All writes go through [LauncherDataStore.updateAndAwait] (one awaited
 * DataStore update per [apply] call); the old fire-and-forget settings
 * setters are not used here.
 *
 * The interface is public so `:services:config` can depend on (and fake) it;
 * the implementation stays internal and is provided via Koin.
 */
interface LauncherConfigSettings {

    /**
     * Reads the settings-backed portion of [ConfigState]. Fields that are not
     * backed by settings (favorites, grid layouts, wallpaper) keep their
     * [ConfigState] defaults and must be filled by their respective
     * repositories.
     */
    suspend fun readState(): ConfigState

    /**
     * Applies [mutations] in a single awaited DataStore update. Mutations
     * that are not backed by settings ([ConfigMutation.SetFavorites],
     * [ConfigMutation.SetWallpaper]) are ignored here; they are handled by
     * their respective repositories.
     * [ConfigMutation.SetGrid] is split: `columns`, `locked` and `labels` land here,
     * `layouts` go to the grid repository.
     *
     * Returns [Unit] in the interface so consumers in other modules can fake
     * it (LauncherSettingsData has an internal constructor); the
     * implementation covariantly returns the updated settings data.
     */
    suspend fun apply(mutations: List<ConfigMutation>)
}

internal class LauncherConfigSettingsImpl(
    private val dataStore: LauncherDataStore,
) : LauncherConfigSettings {

    override suspend fun readState(): ConfigState {
        val data = dataStore.data.first()
        return ConfigState(
            themedIcons = data.iconsThemed,
            enforceThemedIcons = data.iconsForceThemed,
            iconPack = data.iconsPack,
            glassBlur = data.glassBlur,
            glassTint = data.glassTint,
            glassRadius = data.glassRadius,
            glassContrast = data.glassContrast,
            glassWallpaperBlur = data.glassWallpaperBlur,
            glassSearchWallpaperBlur = data.glassSearchWallpaperBlur,
            // search (#91): upstream's own settings, which the search UI reads.
            search = SearchState(
                favorites = data.favoritesEnabled,
                allApps = data.searchAllApps,
                layout = if (data.gridList) SearchResultLayout.List else SearchResultLayout.Grid,
                labels = data.gridLabels,
                contacts = ContactsProvider in data.contactSearchProviders,
                shortcuts = data.shortcutSearchEnabled,
                filterBar = data.searchFilterBar,
                openKeyboard = data.searchBarKeyboard,
                launchOnEnter = data.searchLaunchOnEnter,
                reversed = data.searchResultsReversed,
                hiddenItemsButton = data.hiddenItemsShowButton,
            ),
            searchBarPosition = if (data.searchBarBottom) {
                SearchBarPosition.Bottom
            } else {
                SearchBarPosition.Top
            },
            widgetsEnabled = data.homeScreenWidgets,
            gridColumns = data.homeGridColumns,
            gridLocked = data.homeGridLocked,
            gridLabels = data.homeGridLabels,
        )
    }

    override suspend fun apply(mutations: List<ConfigMutation>) {
        applyAndReturn(mutations)
    }

    /**
     * Rich variant of [apply] for in-module consumers/tests: returns the
     * updated settings data.
     */
    suspend fun applyAndReturn(mutations: List<ConfigMutation>): LauncherSettingsData {
        return dataStore.updateAndAwait { current ->
            mutations.fold(current) { acc, mutation -> acc.apply(mutation) }
        }
    }

    private fun LauncherSettingsData.apply(mutation: ConfigMutation): LauncherSettingsData {
        return when (mutation) {
            is ConfigMutation.SetIcons -> copy(
                iconsThemed = mutation.themed ?: iconsThemed,
                iconsForceThemed = mutation.enforceThemed ?: iconsForceThemed,
                iconsPack = mutation.pack ?: iconsPack,
            )

            is ConfigMutation.SetSearchBarPosition -> copy(
                searchBarBottom = mutation.position == SearchBarPosition.Bottom,
            )

            is ConfigMutation.SetWidgetsEnabled -> copy(homeScreenWidgets = mutation.enabled)

            is ConfigMutation.SetGrid -> copy(
                homeGridColumns = mutation.columns ?: homeGridColumns,
                homeGridLocked = mutation.locked ?: homeGridLocked,
                homeGridLabels = mutation.labels ?: homeGridLabels,
            )

            is ConfigMutation.SetGlass -> copy(
                glassBlur = mutation.blur ?: glassBlur,
                glassTint = mutation.tint ?: glassTint,
                glassRadius = mutation.radius ?: glassRadius,
                glassContrast = mutation.contrast ?: glassContrast,
                glassWallpaperBlur = mutation.wallpaperBlur ?: glassWallpaperBlur,
                glassSearchWallpaperBlur = mutation.searchWallpaperBlur ?: glassSearchWallpaperBlur,
            )

            is ConfigMutation.SetSearch -> with(mutation.search) {
                copy(
                    favoritesEnabled = favorites ?: favoritesEnabled,
                    searchAllApps = allApps ?: searchAllApps,
                    gridList = layout?.let { it == SearchResultLayout.List } ?: gridList,
                    gridLabels = labels ?: gridLabels,
                    // Only the device's own contacts are switched; another
                    // provider a user added stays as it is.
                    contactSearchProviders = when (contacts) {
                        true -> contactSearchProviders + ContactsProvider
                        false -> contactSearchProviders - ContactsProvider
                        null -> contactSearchProviders
                    },
                    shortcutSearchEnabled = shortcuts ?: shortcutSearchEnabled,
                    searchFilterBar = filterBar ?: searchFilterBar,
                    searchBarKeyboard = openKeyboard ?: searchBarKeyboard,
                    searchLaunchOnEnter = launchOnEnter ?: searchLaunchOnEnter,
                    searchResultsReversed = reversed ?: searchResultsReversed,
                    hiddenItemsShowButton = hiddenItemsButton ?: hiddenItemsShowButton,
                )
            }

            is ConfigMutation.SetFavorites,
            is ConfigMutation.SetWallpaper,
            -> this
        }
    }

}

/** The contact search provider for the device's own contacts (upstream's `local`). */
private const val ContactsProvider = "local"

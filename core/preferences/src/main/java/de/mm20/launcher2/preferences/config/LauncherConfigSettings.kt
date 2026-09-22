package de.mm20.launcher2.preferences.config

import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.SearchBarPosition
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import kotlinx.coroutines.flow.first
import java.util.UUID

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
     * Reads the settings-backed portion of [ConfigState] plus the currently
     * selected transparency scheme ID. Fields of [ConfigState] that are not
     * backed by settings (transparency values, favorites, grid layouts)
     * keep their [ConfigState] defaults and must be filled by their
     * respective repositories.
     */
    suspend fun readState(): SettingsBackedState

    /**
     * Applies [mutations] in a single awaited DataStore update. Mutations
     * that are not backed by settings ([ConfigMutation.SetTransparency],
     * [ConfigMutation.SetFavorites], [ConfigMutation.SetWallpaper]) are
     * ignored here; they are handled by their respective repositories.
     * [ConfigMutation.SetGrid] is split: `columns` and `locked` land here,
     * `layouts` go to the grid repository.
     *
     * Returns [Unit] in the interface so consumers in other modules can fake
     * it (LauncherSettingsData has an internal constructor); the
     * implementation covariantly returns the updated settings data.
     */
    suspend fun apply(mutations: List<ConfigMutation>)

    /**
     * Selects the transparency scheme with [id]. Called after the
     * transparency repository has upserted/selected the scheme.
     */
    suspend fun setTransparenciesId(id: UUID)
}

internal class LauncherConfigSettingsImpl(
    private val dataStore: LauncherDataStore,
) : LauncherConfigSettings {

    override suspend fun readState(): SettingsBackedState {
        val data = dataStore.data.first()
        return SettingsBackedState(
            state = ConfigState(
                themedIcons = data.iconsThemed,
                enforceThemedIcons = data.iconsForceThemed,
                iconPack = data.iconsPack,
                searchBarPosition = if (data.searchBarBottom) {
                    SearchBarPosition.Bottom
                } else {
                    SearchBarPosition.Top
                },
                widgetsEnabled = data.homeScreenWidgets,
                gridColumns = data.homeGridColumns,
                gridLocked = data.homeGridLocked,
            ),
            transparenciesId = data.uiTransparenciesId,
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

    override suspend fun setTransparenciesId(id: UUID) {
        setTransparenciesIdAndReturn(id)
    }

    /**
     * Rich variant of [setTransparenciesId] for in-module consumers/tests:
     * returns the updated settings data.
     */
    suspend fun setTransparenciesIdAndReturn(id: UUID): LauncherSettingsData {
        return dataStore.updateAndAwait { it.copy(uiTransparenciesId = id) }
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

            is ConfigMutation.SetGrid -> this // PR 3: columns and locked

            is ConfigMutation.SetTransparency,
            is ConfigMutation.SetFavorites,
            is ConfigMutation.SetWallpaper,
            -> this
        }
    }

}

/**
 * The settings-backed portion of [ConfigState] plus the ID of the currently
 * selected transparency scheme.
 */
data class SettingsBackedState(
    val state: ConfigState,
    val transparenciesId: UUID,
)

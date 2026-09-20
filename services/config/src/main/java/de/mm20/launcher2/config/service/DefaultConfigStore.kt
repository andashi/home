package de.mm20.launcher2.config.service

import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.BuiltinWidget
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.themes.DefaultThemeId
import de.mm20.launcher2.themes.transparencies.Transparencies
import de.mm20.launcher2.themes.transparencies.TransparenciesRepository
import de.mm20.launcher2.widgets.AppWidget
import de.mm20.launcher2.widgets.AppsWidget
import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import de.mm20.launcher2.config.Profile as ConfigProfile

/**
 * Fork addition (Phase 2, ADR 0003): [ConfigStore] implementation backed by
 * the real launcher repositories.
 *
 * - Settings-backed state/mutations go through [LauncherConfigSettings]
 *   (single awaited DataStore write per [apply] call).
 * - Transparency schemes are resolved/upserted via [TransparenciesRepository]
 *   and then selected via settings.
 * - Root widgets are reconciled via [WidgetRepository.setAwaited]; only the
 *   built-in widget types known to the config format are managed, external
 *   [AppWidget]s are preserved.
 * - Dock favorites are resolved from `{packageName, profile}` pairs via
 *   [AppRepository] + [ProfileResolver] and written with
 *   [SavableSearchableRepository.updateFavoritesAwaited]. User serials never
 *   appear in config state or diagnostics.
 */
class DefaultConfigStore(
    private val settings: LauncherConfigSettings,
    private val transparenciesRepository: TransparenciesRepository,
    private val widgetRepository: WidgetRepository,
    private val searchableRepository: SavableSearchableRepository,
    private val appRepository: AppRepository,
    private val profileResolver: ProfileResolver,
    private val wallpapers: WallpaperStore,
) : ConfigStore {

    override suspend fun readState(): ConfigState {
        val settingsState = settings.readState()
        val transparencies = transparenciesRepository.getOnce(settingsState.transparenciesId)
        val widgets = widgetRepository.get().first()
        val dockFavorites = searchableRepository.get(
            includeTypes = listOf(AppDomain),
            minPinnedLevel = PinnedLevel.ManuallySorted,
            maxPinnedLevel = PinnedLevel.ManuallySorted,
        ).first().mapNotNull { it.toFavorite() }
        val wallpaper = wallpapers.current()

        return settingsState.state.copy(
            transparencyName = transparencies?.name,
            transparencyBackground = transparencies?.background ?: 1f,
            transparencySurface = transparencies?.surface ?: 1f,
            transparencyElevatedSurface = transparencies?.elevatedSurface ?: 1f,
            dockFavorites = dockFavorites,
            widgets = widgets.mapNotNull { it.toBuiltinWidget() },
            wallpaperImage = wallpaper?.image,
            wallpaperTarget = wallpaper?.target,
        )
    }

    override suspend fun apply(mutations: List<ConfigMutation>): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        val settingsMutations = mutations.filter { it.isSettingsBacked }
        if (settingsMutations.isNotEmpty()) {
            try {
                settings.apply(settingsMutations)
            } catch (e: Exception) {
                for (mutation in settingsMutations) {
                    diagnostics += mutation.applyFailed(e)
                }
            }
        }

        for (mutation in mutations) {
            when (mutation) {
                is ConfigMutation.SetTransparency -> try {
                    diagnostics += applyTransparency(mutation)
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetWidgets -> try {
                    diagnostics += applyWidgets(mutation)
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetDockFavorites -> try {
                    diagnostics += applyDockFavorites(mutation)
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetWallpaper -> try {
                    diagnostics += wallpapers.apply(mutation.image, mutation.target)
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                else -> Unit
            }
        }
        return diagnostics
    }

    /**
     * Resolves the target scheme by name (or the currently selected scheme),
     * preserves values the mutation does not mention, upserts and selects it.
     * Built-in schemes are never modified; changing values while a built-in
     * is targeted derives a new user scheme instead. When no name is given
     * and the selected scheme no longer exists (deleted user scheme), the
     * built-in default is the base, so the section still converges.
     */
    private suspend fun applyTransparency(
        mutation: ConfigMutation.SetTransparency,
    ): List<Diagnostic> {
        val name = mutation.name
        val base = if (name != null) {
            transparenciesRepository.findByName(name)
        } else {
            transparenciesRepository.getOnce(settings.readState().transparenciesId)
                ?: transparenciesRepository.getOnce(DefaultThemeId)
        }

        val hasValueChanges = mutation.background != null ||
                mutation.surface != null ||
                mutation.elevatedSurface != null

        val target: Transparencies = when {
            base == null -> Transparencies(
                id = UUID.randomUUID(),
                name = name!!,
                background = mutation.background,
                surface = mutation.surface,
                elevatedSurface = mutation.elevatedSurface,
            )

            base.builtIn && hasValueChanges -> Transparencies(
                id = UUID.randomUUID(),
                name = name ?: base.name,
                background = mutation.background ?: base.background,
                surface = mutation.surface ?: base.surface,
                elevatedSurface = mutation.elevatedSurface ?: base.elevatedSurface,
            )

            base.builtIn -> base

            else -> base.copy(
                name = name ?: base.name,
                background = mutation.background ?: base.background,
                surface = mutation.surface ?: base.surface,
                elevatedSurface = mutation.elevatedSurface ?: base.elevatedSurface,
            )
        }

        if (!target.builtIn) {
            transparenciesRepository.upsert(target)
        }
        settings.setTransparenciesId(target.id)
        return emptyList()
    }

    /**
     * Reconciles the root widget list against the configured built-ins.
     * Unchanged built-ins keep their IDs and configs, removed built-ins are
     * deleted, and external
     * [AppWidget]s are kept (appended in their previous relative order) with
     * a warning instead of being deleted.
     */
    private suspend fun applyWidgets(
        mutation: ConfigMutation.SetWidgets,
    ): List<Diagnostic> {
        val current = widgetRepository.get().first()
        val remaining = current.toMutableList()
        val reconciled = mutableListOf<Widget>()

        for (builtin in mutation.widgets) {
            val existing = remaining.firstOrNull { it.toBuiltinWidget() == builtin }
            if (existing != null) {
                remaining.remove(existing)
                reconciled += existing
            } else {
                reconciled += builtin.newWidget()
            }
        }

        val external = remaining.filterIsInstance<AppWidget>()
        reconciled += external

        widgetRepository.setAwaited(reconciled)

        return external.map {
            Diagnostic(
                Severity.Warning,
                "unsupported-widget",
                "home.widgets.widgets",
                "External app widget ${it.id} is not manageable via config; " +
                        "it was kept and moved after the configured widgets",
            )
        }
    }

    /**
     * Resolves the configured favorites to installed apps and writes them in
     * config order. Unresolvable entries (missing profile, uninstalled app)
     * produce error diagnostics and are skipped. Automatically pinned
     * favorites are outside the config's scope and are preserved.
     */
    private suspend fun applyDockFavorites(
        mutation: ConfigMutation.SetDockFavorites,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val resolved = mutableListOf<SavableSearchable>()

        mutation.favorites.forEachIndexed { index, favorite ->
            val path = "home.dock.favorites[$index]"
            val profileType = when (favorite.profile) {
                ConfigProfile.Personal -> Profile.Type.Personal
                ConfigProfile.Work -> Profile.Type.Work
                ConfigProfile.Private -> Profile.Type.Private
            }
            val profile = profileResolver.getProfile(profileType)
            if (profile == null) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    "profile-unavailable",
                    path,
                    "The ${favorite.profile.name.lowercase()} profile does not exist " +
                            "on this device; favorite '${favorite.packageName}' was skipped",
                )
                return@forEachIndexed
            }
            val app = appRepository.findOne(favorite.packageName, profile.userHandle).first()
            if (app == null) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    "favorite-unavailable",
                    path,
                    "App '${favorite.packageName}' is not installed in the " +
                            "${favorite.profile.name.lowercase()} profile; it was skipped",
                )
                return@forEachIndexed
            }
            resolved += app
        }

        val automatic = searchableRepository.get(
            minPinnedLevel = PinnedLevel.AutomaticallySorted,
            maxPinnedLevel = PinnedLevel.AutomaticallySorted,
        ).first()
        searchableRepository.updateFavoritesAwaited(
            manuallySorted = resolved,
            automaticallySorted = automatic,
        )
        return diagnostics
    }

    private suspend fun SavableSearchable.toFavorite(): Favorite? {
        val app = this as? Application ?: return null
        val profileType = profileResolver.getProfile(app.user)?.type ?: return null
        val configProfile = when (profileType) {
            Profile.Type.Personal -> ConfigProfile.Personal
            Profile.Type.Work -> ConfigProfile.Work
            Profile.Type.Private -> ConfigProfile.Private
        }
        return Favorite(
            packageName = app.componentName.packageName,
            profile = configProfile,
        )
    }

    private fun Widget.toBuiltinWidget(): BuiltinWidget? = when (this) {
        is AppsWidget -> BuiltinWidget.Apps
        else -> null
    }

    private fun BuiltinWidget.newWidget(): Widget = when (this) {
        BuiltinWidget.Apps -> AppsWidget(UUID.randomUUID())
    }

    private fun ConfigMutation.applyFailed(cause: Exception): Diagnostic {
        return Diagnostic(
            Severity.Error,
            "apply-failed",
            section,
            "Failed to apply section '$section': ${cause.message ?: cause.javaClass.simpleName}",
        )
    }

    private companion object {
        /**
         * Domain of [Application] searchables (`LauncherApp.Domain`, which is
         * internal to `:data:applications`).
         */
        const val AppDomain = "app"
    }
}

private val ConfigMutation.isSettingsBacked: Boolean
    get() = when (this) {
        is ConfigMutation.SetIcons,
        is ConfigMutation.SetSearchBarPosition,
        is ConfigMutation.SetDockEnabled,
        is ConfigMutation.SetWidgetsEnabled,
        -> true

        is ConfigMutation.SetTransparency,
        is ConfigMutation.SetWidgets,
        is ConfigMutation.SetDockFavorites,
        is ConfigMutation.SetWallpaper,
        -> false
    }

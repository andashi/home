package de.mm20.launcher2.config

data class ConfigState(
    val themedIcons: Boolean = false,
    val enforceThemedIcons: Boolean = false,
    val iconPack: String? = null,
    val transparencyName: String? = null,
    val transparencyBackground: Float = 1f,
    val transparencySurface: Float = 1f,
    val transparencyElevatedSurface: Float = 1f,
    val searchBarPosition: SearchBarPosition = SearchBarPosition.Top,
    val dockEnabled: Boolean = false,
    val dockFavorites: List<Favorite> = emptyList(),
    val widgetsEnabled: Boolean = false,
    val widgets: List<BuiltinWidget> = emptyList(),
    /**
     * Null when the launcher shows a clock the config format cannot express
     * (a custom app-widget clock). Any configured style then differs from it,
     * so convergence replaces the custom clock instead of assuming a match.
     */
    val clockStyle: ClockStyle? = ClockStyle.Digital1,
    val clockFillHeight: Boolean = false,
    /**
     * The wallpaper image (by upload name) and target currently in effect:
     * applied by a config reload, still the system's current wallpaper and
     * the file unchanged since. Null when no config-managed wallpaper is in
     * effect or the state drifted (user changed it, file replaced), so any
     * configured wallpaper counts as a difference.
     */
    val wallpaperImage: String? = null,
    val wallpaperTarget: WallpaperTarget? = null,
)

sealed class ConfigMutation {
    abstract val section: String

    data class SetIcons(
        val themed: Boolean? = null,
        val enforceThemed: Boolean? = null,
        val pack: String? = null,
    ) : ConfigMutation() {
        override val section = "icons"
    }

    data class SetTransparency(
        val name: String? = null,
        val background: Float? = null,
        val surface: Float? = null,
        val elevatedSurface: Float? = null,
    ) : ConfigMutation() {
        override val section = "appearance.transparency"
    }

    data class SetWallpaper(
        val image: String,
        val target: WallpaperTarget,
    ) : ConfigMutation() {
        override val section = "appearance.wallpaper"
    }

    data class SetSearchBarPosition(
        val position: SearchBarPosition,
    ) : ConfigMutation() {
        override val section = "home.searchBar"
    }

    data class SetDockEnabled(
        val enabled: Boolean,
    ) : ConfigMutation() {
        override val section = "home.dock.enabled"
    }

    data class SetDockFavorites(
        val favorites: List<Favorite>,
    ) : ConfigMutation() {
        override val section = "home.dock.favorites"
    }

    data class SetWidgetsEnabled(
        val enabled: Boolean,
    ) : ConfigMutation() {
        override val section = "home.widgets.enabled"
    }

    data class SetWidgets(
        val widgets: List<BuiltinWidget>,
    ) : ConfigMutation() {
        override val section = "home.widgets.widgets"
    }

    data class SetClock(
        val style: ClockStyle? = null,
        val fillHeight: Boolean? = null,
    ) : ConfigMutation() {
        override val section = "home.clock"
    }
}

object ConfigDiffer {
    fun diff(desired: LauncherConfig, current: ConfigState): List<ConfigMutation> {
        val mutations = mutableListOf<ConfigMutation>()

        desired.icons?.let { icons ->
            val themed = icons.themed?.takeIf { it != current.themedIcons }
            val enforceThemed = icons.enforceThemed?.takeIf { it != current.enforceThemedIcons }
            val pack = icons.pack?.takeIf { it != current.iconPack }
            if (themed != null || enforceThemed != null || pack != null) {
                mutations += ConfigMutation.SetIcons(
                    themed = themed,
                    enforceThemed = enforceThemed,
                    pack = pack,
                )
            }
        }

        desired.appearance?.transparency?.let { transparency ->
            val name = transparency.name?.takeIf { it != current.transparencyName }
            val background = transparency.background?.takeIf { it != current.transparencyBackground }
            val surface = transparency.surface?.takeIf { it != current.transparencySurface }
            val elevated = transparency.elevatedSurface?.takeIf { it != current.transparencyElevatedSurface }
            if (name != null || background != null || surface != null || elevated != null) {
                mutations += ConfigMutation.SetTransparency(
                    name = name,
                    background = background,
                    surface = surface,
                    elevatedSurface = elevated,
                )
            }
        }

        desired.appearance?.wallpaper?.image?.let { image ->
            val target = desired.appearance.wallpaper.target ?: WallpaperTarget.Both
            if (image != current.wallpaperImage || target != current.wallpaperTarget) {
                mutations += ConfigMutation.SetWallpaper(image = image, target = target)
            }
        }

        desired.home?.searchBar?.position?.let { position ->
            if (position != current.searchBarPosition) {
                mutations += ConfigMutation.SetSearchBarPosition(position)
            }
        }

        desired.home?.dock?.let { dock ->
            dock.enabled?.let { enabled ->
                if (enabled != current.dockEnabled) {
                    mutations += ConfigMutation.SetDockEnabled(enabled)
                }
            }
            dock.favorites?.let { favorites ->
                if (favorites != current.dockFavorites) {
                    mutations += ConfigMutation.SetDockFavorites(favorites)
                }
            }
        }

        desired.home?.widgets?.let { widgets ->
            widgets.enabled?.let { enabled ->
                if (enabled != current.widgetsEnabled) {
                    mutations += ConfigMutation.SetWidgetsEnabled(enabled)
                }
            }
            widgets.widgets?.let { list ->
                if (list != current.widgets) {
                    mutations += ConfigMutation.SetWidgets(list)
                }
            }
        }

        desired.home?.clock?.let { clock ->
            val style = clock.style?.takeIf { it != current.clockStyle }
            val fillHeight = clock.fillHeight?.takeIf { it != current.clockFillHeight }
            if (style != null || fillHeight != null) {
                mutations += ConfigMutation.SetClock(style = style, fillHeight = fillHeight)
            }
        }

        return mutations
    }
}

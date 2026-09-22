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
    /** The manually pinned apps, in order: `home.favorites`. */
    val favorites: List<Favorite> = emptyList(),
    val widgetsEnabled: Boolean = false,
    val gridColumns: Int = 4,
    val gridLocked: Boolean = false,
    /**
     * The layouts as stored, every item with full geometry. Keyed like
     * `home.grid.layouts`; a layout with no items is an empty list, not an
     * absent key, so the read-back always shows both.
     */
    val gridLayouts: Map<String, GridLayoutConfig> = emptyMap(),
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

    data class SetFavorites(
        val favorites: List<Favorite>,
    ) : ConfigMutation() {
        override val section = "home.favorites"
    }

    data class SetWidgetsEnabled(
        val enabled: Boolean,
    ) : ConfigMutation() {
        override val section = "home.widgets.enabled"
    }

    /**
     * `home.grid`. [columns] and [locked] are settings-backed; [layouts]
     * goes to the grid repository through the layout engine. A null field
     * is unmanaged; a layout key that is absent from [layouts] is untouched.
     */
    data class SetGrid(
        val columns: Int? = null,
        val locked: Boolean? = null,
        val layouts: Map<String, GridLayoutConfig>? = null,
    ) : ConfigMutation() {
        override val section = "home.grid"
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

        desired.home?.favorites?.let { favorites ->
            TODO("PR 3: SetFavorites when the list differs")
        }

        desired.home?.widgets?.enabled?.let { enabled ->
            if (enabled != current.widgetsEnabled) {
                mutations += ConfigMutation.SetWidgetsEnabled(enabled)
            }
        }

        desired.home?.grid?.let { grid ->
            TODO("PR 3: SetGrid when columns, locked or a named layout differ")
        }

        return mutations
    }
}

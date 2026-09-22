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
            if (favorites != current.favorites) {
                mutations += ConfigMutation.SetFavorites(favorites)
            }
        }

        desired.home?.widgets?.enabled?.let { enabled ->
            if (enabled != current.widgetsEnabled) {
                mutations += ConfigMutation.SetWidgetsEnabled(enabled)
            }
        }

        desired.home?.grid?.let { grid ->
            val columns = grid.columns?.takeIf { it != current.gridColumns }
            val locked = grid.locked?.takeIf { it != current.gridLocked }
            val layouts = grid.layouts?.filter { (key, layout) ->
                !layout.matches(current.gridLayouts[key])
            }?.takeIf { it.isNotEmpty() }
            if (columns != null || locked != null || layouts != null) {
                mutations += ConfigMutation.SetGrid(columns = columns, locked = locked, layouts = layouts)
            }
        }

        return mutations
    }
}

/**
 * Whether a configured layout is already what the state holds: the same
 * items in the same order (write-back keeps the file's order, so order is
 * part of the layout), each one [GridItemConfig.matches] its stored twin.
 */
internal fun GridLayoutConfig.matches(current: GridLayoutConfig?): Boolean {
    if (current == null || current.items.size != items.size) return false
    return items.zip(current.items).all { (desired, stored) -> desired.matches(stored) }
}

/**
 * A configured item against its stored twin. Identity fields must be equal;
 * every optional field is compared only when the config sets it - absent
 * means unmanaged, as everywhere in the contract. That is what lets a file
 * that omits geometry (placed once by the launcher, D5) stay a no-op on the
 * next reload, before or without write-back.
 */
internal fun GridItemConfig.matches(stored: GridItemConfig): Boolean {
    if (id != stored.id || widget != stored.widget) return false
    fun <T> same(desired: T?, current: T?) = desired == null || desired == current
    // A position is x and y together; a lone coordinate cannot anchor the
    // item, so the store places it freely and it is not compared here.
    val positionSame = !hasPosition || (x == stored.x && y == stored.y)
    return positionSame && same(w, stored.w) && same(h, stored.h) &&
            same(profile, stored.profile) &&
            same(borderless, stored.borderless) && same(background, stored.background) &&
            same(themeColors, stored.themeColors)
}

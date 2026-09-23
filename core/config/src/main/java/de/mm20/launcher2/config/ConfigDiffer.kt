package de.mm20.launcher2.config

data class ConfigState(
    val themedIcons: Boolean = true,
    val enforceThemedIcons: Boolean = false,
    val iconPack: String? = null,
    val glassBlur: Float = GlassDefaults.Blur,
    val glassTint: Float = GlassDefaults.Tint,
    val glassRadius: Float = GlassDefaults.Radius,
    val glassContrast: GlassContrast = GlassDefaults.Contrast,
    val glassWallpaperBlur: Boolean = GlassDefaults.WallpaperBlur,
    val glassSearchWallpaperBlur: Boolean = GlassDefaults.SearchWallpaperBlur,
    val searchBarPosition: SearchBarPosition = SearchBarPosition.Top,
    /** `search` (#91). */
    val search: SearchState = SearchState(),
    /** The manually pinned apps, in order: `home.favorites`. */
    val favorites: List<Favorite> = emptyList(),
    val widgetsEnabled: Boolean = false,
    val gridColumns: Int = 4,
    val gridLocked: Boolean = false,
    val gridLabels: Boolean = GlassDefaults.Labels,
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

/** What `search` reads back as: every key, at its default until a config sets it (#91). */
data class SearchState(
    val favorites: Boolean = SearchDefaults.Favorites,
    val allApps: Boolean = SearchDefaults.AllApps,
    val layout: SearchResultLayout = SearchDefaults.Layout,
    val labels: Boolean = SearchDefaults.Labels,
    val contacts: Boolean = SearchDefaults.Contacts,
    val shortcuts: Boolean = SearchDefaults.Shortcuts,
    val filterBar: Boolean = SearchDefaults.FilterBar,
    val openKeyboard: Boolean = SearchDefaults.OpenKeyboard,
    val launchOnEnter: Boolean = SearchDefaults.LaunchOnEnter,
    val reversed: Boolean = SearchDefaults.Reversed,
    val hiddenItemsButton: Boolean = SearchDefaults.HiddenItemsButton,
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

    data class SetGlass(
        val blur: Float? = null,
        val tint: Float? = null,
        val radius: Float? = null,
        val contrast: GlassContrast? = null,
        val wallpaperBlur: Boolean? = null,
        val searchWallpaperBlur: Boolean? = null,
    ) : ConfigMutation() {
        override val section = "appearance.glass"
    }

    data class SetWallpaper(
        val image: String,
        val target: WallpaperTarget,
    ) : ConfigMutation() {
        override val section = "appearance.wallpaper"
    }

    /** `search` (#91): the keys that differ from the state; null is unchanged. */
    data class SetSearch(val search: SearchConfig) : ConfigMutation() {
        override val section = "search"
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
     * `home.grid`. [columns], [locked] and [labels] are settings-backed; [layouts]
     * goes to the grid repository through the layout engine. A null field
     * is unmanaged; a layout key that is absent from [layouts] is untouched.
     */
    data class SetGrid(
        val columns: Int? = null,
        val locked: Boolean? = null,
        val layouts: Map<String, GridLayoutConfig>? = null,
        val labels: Boolean? = null,
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

        desired.appearance?.glass?.let { glass ->
            val blur = glass.blur?.takeIf { it != current.glassBlur }
            val tint = glass.tint?.takeIf { it != current.glassTint }
            val radius = glass.radius?.takeIf { it != current.glassRadius }
            val contrast = glass.contrast?.takeIf { it != current.glassContrast }
            val wallpaperBlur = glass.wallpaperBlur?.takeIf { it != current.glassWallpaperBlur }
            val searchWallpaperBlur = glass.searchWallpaperBlur?.takeIf { it != current.glassSearchWallpaperBlur }
            if (blur != null || tint != null || radius != null || contrast != null ||
                wallpaperBlur != null || searchWallpaperBlur != null
            ) {
                mutations += ConfigMutation.SetGlass(
                    blur = blur,
                    tint = tint,
                    radius = radius,
                    contrast = contrast,
                    wallpaperBlur = wallpaperBlur,
                    searchWallpaperBlur = searchWallpaperBlur,
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
            val labels = grid.labels?.takeIf { it != current.gridLabels }
            val layouts = grid.layouts?.filter { (key, layout) ->
                !layout.matches(current.gridLayouts[key])
            }?.takeIf { it.isNotEmpty() }
            if (columns != null || locked != null || layouts != null || labels != null) {
                mutations += ConfigMutation.SetGrid(
                    columns = columns,
                    locked = locked,
                    layouts = layouts,
                    labels = labels,
                )
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

package de.mm20.launcher2.config

data class ConfigState(
    val themedIcons: Boolean = true,
    val enforceThemedIcons: Boolean = false,
    val iconPack: String? = null,
    /** `icons.size` in dp (#3 slice 1): search, the dock and the pickers. */
    val iconSize: Int = IconDefaults.Size,
    val adaptifyIcons: Boolean = IconDefaults.Adaptify,
    /** `icons.badges` (#3 slice 1). */
    val badgeNotifications: Boolean = IconDefaults.Badges,
    val badgeShortcuts: Boolean = IconDefaults.Badges,
    val badgeSuspendedApps: Boolean = IconDefaults.Badges,
    val glassBlur: Float = GlassDefaults.Blur,
    val glassTint: Float = GlassDefaults.Tint,
    val glassRadius: Float = GlassDefaults.Radius,
    val glassContrast: GlassContrast = GlassDefaults.Contrast,
    val glassWallpaperBlur: Boolean = GlassDefaults.WallpaperBlur,
    val glassSearchWallpaperBlur: Boolean = GlassDefaults.SearchWallpaperBlur,
    /** `appearance.theme.mode` (#3 slice 3). */
    val themeMode: ThemeMode = ThemeMode.System,
    /**
     * `appearance.theme.colors`: the built-in scheme in effect, or null when
     * the device uses a colour scheme a person made, which the file cannot
     * name. The read-back then leaves `colors` out, and write-back keeps the
     * file's text.
     */
    val themeColors: ThemeColors? = ThemeColors.System,
    /** `appearance.theme.shapes`: the built-in set in effect, or null for one a person made (as [themeColors]). */
    val themeShapes: ThemeShapes? = ThemeShapes.Default,
    /** `appearance.theme.typography`: the built-in typography in effect, or null for one a person made. */
    val themeTypography: ThemeTypography? = ThemeTypography.GoogleSans,
    /** `appearance.theme.colorSource` (#229): the system palette by default. */
    val themeColorSource: ThemeColorSource = ThemeColorSource.System,
    val searchBarPosition: SearchBarPosition = SearchBarPosition.Top,
    /** `home.searchBar.fixed` (#3 slice 1). */
    val searchBarFixed: Boolean = false,
    /** `appearance.systemBars` (#3 slice 1). */
    val statusBarHidden: Boolean = false,
    val statusBarIcons: SystemBarIcons = SystemBarIcons.Auto,
    val navigationBarHidden: Boolean = false,
    val navigationBarIcons: SystemBarIcons = SystemBarIcons.Auto,
    /** `home.lockRotation` (#3 slice 1). */
    val rotationLocked: Boolean = false,
    /** `appearance.dimWallpaper` (#229). */
    val wallpaperDimmed: Boolean = false,
    /** `search` (#91). */
    val search: SearchState = SearchState(),
    /** The manually pinned apps, in order: `home.favorites`. */
    val favorites: List<Favorite> = emptyList(),
    /** Apps with a name or a visibility of their own (#3 slice 4): `apps`. */
    val apps: List<AppConfig> = emptyList(),
    /** The tags the apps carry, and their icons (#3 slice 4): `tags`. */
    val tags: List<TagConfig> = emptyList(),
    /** The search actions in effect, in order: `search.actions` (#106); null when unknown. */
    val searchActions: List<SearchActionConfig>? = null,
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
     * Whether the grid has been given its first content (HomeGridInitFlag).
     * Until it has, a layout the file names is applied even when it equals
     * the stored one, so an empty layout counts as content and the default
     * favorites row does not overwrite it (#92).
     */
    val gridInitialized: Boolean = true,
    /**
     * Layouts not settled on this device: one of their widgets has no
     * provider here, so the item is kept as written and reported. Each reload
     * applies such a layout again, as it does one before the grid's first
     * content, so every report says what is missing - not only the first,
     * after which the stored layout matched the file and nothing looked
     * again (found on the device for #213).
     */
    val unsettledGridLayouts: Set<String> = emptySet(),
    /**
     * The wallpaper image (by upload name) and target currently in effect:
     * applied by a config reload, still the system's current wallpaper and
     * the file unchanged since. Null when no config-managed wallpaper is in
     * effect or the state drifted (user changed it, file replaced), so any
     * configured wallpaper counts as a difference.
     */
    val wallpaperImage: String? = null,
    val wallpaperTarget: WallpaperTarget? = null,
    /**
     * `gestures` (#3 slice 2), every gesture. Null where the device runs
     * something the file cannot name - a shortcut, an app no longer installed:
     * the read-back leaves it out, and write-back keeps the file's value.
     */
    val gestures: Map<Gesture, GestureConfig?> = GestureDefaults.All,
    /** `search.shortcutsExcluded` (#229): read by the store, which knows the profiles' serials. */
    val shortcutsExcluded: List<Favorite> = emptyList(),
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
    /** Where the bar sits in search (#107); [InSearchBarPosition.Follow] by default, read back always (#3 D6). */
    val barPosition: InSearchBarPosition = InSearchBarPosition.Follow,
    val listIcons: Boolean = SearchDefaults.ListIcons,
    val appDetails: Boolean = SearchDefaults.AppDetails,
    val contactsCallOnTap: Boolean = SearchDefaults.ContactsCallOnTap,
    val frequentlyUsed: Boolean = SearchDefaults.FrequentlyUsed,
    val frequentlyUsedRows: Int = SearchDefaults.FrequentlyUsedRows,
    val favoritesEditButton: Boolean = SearchDefaults.FavoritesEditButton,
    val compactTags: Boolean = SearchDefaults.CompactTags,
    val transliterator: String = SearchDefaults.Transliterator,
    /** `search.defaultFilter` (#229): the launcher's own default, every category and no hidden items. */
    val defaultFilter: List<SearchFilterItem> = SearchFilterItem.entries.filter { it.isCategory },
    /** `search.filterBarItems` (#229): the launcher's own bar, all four in this order. */
    val filterBarItems: List<SearchFilterItem> = SearchFilterItem.entries,
)

sealed class ConfigMutation {
    abstract val section: String

    data class SetIcons(
        val themed: Boolean? = null,
        val enforceThemed: Boolean? = null,
        val pack: String? = null,
        val size: Int? = null,
        val adaptify: Boolean? = null,
        val badgeNotifications: Boolean? = null,
        val badgeShortcuts: Boolean? = null,
        val badgeSuspendedApps: Boolean? = null,
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

    /** `appearance.theme` (#3 slice 3): the keys that differ from the state; null is unchanged. */
    data class SetTheme(
        val mode: ThemeMode? = null,
        val colors: ThemeColors? = null,
        val shapes: ThemeShapes? = null,
        val typography: ThemeTypography? = null,
        val colorSource: ThemeColorSource? = null,
    ) : ConfigMutation() {
        override val section = "appearance.theme"
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

    /** `search.shortcutsExcluded` (#229): the whole list; the store applies it. */
    data class SetShortcutsExcluded(
        val shortcutsExcluded: List<Favorite>,
    ) : ConfigMutation() {
        override val section = "search.shortcutsExcluded"
    }

    data class SetFavorites(
        val favorites: List<Favorite>,
    ) : ConfigMutation() {
        override val section = "home.favorites"
    }

    /** `apps` (#3 slice 4): the whole desired list, as the file writes it: its order names its diagnostics. */
    data class SetApps(
        val apps: List<AppConfig>,
    ) : ConfigMutation() {
        override val section = "apps"
    }

    /** `tags` (#3 slice 4): the whole desired list, as the file writes it: its order names its diagnostics. */
    data class SetTags(
        val tags: List<TagConfig>,
    ) : ConfigMutation() {
        override val section = "tags"
    }

    data class SetSearchActions(
        val actions: List<SearchActionConfig>,
    ) : ConfigMutation() {
        override val section = "search.actions"
    }

    /** `home.searchBar.fixed`; the same section as the position, a mutation of its own. */
    data class SetSearchBarFixed(
        val fixed: Boolean,
    ) : ConfigMutation() {
        override val section = "home.searchBar"
    }

    data class SetSystemBars(
        val statusHidden: Boolean? = null,
        val statusIcons: SystemBarIcons? = null,
        val navigationHidden: Boolean? = null,
        val navigationIcons: SystemBarIcons? = null,
    ) : ConfigMutation() {
        override val section = "appearance.systemBars"
    }

    /** `appearance.dimWallpaper` (#229). */
    data class SetWallpaperDim(
        val dimmed: Boolean,
    ) : ConfigMutation() {
        override val section = "appearance.dimWallpaper"
    }

    data class SetRotationLock(
        val locked: Boolean,
    ) : ConfigMutation() {
        override val section = "home.lockRotation"
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

    /** `gestures` (#3 slice 2): the gestures that differ from the device. */
    data class SetGestures(
        val gestures: Map<Gesture, GestureConfig>,
    ) : ConfigMutation() {
        override val section = "gestures"
    }
}

object ConfigDiffer {
    fun diff(desired: LauncherConfig, current: ConfigState): List<ConfigMutation> {
        val mutations = mutableListOf<ConfigMutation>()

        desired.icons?.let { icons ->
            val themed = icons.themed?.takeIf { it != current.themedIcons }
            val enforceThemed = icons.enforceThemed?.takeIf { it != current.enforceThemedIcons }
            val pack = icons.pack?.takeIf { it != current.iconPack }
            val changed = ConfigMutation.SetIcons(
                themed = themed,
                enforceThemed = enforceThemed,
                pack = pack,
                size = icons.size?.takeIf { it != current.iconSize },
                adaptify = icons.adaptify?.takeIf { it != current.adaptifyIcons },
                badgeNotifications = icons.badges?.notifications?.takeIf { it != current.badgeNotifications },
                badgeShortcuts = icons.badges?.shortcuts?.takeIf { it != current.badgeShortcuts },
                badgeSuspendedApps = icons.badges?.suspendedApps?.takeIf { it != current.badgeSuspendedApps },
            )
            if (changed != ConfigMutation.SetIcons()) mutations += changed
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

        desired.appearance?.theme?.let { theme ->
            val mode = theme.mode?.takeIf { it != current.themeMode }
            // A custom scheme on the device (null) differs from every slug.
            val colors = theme.colors?.takeIf { it != current.themeColors }
            // As the colours: a set a person made (null) differs from every slug.
            val shapes = theme.shapes?.takeIf { it != current.themeShapes }
            val typography = theme.typography?.takeIf { it != current.themeTypography }
            val colorSource = theme.colorSource?.takeIf { it != current.themeColorSource }
            if (mode != null || colors != null || shapes != null || typography != null || colorSource != null) {
                mutations += ConfigMutation.SetTheme(
                    mode = mode, colors = colors, shapes = shapes, typography = typography, colorSource = colorSource,
                )
            }
        }

        desired.search?.let { search ->
            val current = current.search
            val changed = SearchConfig(
                favorites = search.favorites?.takeIf { it != current.favorites },
                allApps = search.allApps?.takeIf { it != current.allApps },
                layout = search.layout?.takeIf { it != current.layout },
                labels = search.labels?.takeIf { it != current.labels },
                contacts = search.contacts?.takeIf { it != current.contacts },
                shortcuts = search.shortcuts?.takeIf { it != current.shortcuts },
                filterBar = search.filterBar?.takeIf { it != current.filterBar },
                openKeyboard = search.openKeyboard?.takeIf { it != current.openKeyboard },
                launchOnEnter = search.launchOnEnter?.takeIf { it != current.launchOnEnter },
                reversed = search.reversed?.takeIf { it != current.reversed },
                hiddenItemsButton = search.hiddenItemsButton?.takeIf { it != current.hiddenItemsButton },
                barPosition = search.barPosition?.takeIf { it != current.barPosition },
                listIcons = search.listIcons?.takeIf { it != current.listIcons },
                appDetails = search.appDetails?.takeIf { it != current.appDetails },
                contactsCallOnTap = search.contactsCallOnTap?.takeIf { it != current.contactsCallOnTap },
                frequentlyUsed = search.frequentlyUsed?.takeIf { it != current.frequentlyUsed },
                frequentlyUsedRows = search.frequentlyUsedRows?.takeIf { it != current.frequentlyUsedRows },
                favoritesEditButton = search.favoritesEditButton?.takeIf { it != current.favoritesEditButton },
                compactTags = search.compactTags?.takeIf { it != current.compactTags },
                transliterator = search.transliterator?.takeIf { it != current.transliterator },
                // A set: order is no difference. The bar is a list: order is.
                defaultFilter = search.defaultFilter?.takeIf { it.toSet() != current.defaultFilter.toSet() },
                filterBarItems = search.filterBarItems?.takeIf { it != current.filterBarItems },
            )
            if (changed != SearchConfig()) mutations += ConfigMutation.SetSearch(changed)
        }

        // As a set: order is no difference.
        desired.search?.shortcutsExcluded?.let { excluded ->
            if (excluded.toSet() != current.shortcutsExcluded.toSet()) {
                mutations += ConfigMutation.SetShortcutsExcluded(excluded)
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

        desired.search?.actions?.let { actions ->
            // The default encoding written or not is the same action (#106).
            if (actions.normalized() != current.searchActions?.normalized()) {
                mutations += ConfigMutation.SetSearchActions(actions)
            }
        }

        desired.home?.favorites?.let { favorites ->
            if (favorites != current.favorites) {
                mutations += ConfigMutation.SetFavorites(favorites)
            }
        }

        // The whole list, compared as customizations: order, and entries that
        // ask only for defaults, are no difference (#3 slice 4). The mutation
        // carries the file's list as written: the store names a diagnostic by
        // the entry's index, and the first of two entries that are one app is
        // the one that applies (review on #224).
        desired.apps?.let { apps ->
            if (apps.normalizedApps() != current.apps.normalizedApps()) {
                mutations += ConfigMutation.SetApps(apps)
            }
        }

        // As a set: the order of the tags and of a tag's apps is no difference.
        // Carried as written, as the apps are.
        desired.tags?.let { tags ->
            if (tags.normalizedTags() != current.tags.normalizedTags()) {
                mutations += ConfigMutation.SetTags(tags)
            }
        }

        desired.home?.searchBar?.fixed?.let { fixed ->
            if (fixed != current.searchBarFixed) mutations += ConfigMutation.SetSearchBarFixed(fixed)
        }

        desired.appearance?.systemBars?.let { bars ->
            val changed = ConfigMutation.SetSystemBars(
                statusHidden = bars.statusBar?.hidden?.takeIf { it != current.statusBarHidden },
                statusIcons = bars.statusBar?.icons?.takeIf { it != current.statusBarIcons },
                navigationHidden = bars.navigationBar?.hidden?.takeIf { it != current.navigationBarHidden },
                navigationIcons = bars.navigationBar?.icons?.takeIf { it != current.navigationBarIcons },
            )
            if (changed != ConfigMutation.SetSystemBars()) mutations += changed
        }

        desired.home?.lockRotation?.let { locked ->
            if (locked != current.rotationLocked) mutations += ConfigMutation.SetRotationLock(locked)
        }

        desired.appearance?.dimWallpaper?.let { dimmed ->
            if (dimmed != current.wallpaperDimmed) mutations += ConfigMutation.SetWallpaperDim(dimmed)
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
                !current.gridInitialized || key in current.unsettledGridLayouts ||
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

        desired.gestures?.let { gestures ->
            // A gesture the device runs but the file cannot name (null) differs.
            val changed = gestures.byGesture().filter { (gesture, value) -> value != current.gestures[gesture] }
            if (changed.isNotEmpty()) mutations += ConfigMutation.SetGestures(changed)
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
 * A configured item against its stored twin, by the two rules the contract
 * has for an item's fields - not one:
 *
 * - **Geometry and profile: absent is unmanaged.** Compared only when the
 *   config sets them. That is what lets a file that omits geometry (placed
 *   once by the launcher, D5) stay a no-op on the next reload.
 * - **The options: absent is the default** ([GridItemConfig.OptionDefaults],
 *   ADR 0002) - the one place in the contract where it is. So an option the
 *   file leaves out is compared as its default, and a stored one away from
 *   it is a difference the next reload resets (review on #231: it was
 *   compared as unmanaged, and reset only when another change made the grid
 *   apply). A stored side without the option is its default too.
 *
 * Resetting cannot overwrite a person's change: nothing on the device writes
 * an option (HomeGridRepository has no setter; the grid only moves, binds,
 * replaces the provider of and deletes items). Were one added, write-back
 * already puts a changed option into the file first (WriteBackPlanTest, "an
 * option the device changed is written").
 *
 * Two rules, not one `same` for everything: merging them back is the defect.
 */
internal fun GridItemConfig.matches(stored: GridItemConfig): Boolean {
    if (id != stored.id || widget != stored.widget) return false
    fun <T> same(desired: T?, current: T?) = desired == null || desired == current
    // A position is x and y together; a lone coordinate cannot anchor the
    // item, so the store places it freely and it is not compared here.
    val positionSame = !hasPosition || (x == stored.x && y == stored.y)
    val optionsSame = GridItemConfig.OptionDefaults.all { (option, default) ->
        (options[option] ?: default) == (stored.options[option] ?: default)
    }
    return positionSame && same(w, stored.w) && same(h, stored.h) &&
            same(profile, stored.profile) && optionsSame
}

/**
 * Each action as the store keeps it (review on #116): the default encoding
 * written out, and the fields a type ignores left out, so a file with a
 * warning-only extra field still converges instead of rewriting every reload.
 */
private fun List<SearchActionConfig>.normalized(): List<SearchActionConfig> = map {
    when (it.type) {
        SearchActionTypes.Url -> it.copy(encoding = it.encoding ?: SearchActionTypes.DefaultEncoding)
        SearchActionTypes.App -> SearchActionConfig(it.type, it.label, packageName = it.packageName)
        SearchActionTypes.Intent -> SearchActionConfig(it.type, it.label)
        else -> SearchActionConfig(it.type)
    }
}

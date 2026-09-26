package de.mm20.launcher2.config

/**
 * Fork addition (Phase 2, ADR 0003): maps the effective launcher state back
 * into the public [LauncherConfig] schema. This is the read-back half of the
 * convergence loop: the state provider serves
 * `ConfigParser.json.encodeToString(LauncherConfig.serializer(), state.toLauncherConfig())`,
 * so a provisioning script can push a config and assert equality against the
 * same schema it writes.
 *
 * Unlike a hand-written config document, the mapped config is fully populated
 * (every section present, no null fields except genuinely unset values like
 * the icon pack). `appearance.glass` is always complete, defaults filled in,
 * so a host compares field by field without knowing them. The one optional section is
 * `appearance.wallpaper`: present only while a config-managed wallpaper is in
 * effect, absent otherwise. `home.grid` carries every item with its full
 * geometry, which is what makes the read-back a document one can paste into
 * the dotfiles (D3).
 */
fun ConfigState.toLauncherConfig(): LauncherConfig {
    return LauncherConfig(
        schemaVersion = ConfigMigrations.currentSchemaVersion,
        // Always complete, so a host compares it key by key (#91).
        search = SearchConfig(
            favorites = search.favorites,
            allApps = search.allApps,
            layout = search.layout,
            labels = search.labels,
            contacts = search.contacts,
            shortcuts = search.shortcuts,
            filterBar = search.filterBar,
            openKeyboard = search.openKeyboard,
            launchOnEnter = search.launchOnEnter,
            reversed = search.reversed,
            hiddenItemsButton = search.hiddenItemsButton,
            // Following the home bar is a value too (#3 D6), so `search` stays complete.
            barPosition = search.barPosition,
            // The actions in effect (#106).
            actions = searchActions,
            listIcons = search.listIcons,
            appDetails = search.appDetails,
            contactsCallOnTap = search.contactsCallOnTap,
        ),
        icons = IconsConfig(
            themed = themedIcons,
            enforceThemed = enforceThemedIcons,
            pack = iconPack,
            size = iconSize,
            adaptify = adaptifyIcons,
            // Complete, like glass (#3 slice 1).
            badges = IconBadgesConfig(
                notifications = badgeNotifications,
                shortcuts = badgeShortcuts,
                suspendedApps = badgeSuspendedApps,
            ),
        ),
        appearance = AppearanceConfig(
            glass = GlassConfig(
                blur = glassBlur,
                tint = glassTint,
                radius = glassRadius,
                contrast = glassContrast,
                wallpaperBlur = glassWallpaperBlur,
                searchWallpaperBlur = glassSearchWallpaperBlur,
            ),
            // Only while a managed wallpaper is in effect; a generated config
            // without the key must compare equal to the read-back.
            wallpaper = wallpaperImage?.let { WallpaperConfig(image = it, target = wallpaperTarget) },
        ),
        home = HomeConfig(
            searchBar = SearchBarConfig(position = searchBarPosition),
            favorites = favorites,
            widgets = WidgetsConfig(enabled = widgetsEnabled),
            grid = GridConfig(
                columns = gridColumns,
                locked = gridLocked,
                layouts = gridLayouts,
                labels = gridLabels,
            ),
        ),
    )
}

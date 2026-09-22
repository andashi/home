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
 * the icon pack or transparency scheme name). The one optional section is
 * `appearance.wallpaper`: present only while a config-managed wallpaper is in
 * effect, absent otherwise. `home.grid` carries every item with its full
 * geometry, which is what makes the read-back a document one can paste into
 * the dotfiles (D3).
 */
fun ConfigState.toLauncherConfig(): LauncherConfig {
    return LauncherConfig(
        schemaVersion = ConfigMigrations.currentSchemaVersion,
        icons = IconsConfig(
            themed = themedIcons,
            enforceThemed = enforceThemedIcons,
            pack = iconPack,
        ),
        appearance = AppearanceConfig(
            transparency = TransparencyConfig(
                name = transparencyName,
                background = transparencyBackground,
                surface = transparencySurface,
                elevatedSurface = transparencyElevatedSurface,
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
            ),
        ),
    )
}

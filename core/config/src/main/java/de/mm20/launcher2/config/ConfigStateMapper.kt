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
 * the icon pack or transparency scheme name).
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
            wallpaper = WallpaperConfig(image = wallpaperImage, target = wallpaperTarget),
        ),
        home = HomeConfig(
            searchBar = SearchBarConfig(position = searchBarPosition),
            dock = DockConfig(enabled = dockEnabled, favorites = dockFavorites),
            widgets = WidgetsConfig(enabled = widgetsEnabled, widgets = widgets),
            clock = ClockConfig(style = clockStyle, fillHeight = clockFillHeight),
        ),
    )
}

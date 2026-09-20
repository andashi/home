package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDifferTest {

    private val baseState = ConfigState(
        themedIcons = true,
        enforceThemedIcons = true,
        iconPack = "app.lawnchair.lawnicons",
        transparencyName = "liquid-glass",
        transparencyBackground = 0.31f,
        transparencySurface = 0.31f,
        transparencyElevatedSurface = 0.31f,
        searchBarPosition = SearchBarPosition.Bottom,
        dockEnabled = true,
        dockFavorites = listOf(Favorite("com.example.dialer", Profile.Personal)),
        widgetsEnabled = true,
        widgets = listOf(BuiltinWidget.Apps),
        wallpaperImage = "home.jpg",
        wallpaperTarget = WallpaperTarget.Both,
    )

    private val matchingConfig = LauncherConfig(
        schemaVersion = 1,
        icons = IconsConfig(
            themed = true,
            enforceThemed = true,
            pack = "app.lawnchair.lawnicons",
        ),
        appearance = AppearanceConfig(
            transparency = TransparencyConfig(
                name = "liquid-glass",
                background = 0.31f,
                surface = 0.31f,
                elevatedSurface = 0.31f,
            ),
            wallpaper = WallpaperConfig(image = "home.jpg", target = WallpaperTarget.Both),
        ),
        home = HomeConfig(
            searchBar = SearchBarConfig(SearchBarPosition.Bottom),
            dock = DockConfig(
                enabled = true,
                favorites = listOf(Favorite("com.example.dialer", Profile.Personal)),
            ),
            widgets = WidgetsConfig(
                enabled = true,
                widgets = listOf(BuiltinWidget.Apps),
            ),
        ),
    )

    @Test
    fun `wallpaper differs by image or target, target defaults to both`() {
        val other = matchingConfig.copy(
            appearance = AppearanceConfig(wallpaper = WallpaperConfig(image = "other.jpg"))
        )
        assertEquals(
            listOf(ConfigMutation.SetWallpaper("other.jpg", WallpaperTarget.Both)),
            ConfigDiffer.diff(other, baseState),
        )

        val lockOnly = matchingConfig.copy(
            appearance = AppearanceConfig(wallpaper = WallpaperConfig("home.jpg", WallpaperTarget.Lock))
        )
        assertEquals(
            listOf(ConfigMutation.SetWallpaper("home.jpg", WallpaperTarget.Lock)),
            ConfigDiffer.diff(lockOnly, baseState),
        )
    }

    @Test
    fun `drifted wallpaper state reapplies the configured image`() {
        val drifted = baseState.copy(wallpaperImage = null, wallpaperTarget = null)
        assertEquals(
            listOf(ConfigMutation.SetWallpaper("home.jpg", WallpaperTarget.Both)),
            ConfigDiffer.diff(matchingConfig, drifted),
        )
    }

    @Test
    fun `emits no mutations for equal state`() {
        val mutations = ConfigDiffer.diff(matchingConfig, baseState)

        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `empty desired config produces no mutations`() {
        val mutations = ConfigDiffer.diff(LauncherConfig(schemaVersion = 1), baseState)

        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `absent desired sections produce no mutations`() {
        val desired = LauncherConfig(
            schemaVersion = 1,
            icons = IconsConfig(themed = false),
        )

        val mutations = ConfigDiffer.diff(desired, baseState)

        assertEquals(
            listOf(ConfigMutation.SetIcons(themed = false)),
            mutations,
        )
    }

    @Test
    fun `absent desired fields within a section produce no mutation for those fields`() {
        val desired = LauncherConfig(
            schemaVersion = 1,
            icons = IconsConfig(pack = "com.other.pack"),
        )

        val mutations = ConfigDiffer.diff(desired, baseState)

        assertEquals(
            listOf(ConfigMutation.SetIcons(pack = "com.other.pack")),
            mutations,
        )
    }

    @Test
    fun `emits deterministic mutations in stable order`() {
        val desired = LauncherConfig(
            schemaVersion = 1,
            icons = IconsConfig(themed = false),
            appearance = AppearanceConfig(
                transparency = TransparencyConfig(background = 0.5f)
            ),
            home = HomeConfig(
                searchBar = SearchBarConfig(SearchBarPosition.Top),
                dock = DockConfig(
                    enabled = false,
                    favorites = listOf(Favorite("com.example.mail", Profile.Work)),
                ),
                widgets = WidgetsConfig(
                    enabled = false,
                    widgets = emptyList(),
                ),
            ),
        )

        val mutations = ConfigDiffer.diff(desired, baseState)

        assertEquals(
            listOf(
                "icons",
                "appearance.transparency",
                "home.searchBar",
                "home.dock.enabled",
                "home.dock.favorites",
                "home.widgets.enabled",
                "home.widgets.widgets",
            ),
            mutations.map { it.section },
        )
        assertEquals(
            listOf(
                ConfigMutation.SetIcons(themed = false),
                ConfigMutation.SetTransparency(background = 0.5f),
                ConfigMutation.SetSearchBarPosition(SearchBarPosition.Top),
                ConfigMutation.SetDockEnabled(false),
                ConfigMutation.SetDockFavorites(
                    listOf(Favorite("com.example.mail", Profile.Work))
                ),
                ConfigMutation.SetWidgetsEnabled(false),
                ConfigMutation.SetWidgets(emptyList()),
            ),
            mutations,
        )
    }

    @Test
    fun `reordered favorites list is a mutation`() {
        val state = baseState.copy(
            dockFavorites = listOf(
                Favorite("com.example.a"),
                Favorite("com.example.b"),
            )
        )
        val desired = LauncherConfig(
            schemaVersion = 1,
            home = HomeConfig(
                dock = DockConfig(
                    favorites = listOf(
                        Favorite("com.example.b"),
                        Favorite("com.example.a"),
                    )
                )
            ),
        )

        val mutations = ConfigDiffer.diff(desired, state)

        assertEquals(
            listOf(
                ConfigMutation.SetDockFavorites(
                    listOf(Favorite("com.example.b"), Favorite("com.example.a"))
                )
            ),
            mutations,
        )
    }

    @Test
    fun `diff is deterministic across repeated runs`() {
        val first = ConfigDiffer.diff(matchingConfig, baseState.copy(themedIcons = false))
        val second = ConfigDiffer.diff(matchingConfig, baseState.copy(themedIcons = false))

        assertEquals(first, second)
    }
}

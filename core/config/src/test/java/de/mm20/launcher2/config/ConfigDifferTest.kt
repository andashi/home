package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDifferTest {

    private val dock = GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1)
    private val clock = GridItemConfig(
        id = "clock",
        widget = "com.android.deskclock/.DigitalAppWidgetProvider",
        x = 0, y = 0, w = 4, h = 2,
        profile = Profile.Personal,
    )

    private val baseState = ConfigState(
        themedIcons = true,
        enforceThemedIcons = true,
        iconPack = "app.lawnchair.lawnicons",
        searchBarPosition = SearchBarPosition.Bottom,
        favorites = listOf(Favorite("com.example.dialer", Profile.Personal)),
        widgetsEnabled = true,
        gridColumns = 4,
        gridLocked = false,
        gridLayouts = mapOf(
            "phone" to GridLayoutConfig(listOf(dock, clock)),
            "fold" to GridLayoutConfig(emptyList()),
        ),
        wallpaperImage = "home.jpg",
        wallpaperTarget = WallpaperTarget.Both,
    )

    private val matchingConfig = LauncherConfig(
        schemaVersion = 2,
        icons = IconsConfig(
            themed = true,
            enforceThemed = true,
            pack = "app.lawnchair.lawnicons",
        ),
        appearance = AppearanceConfig(
            glass = GlassConfig(
                blur = GlassDefaults.Blur,
                tint = GlassDefaults.Tint,
                radius = GlassDefaults.Radius,
                contrast = GlassDefaults.Contrast,
            ),
            wallpaper = WallpaperConfig(image = "home.jpg", target = WallpaperTarget.Both),
        ),
        home = HomeConfig(
            searchBar = SearchBarConfig(SearchBarPosition.Bottom),
            favorites = listOf(Favorite("com.example.dialer", Profile.Personal)),
            widgets = WidgetsConfig(enabled = true),
            grid = GridConfig(
                columns = 4,
                locked = false,
                labels = true,
                layouts = mapOf(
                    "phone" to GridLayoutConfig(listOf(dock, clock)),
                    "fold" to GridLayoutConfig(emptyList()),
                ),
            ),
        ),
    )

    // ----- appearance.glass (#73) -----

    @Test
    fun `each glass key diffs on its own`() {
        fun diff(glass: GlassConfig) =
            ConfigDiffer.diff(LauncherConfig(2, appearance = AppearanceConfig(glass = glass)), baseState)

        assertEquals(listOf(ConfigMutation.SetGlass(blur = 0f)), diff(GlassConfig(blur = 0f)))
        assertEquals(listOf(ConfigMutation.SetGlass(tint = 0.6f)), diff(GlassConfig(tint = 0.6f)))
        assertEquals(listOf(ConfigMutation.SetGlass(radius = 16f)), diff(GlassConfig(radius = 16f)))
        assertEquals(
            listOf(ConfigMutation.SetGlass(contrast = GlassContrast.High)),
            diff(GlassConfig(contrast = GlassContrast.High)),
        )
        assertEquals(
            listOf(ConfigMutation.SetGlass(wallpaperBlur = false)),
            diff(GlassConfig(wallpaperBlur = false)),
        )
        assertEquals(
            listOf(ConfigMutation.SetGlass(searchWallpaperBlur = false)),
            diff(GlassConfig(searchWallpaperBlur = false)),
        )
    }

    @Test
    fun `searchWallpaperBlur equal to the state produces nothing`() {
        val mutations = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(glass = GlassConfig(searchWallpaperBlur = false))),
            baseState.copy(glassSearchWallpaperBlur = false),
        )

        assertEquals(emptyList<ConfigMutation>(), mutations)
    }

    @Test
    fun `a glass section that sets two keys, one already in effect, carries only the other`() {
        val mutations = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(glass = GlassConfig(blur = GlassDefaults.Blur, tint = 0.1f))),
            baseState,
        )

        assertEquals(listOf(ConfigMutation.SetGlass(tint = 0.1f)), mutations)
    }

    @Test
    fun `a glass section equal to the state produces nothing`() {
        val mutations = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(glass = GlassConfig(blur = 12f, contrast = GlassContrast.Low))),
            baseState.copy(glassBlur = 12f, glassContrast = GlassContrast.Low),
        )

        assertEquals(emptyList<ConfigMutation>(), mutations)
    }

    @Test
    fun `labels diffs as part of the grid section`() {
        val mutations = ConfigDiffer.diff(
            LauncherConfig(2, home = HomeConfig(grid = GridConfig(labels = false))),
            baseState,
        )

        assertEquals(listOf(ConfigMutation.SetGrid(labels = false)), mutations)
    }

    @Test
    fun `a file that still carries transparency produces no mutation`() {
        val parsed = ConfigParser.parse(
            """{ "schemaVersion": 2, "appearance": { "transparency": { "name": "x", "background": 0.2 } } }"""
        ).config!!

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(parsed, ConfigState()))
    }

    @Test
    fun `wallpaper differs by image or target, target defaults to both`() {
        val byImage = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(wallpaper = WallpaperConfig("other.jpg"))),
            baseState,
        )
        assertEquals(listOf(ConfigMutation.SetWallpaper("other.jpg", WallpaperTarget.Both)), byImage)

        val byTarget = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(wallpaper = WallpaperConfig("home.jpg", WallpaperTarget.Lock))),
            baseState,
        )
        assertEquals(listOf(ConfigMutation.SetWallpaper("home.jpg", WallpaperTarget.Lock)), byTarget)

        val same = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(wallpaper = WallpaperConfig("home.jpg"))),
            baseState,
        )
        assertEquals(emptyList<ConfigMutation>(), same)
    }

    @Test
    fun `drifted wallpaper state reapplies the configured image`() {
        val mutations = ConfigDiffer.diff(matchingConfig, baseState.copy(wallpaperImage = null, wallpaperTarget = null))

        assertEquals(listOf(ConfigMutation.SetWallpaper("home.jpg", WallpaperTarget.Both)), mutations)
    }

    @Test
    fun `emits no mutations for equal state`() {
        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(matchingConfig, baseState))
    }

    @Test
    fun `empty desired config produces no mutations`() {
        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(LauncherConfig(schemaVersion = 2), baseState))
    }

    @Test
    fun `absent desired sections produce no mutations`() {
        val desired = LauncherConfig(
            schemaVersion = 2,
            icons = IconsConfig(themed = true, enforceThemed = true, pack = "app.lawnchair.lawnicons"),
        )

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(desired, baseState.copy(favorites = emptyList())))
    }

    @Test
    fun `absent desired fields within a section produce no mutation for those fields`() {
        val desired = LauncherConfig(
            schemaVersion = 2,
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
            schemaVersion = 2,
            icons = IconsConfig(themed = false),
            appearance = AppearanceConfig(
                glass = GlassConfig(tint = 0.5f)
            ),
            home = HomeConfig(
                searchBar = SearchBarConfig(SearchBarPosition.Top),
                favorites = listOf(Favorite("com.example.mail", Profile.Work)),
                widgets = WidgetsConfig(enabled = false),
                grid = GridConfig(columns = 5, locked = true),
            ),
        )

        val mutations = ConfigDiffer.diff(desired, baseState)

        assertEquals(
            listOf(
                "icons",
                "appearance.glass",
                "home.searchBar",
                "home.favorites",
                "home.widgets.enabled",
                "home.grid",
            ),
            mutations.map { it.section },
        )
        assertEquals(
            listOf(
                ConfigMutation.SetIcons(themed = false),
                ConfigMutation.SetGlass(tint = 0.5f),
                ConfigMutation.SetSearchBarPosition(SearchBarPosition.Top),
                ConfigMutation.SetFavorites(listOf(Favorite("com.example.mail", Profile.Work))),
                ConfigMutation.SetWidgetsEnabled(false),
                ConfigMutation.SetGrid(columns = 5, locked = true),
            ),
            mutations,
        )
    }

    @Test
    fun `reordered favorites list is a mutation`() {
        val state = baseState.copy(
            favorites = listOf(
                Favorite("com.example.a"),
                Favorite("com.example.b"),
            )
        )
        val desired = LauncherConfig(
            schemaVersion = 2,
            home = HomeConfig(
                favorites = listOf(
                    Favorite("com.example.b"),
                    Favorite("com.example.a"),
                )
            ),
        )

        val mutations = ConfigDiffer.diff(desired, state)

        assertEquals(
            listOf(
                ConfigMutation.SetFavorites(
                    listOf(Favorite("com.example.b"), Favorite("com.example.a"))
                )
            ),
            mutations,
        )
    }

    // ----- grid -----

    @Test
    fun `grid fields that equal the state produce nothing, one that differs produces SetGrid with only that field`() {
        val columns = ConfigDiffer.diff(
            LauncherConfig(2, home = HomeConfig(grid = GridConfig(columns = 5))),
            baseState,
        )
        assertEquals(listOf(ConfigMutation.SetGrid(columns = 5)), columns)

        val locked = ConfigDiffer.diff(
            LauncherConfig(2, home = HomeConfig(grid = GridConfig(locked = true))),
            baseState,
        )
        assertEquals(listOf(ConfigMutation.SetGrid(locked = true)), locked)

        val same = ConfigDiffer.diff(
            LauncherConfig(2, home = HomeConfig(grid = GridConfig(columns = 4, locked = false))),
            baseState,
        )
        assertEquals(emptyList<ConfigMutation>(), same)
    }

    @Test
    fun `a lone x or y is not a position and is not compared`() {
        // A position is x and y together; a single coordinate cannot anchor
        // an item, so the store places it freely and the differ must not
        // keep asking for a coordinate the store cannot honour.
        val loneX = clock.copy(x = 3, y = null)
        val desired = LauncherConfig(
            2,
            home = HomeConfig(grid = GridConfig(layouts = mapOf("phone" to GridLayoutConfig(listOf(dock, loneX))))),
        )

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(desired, baseState))
    }

    @Test
    fun `a layout the config names is compared item by item, in order`() {
        val moved = clock.copy(y = 2)
        val desired = LauncherConfig(
            2,
            home = HomeConfig(grid = GridConfig(layouts = mapOf("phone" to GridLayoutConfig(listOf(dock, moved))))),
        )

        val mutations = ConfigDiffer.diff(desired, baseState)

        assertEquals(
            listOf(ConfigMutation.SetGrid(layouts = mapOf("phone" to GridLayoutConfig(listOf(dock, moved))))),
            mutations,
        )
    }

    @Test
    fun `a reordered layout is a mutation because write-back keeps the file order`() {
        val desired = LauncherConfig(
            2,
            home = HomeConfig(grid = GridConfig(layouts = mapOf("phone" to GridLayoutConfig(listOf(clock, dock))))),
        )

        assertEquals(1, ConfigDiffer.diff(desired, baseState).size)
    }

    @Test
    fun `only the layouts the config names are compared`() {
        // The fold layout in state is untouched by a config that only speaks
        // about the phone layout, and vice versa.
        val state = baseState.copy(
            gridLayouts = mapOf(
                "phone" to GridLayoutConfig(listOf(dock, clock)),
                "fold" to GridLayoutConfig(listOf(dock.copy(w = 8))),
            )
        )
        val desired = LauncherConfig(
            2,
            home = HomeConfig(grid = GridConfig(layouts = mapOf("phone" to GridLayoutConfig(listOf(dock, clock))))),
        )

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(desired, state))
    }

    @Test
    fun `an item without geometry matches an item that has been placed`() {
        // Geometry may be omitted once (D5): the launcher places the item and
        // writes the geometry back. Until then a re-push of the same file must
        // stay a no-op, so a null field matches whatever the state holds, the
        // same "absent means unmanaged" rule as everywhere in the contract.
        val desired = LauncherConfig(
            2,
            home = HomeConfig(
                grid = GridConfig(
                    layouts = mapOf(
                        "phone" to GridLayoutConfig(
                            listOf(
                                GridItemConfig(id = "dock", widget = "favorites"),
                                GridItemConfig(id = "clock", widget = clock.widget, w = 4, h = 2),
                            )
                        )
                    )
                )
            ),
        )

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(desired, baseState))
    }

    @Test
    fun `an item with a different id, widget or option is a mutation`() {
        fun differs(items: List<GridItemConfig>): Boolean {
            val desired = LauncherConfig(
                2,
                home = HomeConfig(grid = GridConfig(layouts = mapOf("phone" to GridLayoutConfig(items)))),
            )
            return ConfigDiffer.diff(desired, baseState).isNotEmpty()
        }

        assertTrue(differs(listOf(dock, clock.copy(id = "clock2"))))
        assertTrue(differs(listOf(dock, clock.copy(widget = "com.other/.Widget"))))
        assertTrue(differs(listOf(dock, clock.copy(borderless = true))))
        assertTrue(differs(listOf(dock, clock.copy(profile = Profile.Work))))
        assertTrue(differs(listOf(dock)))
        assertTrue(differs(listOf(dock, clock, clock.copy(id = "clock2", y = 3))))
    }

    @Test
    fun `a layout missing from the state is a mutation`() {
        val desired = LauncherConfig(
            2,
            home = HomeConfig(grid = GridConfig(layouts = mapOf("fold" to GridLayoutConfig(listOf(dock))))),
        )

        val mutations = ConfigDiffer.diff(desired, baseState.copy(gridLayouts = emptyMap()))

        assertEquals(
            listOf(ConfigMutation.SetGrid(layouts = mapOf("fold" to GridLayoutConfig(listOf(dock))))),
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

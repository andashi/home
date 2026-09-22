package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStateMapperTest {

    private val dock = GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1)
    private val clock = GridItemConfig(
        id = "clock",
        widget = "com.android.deskclock/.DigitalAppWidgetProvider",
        x = 0, y = 0, w = 4, h = 2,
        profile = Profile.Personal,
        borderless = true, background = false, themeColors = true,
    )

    @Test
    fun `maps every state field into the config schema`() {
        val state = ConfigState(
            themedIcons = true,
            enforceThemedIcons = true,
            iconPack = "com.example.icons",
            transparencyName = "mystique",
            transparencyBackground = 0.5f,
            transparencySurface = 0.6f,
            transparencyElevatedSurface = 0.7f,
            searchBarPosition = SearchBarPosition.Bottom,
            favorites = listOf(
                Favorite("com.example.app", Profile.Personal),
                Favorite("com.example.work", Profile.Work),
            ),
            widgetsEnabled = true,
            gridColumns = 5,
            gridLocked = true,
            gridLayouts = mapOf(
                "phone" to GridLayoutConfig(listOf(dock, clock)),
                "fold" to GridLayoutConfig(emptyList()),
            ),
        )

        val config = state.toLauncherConfig()

        assertEquals(ConfigMigrations.currentSchemaVersion, config.schemaVersion)
        assertEquals(IconsConfig(true, true, "com.example.icons"), config.icons)
        assertEquals(
            TransparencyConfig("mystique", 0.5f, 0.6f, 0.7f),
            config.appearance?.transparency,
        )
        assertEquals(SearchBarPosition.Bottom, config.home?.searchBar?.position)
        assertEquals(state.favorites, config.home?.favorites)
        assertEquals(true, config.home?.widgets?.enabled)
        assertEquals(5, config.home?.grid?.columns)
        assertEquals(true, config.home?.grid?.locked)
        assertEquals(state.gridLayouts, config.home?.grid?.layouts)
    }

    @Test
    fun `wallpaper section is present only while a managed wallpaper is in effect`() {
        assertEquals(null, ConfigState().toLauncherConfig().appearance?.wallpaper)
        assertEquals(
            WallpaperConfig("home.jpg", WallpaperTarget.Lock),
            ConfigState(wallpaperImage = "home.jpg", wallpaperTarget = WallpaperTarget.Lock)
                .toLauncherConfig().appearance?.wallpaper,
        )
    }

    @Test
    fun `default state maps to defaults of the schema`() {
        val config = ConfigState().toLauncherConfig()

        assertNotNull(config.icons)
        assertNotNull(config.appearance?.transparency)
        assertNotNull(config.home?.searchBar)
        assertNotNull(config.home?.favorites)
        assertNotNull(config.home?.widgets)
        assertNotNull(config.home?.grid)
        assertEquals(4, config.home?.grid?.columns)
        assertEquals(false, config.home?.grid?.locked)
        assertNotNull(config.home?.grid?.layouts)
    }

    @Test
    fun `serialized mapping round-trips through the parser and diffs to nothing`() {
        val state = ConfigState(
            themedIcons = true,
            iconPack = "com.example.icons",
            transparencyName = "mystique",
            transparencyBackground = 0.5f,
            favorites = listOf(Favorite("com.example.app")),
            widgetsEnabled = true,
            gridLayouts = mapOf("phone" to GridLayoutConfig(listOf(dock, clock))),
        )

        val serialized = ConfigParser.json.encodeToString(
            LauncherConfig.serializer(),
            state.toLauncherConfig(),
        )
        val parseResult = ConfigParser.parse(serialized)

        assertNotNull(parseResult.config)
        assertTrue(parseResult.diagnostics.none { it.severity == Severity.Error })
        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(parseResult.config!!, state))
    }

    @Test
    fun `the read-back carries every item with full geometry`() {
        // D3: the read-back is a document one can paste into the dotfiles, so
        // nothing in it may be left for the launcher to fill in.
        val state = ConfigState(gridLayouts = mapOf("phone" to GridLayoutConfig(listOf(dock, clock))))

        val items = state.toLauncherConfig().home?.grid?.layouts?.get("phone")?.items

        assertNotNull(items)
        assertTrue(items!!.all { it.hasGeometry })
    }
}

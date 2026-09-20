package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStateMapperTest {

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
            dockEnabled = true,
            dockFavorites = listOf(
                Favorite("com.example.app", Profile.Personal),
                Favorite("com.example.work", Profile.Work),
            ),
            widgetsEnabled = true,
            widgets = listOf(BuiltinWidget.Apps, BuiltinWidget.Notes),
            clockStyle = ClockStyle.Binary,
            clockFillHeight = true,
        )

        val config = state.toLauncherConfig()

        assertEquals(ConfigMigrations.currentSchemaVersion, config.schemaVersion)
        assertEquals(IconsConfig(true, true, "com.example.icons"), config.icons)
        assertEquals(
            TransparencyConfig("mystique", 0.5f, 0.6f, 0.7f),
            config.appearance?.transparency,
        )
        assertEquals(SearchBarPosition.Bottom, config.home?.searchBar?.position)
        assertEquals(true, config.home?.dock?.enabled)
        assertEquals(state.dockFavorites, config.home?.dock?.favorites)
        assertEquals(true, config.home?.widgets?.enabled)
        assertEquals(state.widgets, config.home?.widgets?.widgets)
        assertEquals(ClockStyle.Binary, config.home?.clock?.style)
        assertEquals(true, config.home?.clock?.fillHeight)
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
        assertNotNull(config.home?.dock)
        assertNotNull(config.home?.widgets)
        assertNotNull(config.home?.clock)
    }

    @Test
    fun `serialized mapping round-trips through the parser and diffs to nothing`() {
        val state = ConfigState(
            themedIcons = true,
            iconPack = "com.example.icons",
            transparencyName = "mystique",
            transparencyBackground = 0.5f,
            dockEnabled = true,
            dockFavorites = listOf(Favorite("com.example.app")),
            widgetsEnabled = true,
            widgets = listOf(BuiltinWidget.Calendar),
            clockStyle = ClockStyle.Orbit,
            clockFillHeight = true,
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
}

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
            iconSize = 56,
            adaptifyIcons = true,
            badgeNotifications = false,
            badgeShortcuts = false,
            badgeSuspendedApps = false,
            glassBlur = 16f,
            glassTint = 0.5f,
            glassRadius = 20f,
            glassContrast = GlassContrast.High,
            glassWallpaperBlur = false,
            glassSearchWallpaperBlur = false,
            searchBarPosition = SearchBarPosition.Bottom,
            favorites = listOf(
                Favorite("com.example.app", Profile.Personal),
                Favorite("com.example.work", Profile.Work),
            ),
            widgetsEnabled = true,
            gridColumns = 5,
            gridLocked = true,
            gridLabels = false,
            gridLayouts = mapOf(
                "phone" to GridLayoutConfig(listOf(dock, clock)),
                "fold" to GridLayoutConfig(emptyList()),
            ),
        )

        val config = state.toLauncherConfig()

        assertEquals(ConfigMigrations.currentSchemaVersion, config.schemaVersion)
        assertEquals(
            IconsConfig(
                true, true, "com.example.icons", size = 56, adaptify = true,
                badges = IconBadgesConfig(notifications = false, shortcuts = false, suspendedApps = false),
            ),
            config.icons,
        )
        assertEquals(
            GlassConfig(16f, 0.5f, 20f, GlassContrast.High, wallpaperBlur = false, searchWallpaperBlur = false),
            config.appearance?.glass,
        )
        assertEquals(false, config.home?.grid?.labels)
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
        // Fully populated with the defaults, so provisioning's read-back can
        // compare field by field without knowing them (#73).
        assertEquals(
            GlassConfig(
                GlassDefaults.Blur, GlassDefaults.Tint, GlassDefaults.Radius, GlassDefaults.Contrast,
                GlassDefaults.WallpaperBlur, GlassDefaults.SearchWallpaperBlur,
            ),
            config.appearance?.glass,
        )
        assertEquals(GlassDefaults.Labels, config.home?.grid?.labels)
        assertNotNull(config.home?.searchBar)
        assertNotNull(config.home?.favorites)
        assertNotNull(config.home?.widgets)
        assertNotNull(config.home?.grid)
        assertEquals(4, config.home?.grid?.columns)
        assertEquals(false, config.home?.grid?.locked)
        assertNotNull(config.home?.grid?.layouts)
    }

    /** Served complete, like glass: a host compares field by field without knowing the defaults. */
    @Test
    fun `the icons read-back carries size, adaptify and every badge by default`() {
        assertEquals(
            IconsConfig(
                themed = true, enforceThemed = false, pack = null, size = 48, adaptify = false,
                badges = IconBadgesConfig(notifications = true, shortcuts = true, suspendedApps = true),
            ),
            ConfigState().toLauncherConfig().icons,
        )
    }

    @Test
    fun `serialized mapping round-trips through the parser and diffs to nothing`() {
        val state = ConfigState(
            themedIcons = true,
            iconPack = "com.example.icons",
            glassTint = 0.2f,
            glassContrast = GlassContrast.Low,
            gridLabels = false,
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

    @Test
    fun `the read-back no longer serves transparency`() {
        val serialized = ConfigParser.json.encodeToString(LauncherConfig.serializer(), ConfigState().toLauncherConfig())

        assertTrue(serialized, !serialized.contains("transparency"))
    }

    /** The documented defaults (#73, #24) are what an empty state reads back as. */
    @Test
    fun `the glass defaults are the documented ones`() {
        assertEquals(24f, GlassDefaults.Blur)
        // Lowered from 0.35 when the frosted look became liquid (#82).
        assertEquals(0.12f, GlassDefaults.Tint)
        assertEquals(true, GlassDefaults.WallpaperBlur)
        // Search is an overlay: blurred by default even when home is sharp (#91).
        assertEquals(true, GlassDefaults.SearchWallpaperBlur)
        assertEquals(28f, GlassDefaults.Radius)
        assertEquals(GlassContrast.Medium, GlassDefaults.Contrast)
        assertEquals(true, GlassDefaults.Labels)
    }

    /** `search` (#91) is always served complete, so a host compares it key by key. */
    @Test
    fun `search is fully populated, defaults and set values alike`() {
        val defaults = ConfigState().toLauncherConfig().search
        assertEquals(
            SearchConfig(
                favorites = true, allApps = true, layout = SearchResultLayout.Grid, labels = true,
                contacts = true, shortcuts = true, filterBar = true, openKeyboard = true,
                launchOnEnter = true, reversed = false, hiddenItemsButton = false,
                barPosition = InSearchBarPosition.Follow, listIcons = true, appDetails = true,
                contactsCallOnTap = false,
            ),
            defaults,
        )
        val set = ConfigState(
            search = SearchState(
                layout = SearchResultLayout.List, reversed = true, listIcons = false, appDetails = false,
                contactsCallOnTap = true,
            ),
        ).toLauncherConfig().search
        assertEquals(SearchResultLayout.List, set?.layout)
        assertEquals(true, set?.reversed)
        assertEquals(false, set?.listIcons)
        assertEquals(false, set?.appDetails)
        assertEquals(true, set?.contactsCallOnTap)
    }

    /** #3 D6: following is a value now, so the read-back serves it and `search` is complete. */
    @Test
    fun `search barPosition reads back follow while search follows the home bar`() {
        val served = ConfigParser.json.encodeToString(LauncherConfig.serializer(), ConfigState().toLauncherConfig())

        assertTrue(served, Regex(""""barPosition":\s*"follow"""").containsMatchIn(served))
    }

    /** #107, #3 D6: a set position is served as set (follow, the default, is checked above). */
    @Test
    fun `search barPosition is served as set`() {
        assertEquals(
            InSearchBarPosition.Bottom,
            ConfigState(search = SearchState(barPosition = InSearchBarPosition.Bottom)).toLauncherConfig().search?.barPosition,
        )
    }

    /** Today's behavior: a file without `search` changes nothing (#91). */
    @Test
    fun `the search defaults are today's behavior`() {
        assertEquals(true, SearchDefaults.Favorites)
        assertEquals(true, SearchDefaults.AllApps)
        assertEquals(SearchResultLayout.Grid, SearchDefaults.Layout)
        assertEquals(true, SearchDefaults.Labels)
        assertEquals(true, SearchDefaults.Contacts)
        assertEquals(true, SearchDefaults.Shortcuts)
        assertEquals(true, SearchDefaults.FilterBar)
        assertEquals(true, SearchDefaults.OpenKeyboard)
        assertEquals(true, SearchDefaults.LaunchOnEnter)
        assertEquals(false, SearchDefaults.Reversed)
        assertEquals(false, SearchDefaults.HiddenItemsButton)
    }
}

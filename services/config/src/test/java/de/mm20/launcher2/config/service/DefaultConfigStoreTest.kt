package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.SearchActionConfig
import de.mm20.launcher2.config.SearchConfig
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.WallpaperTarget
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.GlassContrast
import de.mm20.launcher2.config.GridItemConfig
import de.mm20.launcher2.config.GridLayoutConfig
import de.mm20.launcher2.config.GridLayouts
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridItemConfig
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.SavableSearchable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import de.mm20.launcher2.config.Profile as ConfigProfile

@RunWith(RobolectricTestRunner::class)
class DefaultConfigStoreTest {

    private lateinit var context: Context
    private lateinit var settings: FakeLauncherConfigSettings
    private lateinit var homeGridRepository: FakeHomeGridRepository
    private lateinit var initFlag: FakeInitFlag
    private val initLock = HomeGridInitLock()
    private lateinit var gridLimits: FakeGridLimitsSource
    private lateinit var searchActionStore: FakeSearchActionStore
    private lateinit var gridRows: FakeGridRowsSource
    private lateinit var searchableRepository: FakeSavableSearchableRepository
    private lateinit var appRepository: FakeAppRepository
    private lateinit var profileResolver: FakeProfileResolver
    private lateinit var wallpaperStore: FakeWallpaperStore
    private lateinit var store: DefaultConfigStore

    private val personalHandle: UserHandle = Process.myUserHandle()
    private val workHandle: UserHandle = TestUsers.userHandleFor(10)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = FakeLauncherConfigSettings()
        homeGridRepository = FakeHomeGridRepository()
        initFlag = FakeInitFlag()
        gridLimits = FakeGridLimitsSource()
        gridRows = FakeGridRowsSource()
        searchableRepository = FakeSavableSearchableRepository()
        appRepository = FakeAppRepository()
        profileResolver = FakeProfileResolver(
            personal = Profile(Profile.Type.Personal, personalHandle, 0),
            work = Profile(Profile.Type.Work, workHandle, 10),
        )
        wallpaperStore = FakeWallpaperStore()
        searchActionStore = FakeSearchActionStore()
        store = DefaultConfigStore(
            settings,
            homeGridRepository,
            initFlag,
            initLock,
            gridLimits,
            gridRows,
            searchableRepository,
            appRepository,
            profileResolver,
            wallpaperStore,
            searchActionStore,
        )
    }

    @Test
    fun `readState reports the managed wallpaper and SetWallpaper applies through the store`() = runTest {
        wallpaperStore.state = WallpaperState("home.jpg", WallpaperTarget.Both)
        val state = store.readState()
        assertEquals("home.jpg", state.wallpaperImage)
        assertEquals(WallpaperTarget.Both, state.wallpaperTarget)

        val diagnostics = store.apply(listOf(ConfigMutation.SetWallpaper("lock.jpg", WallpaperTarget.Lock)))

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        assertEquals(listOf("lock.jpg" to WallpaperTarget.Lock), wallpaperStore.applied)
    }


    private fun app(packageName: String, user: UserHandle): FakeApplication {
        return FakeApplication(ComponentName(packageName, "$packageName.MainActivity"), user)
    }

    // ----- readState -----

    @Test
    fun `readState combines settings glass grid layouts and favorites`() = runTest {
        settings.state = ConfigState(
            themedIcons = true,
            gridColumns = 5,
            gridLocked = true,
            gridLabels = false,
            glassBlur = 12f,
            glassTint = 0.6f,
            glassRadius = 20f,
            glassContrast = GlassContrast.High,
        )
        homeGridRepository.layouts["phone"] = listOf(
            HomeGridItem(layout = "phone", id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1, position = 0),
            HomeGridItem(
                layout = "phone", id = "clock", widget = "com.android.deskclock/.DigitalAppWidgetProvider",
                profile = "work", x = 0, y = 0, w = 4, h = 2, appWidgetId = 42,
                config = HomeGridItemConfig(borderless = true, background = false, themeColors = true),
                position = 1,
            ),
        )
        val appA = app("com.example.a", personalHandle)
        val appB = app("com.example.b", workHandle)
        searchableRepository.manuallySorted = listOf(appA, appB)

        val state = store.readState()

        assertTrue(state.themedIcons)
        assertEquals(5, state.gridColumns)
        assertEquals(true, state.gridLocked)
        assertEquals(false, state.gridLabels)
        assertEquals(12f, state.glassBlur)
        assertEquals(0.6f, state.glassTint)
        assertEquals(20f, state.glassRadius)
        assertEquals(GlassContrast.High, state.glassContrast)
        assertEquals(
            listOf(
                Favorite("com.example.a", ConfigProfile.Personal),
                Favorite("com.example.b", ConfigProfile.Work),
            ),
            state.favorites,
        )
        // Both layouts are always present; the device-local widget id is not
        // part of the contract and never appears.
        assertEquals(setOf("phone", "fold"), state.gridLayouts.keys)
        // Every field is populated in the read-back, options included, so the
        // document is complete (D3).
        assertEquals(
            listOf(
                GridItemConfig(
                    id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1,
                    borderless = false, background = true, themeColors = true,
                ),
                GridItemConfig(
                    id = "clock", widget = "com.android.deskclock/.DigitalAppWidgetProvider",
                    x = 0, y = 0, w = 4, h = 2, profile = ConfigProfile.Work,
                    borderless = true, background = false, themeColors = true,
                ),
            ),
            state.gridLayouts["phone"]?.items,
        )
        assertEquals(emptyList<GridItemConfig>(), state.gridLayouts["fold"]?.items)
    }

    @Test
    fun `readState skips favorites whose profile cannot be resolved`() = runTest {
        searchableRepository.manuallySorted = listOf(app("com.example.a", TestUsers.userHandleFor(99)))

        val state = store.readState()

        assertEquals(emptyList<Favorite>(), state.favorites)
    }

    // ----- grid -----

    private val clockWidget = "com.android.deskclock/.DigitalAppWidgetProvider"
    private val tallWidget = "com.example.notes/.TallWidgetProvider"

    private fun grid(vararg items: GridItemConfig, layout: String = "phone") =
        ConfigMutation.SetGrid(layouts = mapOf(layout to GridLayoutConfig(items.toList())))

    @Test
    fun `SetGrid writes the layout and sets the flag under the init lock`() = runTest {
        // The default row takes the same lock around its read-and-write, so a
        // reload and a first start cannot interleave (review on #71).
        homeGridRepository.onReplace = { homeGridRepository.lockedDuringReplace = initLock.isLocked }

        store.apply(listOf(grid(GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1))))

        assertEquals(true, homeGridRepository.lockedDuringReplace)
    }

    @Test
    fun `SetGrid marks the grid initialised so the default row never overwrites a configured layout`() = runTest {
        store.apply(listOf(grid(GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1))))

        assertTrue(initFlag.initialized)
    }

    @Test
    fun `SetGrid writes a layout with full geometry in config order`() = runTest {
        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 0, w = 4, h = 2, profile = ConfigProfile.Work, borderless = true),
                    GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1),
                )
            )
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        assertEquals(
            listOf(
                HomeGridItem(
                    layout = "phone", id = "clock", widget = clockWidget, profile = "work",
                    x = 0, y = 0, w = 4, h = 2, config = HomeGridItemConfig(borderless = true), position = 0,
                ),
                HomeGridItem(layout = "phone", id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1, position = 1),
            ),
            homeGridRepository.layouts["phone"],
        )
        assertEquals("the fold layout was not named and is untouched", null, homeGridRepository.layouts["fold"])
    }

    @Test
    fun `SetGrid places items without geometry at the first free cells, provider default span`() = runTest {
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(3, 1), limits = SizeLimits(2, 1, 4, 2))

        store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1),
                    GridItemConfig(id = "clock", widget = clockWidget),
                    GridItemConfig(id = "favs2", widget = "favorites", w = 2, h = 2),
                )
            )
        )

        val items = homeGridRepository.layouts["phone"]!!.associateBy { it.id }
        assertEquals(listOf(0, 0, 3, 1), items["clock"]!!.let { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(listOf(0, 1, 2, 2), items["favs2"]!!.let { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(listOf(0, 5, 4, 1), items["dock"]!!.let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun `SetGrid anchors an item that sets only its position and defaults the span`() = runTest {
        // Partial geometry: a position without a size is still a position.
        // Review on #66: placing such an item at the first free cells drops
        // the configured x/y, and because the differ compares x and y when
        // the file sets them, every reload would emit SetGrid again.
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(3, 1), limits = SizeLimits(2, 1, 4, 2))

        store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1),
                    GridItemConfig(id = "clock", widget = clockWidget, x = 1, y = 2),
                )
            )
        )

        val clock = homeGridRepository.layouts["phone"]!!.associateBy { it.id }.getValue("clock")
        assertEquals(listOf(1, 2, 3, 1), listOf(clock.x, clock.y, clock.w, clock.h))
    }

    @Test
    fun `SetGrid enlarges a span below the provider minimum and says so`() = runTest {
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(4, 2), limits = SizeLimits(2, 2, 4, 3))

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 0, w = 4, h = 1)))
        )

        val clock = homeGridRepository.layouts["phone"]!!.single()
        assertEquals(2, clock.h)
        assertEquals(listOf("widget-too-small"), diagnostics.map { it.code })
        assertEquals("home.grid.layouts.phone.items[0]", diagnostics.single().path)
        assertEquals(Severity.Warning, diagnostics.single().severity)
    }

    /**
     * #140: a span shrunk to fit was stored and served without a diagnostic.
     * Here the provider's own maximum sets the limit: `SizeLimits(2, 2, 4, 3)`
     * allows three rows, and the phone grid has six, so asking for five is
     * shrunk by the widget, not by the grid (the next test covers that).
     * Read-back serves what is in effect; the diagnostic says what was asked,
     * what is in effect and why.
     */
    @Test
    fun `SetGrid shrinks a span above the provider maximum and says so`() = runTest {
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(4, 2), limits = SizeLimits(2, 2, 4, 3))

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 0, w = 4, h = 5)))
        )

        val clock = homeGridRepository.layouts["phone"]!!.single()
        assertEquals(3, clock.h)
        val diagnostic = diagnostics.single()
        assertEquals("widget-too-large", diagnostic.code)
        assertEquals("home.grid.layouts.phone.items[0]", diagnostic.path)
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals(
            "'clock' asks for 4x5 cells, above the widget's maximum; it was shrunk to 4x3",
            diagnostic.message,
        )
    }

    @Test
    fun `SetGrid shrinks a span larger than the grid and says the grid set the limit`() = runTest {
        // This device's own layout, so its rows are the device's, not sized to the items.
        gridRows.own = "phone"
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(2, 2), limits = SizeLimits(1, 1, 8, 20))

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 0, w = 2, h = 9)))
        )

        val clock = homeGridRepository.layouts["phone"]!!.single()
        assertEquals(gridRows.rows, clock.h)
        assertEquals(
            "'clock' asks for 2x9 cells, more than the grid's ${4}x${gridRows.rows}; it was shrunk to 2x${gridRows.rows}",
            diagnostics.single { it.code == "widget-too-large" }.message,
        )
    }

    // #140: an item the file placed that ends up elsewhere is reported, like a
    // size that was fitted: the file keeps what it asked, the report says why
    // the effect differs. The move itself is unchanged.
    @Test
    fun `an item slid back into the grid is reported with where it went`() = runTest {
        gridRows.own = "phone"
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(2, 1), limits = SizeLimits(1, 1, 4, 6))

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, x = 3, y = 5, w = 2, h = 2)))
        )

        val clock = homeGridRepository.layouts["phone"]!!.single()
        assertEquals(listOf(2, 4), listOf(clock.x, clock.y))
        val diagnostic = diagnostics.single()
        assertEquals("grid-item-moved", diagnostic.code)
        assertEquals("home.grid.layouts.phone.items[0]", diagnostic.path)
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals(
            "'clock' asks for x=3 y=5, which puts its 2x2 cells outside the grid; it was moved to x=2 y=4",
            diagnostic.message,
        )
    }

    @Test
    fun `an item pushed down by an earlier one is reported with what it overlapped`() = runTest {
        gridRows.own = "phone"
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(2, 2), limits = SizeLimits(1, 1, 4, 6))

        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "first", widget = clockWidget, x = 0, y = 0, w = 2, h = 2),
                    GridItemConfig(id = "second", widget = clockWidget, x = 0, y = 1, w = 2, h = 2),
                )
            )
        )

        assertEquals(
            listOf("grid-item-moved" to "'second' asks for x=0 y=1, which overlaps 'first'; it was moved down to x=0 y=2"),
            diagnostics.map { it.code to it.message },
        )
        assertEquals("home.grid.layouts.phone.items[1]", diagnostics.single().path)
    }

    // Nothing was asked, so nothing was overridden: an item without a
    // position is placed, and placing it is not a move. Placement puts
    // "placed" on row 2, the first free one; normalize then pushes "second"
    // down onto row 2 as well, and "placed" goes to row 3 - the engine
    // reports that as a move, and only the store knows it asked for none.
    @Test
    fun `an item without a position is never reported as moved`() = runTest {
        gridRows.own = "phone"
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(4, 1), limits = SizeLimits(1, 1, 4, 6))

        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "first", widget = clockWidget, x = 0, y = 0, w = 4, h = 2),
                    GridItemConfig(id = "second", widget = clockWidget, x = 0, y = 1, w = 4, h = 1),
                    GridItemConfig(id = "placed", widget = clockWidget),
                )
            )
        )

        val layout = homeGridRepository.layouts["phone"]!!.associateBy { it.id }
        assertEquals(listOf(2, 3), listOf(layout.getValue("second").y, layout.getValue("placed").y))
        assertEquals(listOf("second"), diagnostics.filter { it.code == "grid-item-moved" }.map { it.path.substringAfterLast('[').trimEnd(']').let { i -> listOf("first", "second", "placed")[i.toInt()] } })
    }

    // An item without a position is sized by placement, not by normalize, and
    // used to be fitted there without a word in either direction (#170 review).
    @Test
    fun `an item without a position that is too large is reported like a placed one`() = runTest {
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(4, 2), limits = SizeLimits(2, 2, 4, 3))

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, w = 4, h = 5)))
        )

        assertEquals(3, homeGridRepository.layouts["phone"]!!.single().h)
        assertEquals(
            listOf("widget-too-large" to "'clock' asks for 4x5 cells, above the widget's maximum; it was shrunk to 4x3"),
            diagnostics.map { it.code to it.message },
        )
    }

    @Test
    fun `an item without a position that is too small is reported like a placed one`() = runTest {
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(4, 2), limits = SizeLimits(2, 2, 4, 3))

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, w = 4, h = 1)))
        )

        assertEquals(2, homeGridRepository.layouts["phone"]!!.single().h)
        assertEquals(listOf("widget-too-small"), diagnostics.map { it.code })
    }

    @Test
    fun `SetGrid drops what does not fit and reports it`() = runTest {
        gridRows.rows = 2
        // A widget whose minimum height is three rows can never fit two.
        gridLimits.limits[clockWidget] = ProviderLimits(default = CellSize(4, 3), limits = SizeLimits(2, 3, 4, 4))

        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "a", widget = "favorites", x = 0, y = 0, w = 4, h = 2),
                    GridItemConfig(id = "b", widget = "favorites", x = 0, y = 0, w = 4, h = 1),
                    GridItemConfig(id = "c", widget = clockWidget, x = 0, y = 0, w = 4, h = 3),
                )
            )
        )

        assertEquals(listOf("a"), homeGridRepository.layouts["phone"]!!.map { it.id })
        val codes = diagnostics.map { it.code to it.path }
        assertTrue(codes.toString(), ("grid-overflow" to "home.grid.layouts.phone.items[1]") in codes)
        assertTrue(codes.toString(), ("grid-out-of-bounds" to "home.grid.layouts.phone.items[2]") in codes)
        assertTrue(diagnostics.all { it.severity == Severity.Warning })
    }

    @Test
    fun `SetGrid keeps the fold line rule on the fold layout`() = runTest {
        settings.state = ConfigState(gridColumns = 4)

        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 8, h = 1),
                    GridItemConfig(id = "clock", widget = clockWidget, x = 3, y = 0, w = 2, h = 1),
                    layout = "fold",
                )
            )
        )

        val items = homeGridRepository.layouts["fold"]!!.associateBy { it.id }
        assertEquals("favorites may span the fold", 8, items["dock"]!!.w)
        val clock = items["clock"]!!
        assertTrue("the clock was nudged to one side: x=${clock.x}", clock.x + clock.w <= 4 || clock.x >= 4)
        assertEquals(listOf("grid-crosses-fold"), diagnostics.map { it.code })
    }

    /**
     * #90: a phone checked the fold layout against its own six rows, so the
     * Fold's seventh row was clamped to the sixth and, with that row taken,
     * the item dropped with grid-overflow. The phone never shows that layout;
     * it stores it as written.
     */
    @Test
    fun `a phone stores the fold layout as written, its seventh row included`() = runTest {
        settings.state = ConfigState(gridColumns = 4)
        gridRows.own = "phone"

        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 5, w = 2, h = 1),
                    GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 6, w = 8, h = 1),
                    layout = "fold",
                )
            )
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        val dock = homeGridRepository.layouts["fold"]!!.single { it.id == "dock" }
        assertEquals(listOf(0, 6, 8, 1), listOf(dock.x, dock.y, dock.w, dock.h))
    }

    @Test
    fun `a phone keeps room for an unplaced widget of its provider's default height`() = runTest {
        gridRows.own = "phone"
        gridLimits.limits[tallWidget] = ProviderLimits(default = CellSize(2, 3), limits = SizeLimits(1, 3, 4, 4))

        val diagnostics = store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 0, w = 8, h = 6),
                    GridItemConfig(id = "tall", widget = tallWidget),
                    layout = "fold",
                )
            )
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        val tall = homeGridRepository.layouts["fold"]!!.single { it.id == "tall" }
        assertEquals(listOf(0, 6, 2, 3), listOf(tall.x, tall.y, tall.w, tall.h))
    }

    @Test
    fun `a phone keeps room below a placed widget enlarged to its minimum height`() = runTest {
        gridRows.own = "phone"
        gridLimits.limits[tallWidget] = ProviderLimits(default = CellSize(2, 3), limits = SizeLimits(1, 3, 4, 4))

        store.apply(listOf(grid(GridItemConfig(id = "tall", widget = tallWidget, x = 0, y = 5, w = 2, h = 1), layout = "fold")))

        val tall = homeGridRepository.layouts["fold"]!!.single()
        assertEquals(listOf(0, 5, 2, 3), listOf(tall.x, tall.y, tall.w, tall.h))
    }

    /** Control: the layout this device renders is still bounded by its rows. */
    @Test
    fun `the layout this device renders is still bounded by its own rows`() = runTest {
        gridRows.own = "phone"

        store.apply(listOf(grid(GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 6, w = 4, h = 1))))

        assertEquals(5, homeGridRepository.layouts["phone"]!!.single().y)
    }

    /** #92: the differ applies named layouts on a never-initialised grid; it reads the flag here. */
    @Test
    fun `readState reports whether the grid was ever initialised`() = runTest {
        assertEquals(false, store.readState().gridInitialized)

        initFlag.markInitialized()

        assertEquals(true, store.readState().gridInitialized)
    }

    /**
     * #92 review: the layouts and the flag are one snapshot. Read apart, the
     * default row could land in between: empty layouts, flag set, and the
     * differ would drop the file's empty layout and leave the row.
     */
    @Test
    fun `readState reads the layouts and the flag under the init lock`() = runTest {
        initFlag.lockProbe = { initLock.isLocked }
        homeGridRepository.lockProbe = { initLock.isLocked }

        store.readState()

        assertEquals(listOf(true), initFlag.lockedDuringRead)
        assertEquals(GridLayouts.All.map { true }, homeGridRepository.lockedDuringObserve)
    }

    /** The applied empty layout sets the flag, as any applied layout does. */
    @Test
    fun `an applied empty layout marks the grid initialised`() = runTest {
        store.apply(listOf(ConfigMutation.SetGrid(layouts = mapOf("phone" to GridLayoutConfig(emptyList())))))

        assertEquals(true, initFlag.isInitialized())
        assertEquals(emptyList<HomeGridItem>(), homeGridRepository.layouts["phone"])
    }

    @Test
    fun `SetGrid keeps the AppWidget host id of an item that already exists`() = runTest {
        homeGridRepository.layouts["phone"] = listOf(
            HomeGridItem(layout = "phone", id = "clock", widget = clockWidget, x = 0, y = 0, w = 4, h = 2, appWidgetId = 42, position = 0),
            HomeGridItem(layout = "phone", id = "gone", widget = clockWidget, x = 0, y = 2, w = 4, h = 2, appWidgetId = 43, position = 1),
        )

        store.apply(
            listOf(
                grid(
                    GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 3, w = 4, h = 2),
                    GridItemConfig(id = "other", widget = clockWidget, x = 0, y = 0, w = 4, h = 2),
                )
            )
        )

        val items = homeGridRepository.layouts["phone"]!!.associateBy { it.id }
        assertEquals(42, items["clock"]!!.appWidgetId)
        assertEquals(3, items["clock"]!!.y)
        assertEquals(null, items["other"]!!.appWidgetId)
        assertEquals(null, items["gone"])
    }

    @Test
    fun `SetGrid does not carry a host id over to a different provider under the same id`() = runTest {
        homeGridRepository.layouts["phone"] = listOf(
            HomeGridItem(layout = "phone", id = "w", widget = clockWidget, x = 0, y = 0, w = 4, h = 2, appWidgetId = 42, position = 0),
        )

        store.apply(listOf(grid(GridItemConfig(id = "w", widget = "com.other/.Widget", x = 0, y = 0, w = 4, h = 2))))

        assertEquals(null, homeGridRepository.layouts["phone"]!!.single().appWidgetId)
    }

    @Test
    fun `SetGrid keeps an item whose provider is not installed, with a warning`() = runTest {
        gridLimits.limits.clear()

        val diagnostics = store.apply(
            listOf(grid(GridItemConfig(id = "clock", widget = clockWidget, x = 0, y = 0, w = 4, h = 2)))
        )

        assertEquals(listOf("clock"), homeGridRepository.layouts["phone"]!!.map { it.id })
        assertEquals(listOf("unknown-widget-provider"), diagnostics.map { it.code })
        assertEquals("home.grid.layouts.phone.items[0]", diagnostics.single().path)
        assertEquals(Severity.Warning, diagnostics.single().severity)
    }

    @Test
    fun `SetGrid without layouts touches no layout`() = runTest {
        homeGridRepository.layouts["phone"] = listOf(
            HomeGridItem(layout = "phone", id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1, position = 0),
        )

        val diagnostics = store.apply(listOf(ConfigMutation.SetGrid(columns = 5, locked = true)))

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        assertEquals(1, homeGridRepository.layouts["phone"]!!.size)
        assertEquals(0, homeGridRepository.replaceCalls)
    }

    // ----- favorites -----

    @Test
    fun `SetFavorites resolves apps and writes them in config order`() = runTest {
        val appA = app("com.example.a", personalHandle)
        val appB = app("com.example.b", workHandle)
        appRepository.apps["com.example.a" to personalHandle] = appA
        appRepository.apps["com.example.b" to workHandle] = appB
        val automatic = app("com.example.c", personalHandle)
        searchableRepository.automaticallySorted = listOf(automatic)

        val diagnostics = store.apply(
            listOf(
                ConfigMutation.SetFavorites(
                    listOf(
                        Favorite("com.example.b", ConfigProfile.Work),
                        Favorite("com.example.a", ConfigProfile.Personal),
                    )
                )
            )
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        assertEquals(listOf(appB, appA), searchableRepository.manuallySorted)
        // Automatically pinned favorites are outside the config's scope.
        assertEquals(listOf(automatic), searchableRepository.automaticallySorted)
    }

    /**
     * #3 D4: the file names apps only, so a present `home.favorites` manages
     * the app pins and nothing else. Shortcuts, tags and contacts pinned on
     * the device stay pinned, in their order, after the configured apps -
     * a reload used to unpin them all.
     */
    @Test
    fun `SetFavorites keeps pinned shortcuts tags and contacts after the configured apps`() = runTest {
        val appA = app("com.example.a", personalHandle)
        val appB = app("com.example.b", workHandle)
        val appOld = app("com.example.old", personalHandle)
        appRepository.apps["com.example.a" to personalHandle] = appA
        appRepository.apps["com.example.b" to workHandle] = appB
        val contact = FakeItem("contact://42", "contact")
        val shortcut = FakeItem("shortcut://com.example.a/compose", "shortcut")
        val tag = FakeItem("tag://work", "tag")
        // Mixed on the device: a contact first, apps in between.
        searchableRepository.manuallySorted = listOf(contact, appB, shortcut, appOld, tag)
        val automatic = app("com.example.c", personalHandle)
        searchableRepository.automaticallySorted = listOf(automatic)

        val diagnostics = store.apply(
            listOf(
                ConfigMutation.SetFavorites(
                    listOf(
                        Favorite("com.example.a", ConfigProfile.Personal),
                        Favorite("com.example.b", ConfigProfile.Work),
                    )
                )
            )
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        // The apps in file order, the app the file dropped unpinned, then the
        // other pins in the order they had.
        assertEquals(listOf(appA, appB, contact, shortcut, tag), searchableRepository.manuallySorted)
        assertEquals(listOf(automatic), searchableRepository.automaticallySorted)
    }

    @Test
    fun `readState reports app favorites only, not the pins the file cannot name`() = runTest {
        val appA = app("com.example.a", personalHandle)
        searchableRepository.manuallySorted = listOf(appA, FakeItem("tag://work", "tag"), FakeItem("contact://42", "contact"))

        assertEquals(listOf(Favorite("com.example.a", ConfigProfile.Personal)), store.readState().favorites)
    }

    @Test
    fun `SetFavorites skips unavailable apps with error diagnostics`() = runTest {
        val appA = app("com.example.a", personalHandle)
        appRepository.apps["com.example.a" to personalHandle] = appA

        val diagnostics = store.apply(
            listOf(
                ConfigMutation.SetFavorites(
                    listOf(
                        Favorite("com.example.a", ConfigProfile.Personal),
                        Favorite("com.example.missing", ConfigProfile.Personal),
                    )
                )
            )
        )

        assertEquals(listOf(appA), searchableRepository.manuallySorted)
        assertEquals(1, diagnostics.size)
        assertEquals(Severity.Error, diagnostics[0].severity)
        assertEquals("favorite-unavailable", diagnostics[0].code)
        assertEquals("home.favorites[1]", diagnostics[0].path)
    }

    @Test
    fun `SetFavorites reports an unavailable profile`() = runTest {
        profileResolver.work = null

        val diagnostics = store.apply(
            listOf(
                ConfigMutation.SetFavorites(
                    listOf(Favorite("com.example.b", ConfigProfile.Work))
                )
            )
        )

        assertEquals(emptyList<SavableSearchable>(), searchableRepository.manuallySorted)
        assertEquals(1, diagnostics.size)
        assertEquals("profile-unavailable", diagnostics[0].code)
        assertEquals(Severity.Error, diagnostics[0].severity)
    }

    // ----- settings-backed mutations -----

    @Test
    fun `settings-backed mutations are applied in a single settings call`() = runTest {
        store.apply(
            listOf(
                ConfigMutation.SetIcons(themed = true),
                ConfigMutation.SetGlass(tint = 0.1f, contrast = GlassContrast.Low),
                ConfigMutation.SetGrid(columns = 5, locked = true, labels = false),
                ConfigMutation.SetWidgetsEnabled(true),
                ConfigMutation.SetFavorites(emptyList()),
            )
        )

        assertEquals(1, settings.applyCalls.size)
        assertEquals(4, settings.applyCalls[0].size)
        assertEquals(0.1f, settings.state.glassTint)
        assertEquals(GlassContrast.Low, settings.state.glassContrast)
        assertEquals(false, settings.state.gridLabels)
        assertTrue(settings.state.themedIcons)
        assertEquals(5, settings.state.gridColumns)
        assertTrue(settings.state.gridLocked)
        assertTrue(settings.state.widgetsEnabled)
    }

    @Test
    fun `search is settings-backed and applied in the same settings call`() = runTest {
        store.apply(
            listOf(
                ConfigMutation.SetIcons(themed = true),
                ConfigMutation.SetSearch(SearchConfig(favorites = false)),
            )
        )

        assertEquals(1, settings.applyCalls.size)
        assertEquals(2, settings.applyCalls[0].size)
        assertEquals(false, settings.state.search.favorites)
    }

    // ---- search.actions (#106) ----

    @Test
    fun `readState reports the search actions in effect`() = runTest {
        searchActionStore.actions = listOf(SearchActionConfig("call"), SearchActionConfig("websearch"))

        assertEquals(searchActionStore.actions, store.readState().searchActions)
    }

    @Test
    fun `SetSearchActions replaces the actions, outside the settings call, and passes its reports on`() = runTest {
        val actions = listOf(SearchActionConfig("url", label = "Docs", url = "https://example.org/?q=\${1}"))
        val report = Diagnostic(Severity.Warning, "search-action-app-not-searchable", "search.actions[1]", "x")
        searchActionStore.reports = listOf(report)

        val diagnostics = store.apply(listOf(ConfigMutation.SetSearchActions(actions)))

        assertEquals(listOf(actions to "search.actions"), searchActionStore.replaced)
        assertEquals(listOf(report), diagnostics)
        assertEquals(emptyList<List<ConfigMutation>>(), settings.applyCalls)
    }

    @Test
    fun `a failed settings write is reported for every settings-backed section, glass included`() = runTest {
        settings.applyFailure = IllegalStateException("datastore gone")

        val diagnostics = store.apply(
            listOf(
                ConfigMutation.SetIcons(themed = true),
                ConfigMutation.SetGlass(tint = 0.1f),
                ConfigMutation.SetFavorites(emptyList()),
            )
        )

        assertEquals(
            listOf("icons", "appearance.glass"),
            diagnostics.filter { it.code == "apply-failed" }.map { it.path },
        )
        assertTrue(diagnostics.all { it.severity == Severity.Error && it.message.contains("datastore gone") })
    }


    /**
     * #37: the deferred wallpaper has to be reported on every reload, not only
     * on the one that deferred it. Once recorded it stops producing mutations,
     * so a run that checks whether everything sits would otherwise come back
     * green for something still waiting.
     */
    @Test
    fun `a pending wallpaper is reported even when nothing is applied`() = runTest {
        wallpaperStore.pending = WallpaperState("home.jpg", WallpaperTarget.Both)

        val diagnostics = store.apply(emptyList())

        assertEquals(listOf("wallpaper-pending-foreground"), diagnostics.map { it.code })
        assertEquals("appearance.wallpaper.image", diagnostics.single().path)
        assertEquals(Severity.Warning, diagnostics.single().severity)
    }

    @Test
    fun `nothing pending means nothing extra is reported`() = runTest {
        assertEquals(emptyList<Diagnostic>(), store.apply(emptyList()))
    }

}

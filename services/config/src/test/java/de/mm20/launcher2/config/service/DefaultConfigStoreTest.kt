package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.WallpaperTarget
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.GridItemConfig
import de.mm20.launcher2.config.GridLayoutConfig
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridItemConfig
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.preferences.config.SettingsBackedState
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.searchable.VisibilityLevel
import de.mm20.launcher2.themes.DefaultThemeId
import de.mm20.launcher2.themes.R
import de.mm20.launcher2.themes.transparencies.Transparencies
import de.mm20.launcher2.themes.transparencies.TransparenciesRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import de.mm20.launcher2.config.Profile as ConfigProfile

@RunWith(RobolectricTestRunner::class)
class DefaultConfigStoreTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var transparenciesRepository: TransparenciesRepository
    private lateinit var settings: FakeLauncherConfigSettings
    private lateinit var homeGridRepository: FakeHomeGridRepository
    private lateinit var gridLimits: FakeGridLimitsSource
    private lateinit var gridRows: FakeGridRowsSource
    private lateinit var searchableRepository: FakeSavableSearchableRepository
    private lateinit var appRepository: FakeAppRepository
    private lateinit var profileResolver: FakeProfileResolver
    private lateinit var wallpaperStore: FakeWallpaperStore
    private lateinit var store: DefaultConfigStore

    private val personalHandle: UserHandle = Process.myUserHandle()
    private val workHandle: UserHandle = userHandleFor(10)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        transparenciesRepository = TransparenciesRepository(context, database)
        settings = FakeLauncherConfigSettings()
        homeGridRepository = FakeHomeGridRepository()
        gridLimits = FakeGridLimitsSource()
        gridRows = FakeGridRowsSource()
        searchableRepository = FakeSavableSearchableRepository()
        appRepository = FakeAppRepository()
        profileResolver = FakeProfileResolver(
            personal = Profile(Profile.Type.Personal, personalHandle, 0),
            work = Profile(Profile.Type.Work, workHandle, 10),
        )
        wallpaperStore = FakeWallpaperStore()
        store = DefaultConfigStore(
            settings,
            transparenciesRepository,
            homeGridRepository,
            gridLimits,
            gridRows,
            searchableRepository,
            appRepository,
            profileResolver,
            wallpaperStore,
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

    @After
    fun tearDown() {
        database.close()
    }

    private fun app(packageName: String, user: UserHandle): FakeApplication {
        return FakeApplication(ComponentName(packageName, "$packageName.MainActivity"), user)
    }

    // ----- readState -----

    @Test
    fun `readState combines settings transparency grid layouts and favorites`() = runTest {
        val theme = Transparencies(
            id = UUID.randomUUID(),
            name = "glass",
            background = 0.5f,
            surface = 0.6f,
            elevatedSurface = 0.7f,
        )
        transparenciesRepository.upsert(theme)
        settings.state = ConfigState(themedIcons = true, gridColumns = 5, gridLocked = true)
        settings.transparenciesId = theme.id
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
        assertEquals("glass", state.transparencyName)
        assertEquals(0.5f, state.transparencyBackground)
        assertEquals(0.6f, state.transparencySurface)
        assertEquals(0.7f, state.transparencyElevatedSurface)
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
        searchableRepository.manuallySorted = listOf(app("com.example.a", userHandleFor(99)))

        val state = store.readState()

        assertEquals(emptyList<Favorite>(), state.favorites)
    }

    // ----- transparency -----

    @Test
    fun `SetTransparency creates a new scheme and selects it`() = runTest {
        val diagnostics = store.apply(
            listOf(ConfigMutation.SetTransparency(name = "glass", background = 0.5f))
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        val theme = transparenciesRepository.findByName("glass")
        assertEquals(0.5f, theme!!.background)
        assertNull(theme.surface)
        assertEquals(theme.id, settings.transparenciesId)
    }

    @Test
    fun `SetTransparency without a name derives from the default when the selected scheme is gone`() = runTest {
        settings.transparenciesId = UUID.randomUUID() // deleted user scheme

        val diagnostics = store.apply(
            listOf(ConfigMutation.SetTransparency(background = 0.5f))
        )

        assertEquals(emptyList<Diagnostic>(), diagnostics)
        val selected = transparenciesRepository.getOnce(settings.transparenciesId)
        assertEquals(0.5f, selected!!.background)
        assertEquals(false, selected.builtIn)
    }

    @Test
    fun `SetTransparency preserves unspecified values and stays idempotent`() = runTest {
        store.apply(
            listOf(
                ConfigMutation.SetTransparency(
                    name = "glass",
                    background = 0.5f,
                    surface = 0.6f,
                    elevatedSurface = 0.7f,
                )
            )
        )
        val created = transparenciesRepository.findByName("glass")!!

        store.apply(listOf(ConfigMutation.SetTransparency(name = "glass", background = 0.2f)))

        val updated = transparenciesRepository.findByName("glass")!!
        assertEquals(created.id, updated.id)
        assertEquals(0.2f, updated.background)
        assertEquals(0.6f, updated.surface)
        assertEquals(0.7f, updated.elevatedSurface)
        assertEquals(1, database.themeDao().getAllTransparencies().first().size)
        assertEquals(updated.id, settings.transparenciesId)
    }

    @Test
    fun `SetTransparency selects a built-in scheme without creating a row`() = runTest {
        val defaultName = context.getString(R.string.preference_transparencies_default)

        store.apply(listOf(ConfigMutation.SetTransparency(name = defaultName)))

        assertEquals(DefaultThemeId, settings.transparenciesId)
        assertTrue(database.themeDao().getAllTransparencies().first().isEmpty())
    }

    @Test
    fun `SetTransparency with values derives a user scheme from a built-in`() = runTest {
        settings.transparenciesId = DefaultThemeId

        store.apply(listOf(ConfigMutation.SetTransparency(background = 0.3f)))

        assertNotEquals(DefaultThemeId, settings.transparenciesId)
        val derived = transparenciesRepository.getOnce(settings.transparenciesId)!!
        assertEquals(context.getString(R.string.preference_transparencies_default), derived.name)
        assertEquals(0.3f, derived.background)

        // The derived scheme shadows the built-in name, so a follow-up
        // mutation by name updates it instead of deriving another scheme.
        store.apply(
            listOf(
                ConfigMutation.SetTransparency(
                    name = derived.name,
                    surface = 0.4f,
                )
            )
        )
        assertEquals(1, database.themeDao().getAllTransparencies().first().size)
        val updated = transparenciesRepository.findByName(derived.name)!!
        assertEquals(derived.id, updated.id)
        assertEquals(0.3f, updated.background)
        assertEquals(0.4f, updated.surface)
    }

    // ----- grid -----

    private val clockWidget = "com.android.deskclock/.DigitalAppWidgetProvider"

    private fun grid(vararg items: GridItemConfig, layout: String = "phone") =
        ConfigMutation.SetGrid(layouts = mapOf(layout to GridLayoutConfig(items.toList())))

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
                ConfigMutation.SetGrid(columns = 5, locked = true),
                ConfigMutation.SetWidgetsEnabled(true),
                ConfigMutation.SetFavorites(emptyList()),
            )
        )

        assertEquals(1, settings.applyCalls.size)
        assertEquals(3, settings.applyCalls[0].size)
        assertTrue(settings.state.themedIcons)
        assertEquals(5, settings.state.gridColumns)
        assertTrue(settings.state.gridLocked)
        assertTrue(settings.state.widgetsEnabled)
    }

    // ----- fakes -----

    private class FakeLauncherConfigSettings(
        var state: ConfigState = ConfigState(),
        var transparenciesId: UUID = UUID(0L, 0L),
    ) : LauncherConfigSettings {
        val applyCalls = mutableListOf<List<ConfigMutation>>()

        override suspend fun readState(): SettingsBackedState {
            return SettingsBackedState(state, transparenciesId)
        }

        override suspend fun apply(mutations: List<ConfigMutation>) {
            applyCalls += mutations
            for (mutation in mutations) {
                when (mutation) {
                    is ConfigMutation.SetIcons -> state = state.copy(
                        themedIcons = mutation.themed ?: state.themedIcons,
                        enforceThemedIcons = mutation.enforceThemed ?: state.enforceThemedIcons,
                        iconPack = mutation.pack ?: state.iconPack,
                    )

                    is ConfigMutation.SetSearchBarPosition ->
                        state = state.copy(searchBarPosition = mutation.position)

                    is ConfigMutation.SetGrid ->
                        state = state.copy(
                            gridColumns = mutation.columns ?: state.gridColumns,
                            gridLocked = mutation.locked ?: state.gridLocked,
                        )

                    is ConfigMutation.SetWidgetsEnabled ->
                        state = state.copy(widgetsEnabled = mutation.enabled)

                    else -> Unit
                }
            }
        }

        override suspend fun setTransparenciesId(id: UUID) {
            transparenciesId = id
        }
    }

    private class FakeHomeGridRepository : HomeGridRepository {
        val layouts = mutableMapOf<String, List<HomeGridItem>>()
        var replaceCalls = 0

        override fun observe(layout: String): Flow<List<HomeGridItem>> = flowOf(layouts[layout] ?: emptyList())

        override suspend fun replace(layout: String, items: List<HomeGridItem>) {
            replaceCalls++
            layouts[layout] = items
        }

        override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) =
            throw NotImplementedError()

        override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) =
            throw NotImplementedError()

        override suspend fun delete(layout: String, id: String) = throw NotImplementedError()
    }

    private class FakeGridLimitsSource : GridLimitsSource {
        /** Providers the fake knows; the clock is installed by default. */
        val limits = mutableMapOf(
            "com.android.deskclock/.DigitalAppWidgetProvider" to
                    ProviderLimits(default = CellSize(4, 2), limits = SizeLimits(2, 1, 4, 4)),
        )

        override fun lookup(widget: String, profile: ConfigProfile?, columns: Int): ProviderLimits? = limits[widget]
    }

    private class FakeGridRowsSource(var rows: Int = 6) : GridRowsSource {
        override fun rows(layout: String): Int = rows
    }

    private class FakeSavableSearchableRepository : SavableSearchableRepository {
        var manuallySorted: List<SavableSearchable> = emptyList()
        var automaticallySorted: List<SavableSearchable> = emptyList()

        override fun get(
            includeTypes: List<String>?,
            excludeTypes: List<String>?,
            minPinnedLevel: PinnedLevel,
            maxPinnedLevel: PinnedLevel,
            minVisibility: VisibilityLevel,
            maxVisibility: VisibilityLevel,
            limit: Int,
        ): Flow<List<SavableSearchable>> {
            val items = buildList {
                if (PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel) {
                    addAll(manuallySorted)
                }
                if (PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel) {
                    addAll(automaticallySorted)
                }
            }
            return flowOf(items.filter { includeTypes == null || it.domain in includeTypes })
        }

        override suspend fun updateFavoritesAwaited(
            manuallySorted: List<SavableSearchable>,
            automaticallySorted: List<SavableSearchable>,
        ) {
            this.manuallySorted = manuallySorted
            this.automaticallySorted = automaticallySorted
        }

        override fun insert(searchable: SavableSearchable) = throw NotImplementedError()
        override fun upsert(
            searchable: SavableSearchable,
            visibility: VisibilityLevel?,
            pinned: Boolean?,
            launchCount: Int?,
            weight: Double?,
        ) = throw NotImplementedError()

        override fun update(
            searchable: SavableSearchable,
            visibility: VisibilityLevel?,
            pinned: Boolean?,
            launchCount: Int?,
            weight: Double?,
        ) = throw NotImplementedError()

        override fun replace(key: String, newSearchable: SavableSearchable) =
            throw NotImplementedError()

        override fun touch(searchable: SavableSearchable) = throw NotImplementedError()
        override fun getKeys(
            includeTypes: List<String>?,
            excludeTypes: List<String>?,
            minPinnedLevel: PinnedLevel,
            maxPinnedLevel: PinnedLevel,
            minVisibility: VisibilityLevel,
            maxVisibility: VisibilityLevel,
            limit: Int,
        ): Flow<List<String>> = throw NotImplementedError()

        override fun isPinned(searchable: SavableSearchable): Flow<Boolean> =
            throw NotImplementedError()

        override fun getVisibility(searchable: SavableSearchable): Flow<VisibilityLevel> =
            throw NotImplementedError()

        override fun updateFavorites(
            manuallySorted: List<SavableSearchable>,
            automaticallySorted: List<SavableSearchable>,
        ) = throw NotImplementedError()

        override fun sortByRelevance(keys: List<String>): Flow<List<String>> =
            throw NotImplementedError()

        override fun sortByWeight(keys: List<String>): Flow<List<String>> =
            throw NotImplementedError()

        override fun getWeights(keys: List<String>): Flow<Map<String, Double>> =
            throw NotImplementedError()

        override fun delete(searchable: SavableSearchable) = throw NotImplementedError()
        override fun getByKeys(keys: List<String>): Flow<List<SavableSearchable>> =
            throw NotImplementedError()

        override suspend fun cleanupDatabase(): Int = throw NotImplementedError()
    }

    private class FakeWallpaperStore : WallpaperStore {
        var state: WallpaperState? = null
        val applied = mutableListOf<Pair<String, WallpaperTarget>>()
        override suspend fun current(): WallpaperState? = state
        override suspend fun apply(image: String, target: WallpaperTarget): List<Diagnostic> {
            applied += image to target
            state = WallpaperState(image, target)
            return emptyList()
        }
        override suspend fun ensureRendered(): Boolean = false
        var pending: WallpaperState? = null
        override suspend fun pending(): WallpaperState? = pending
    }

    private class FakeAppRepository : AppRepository {
        val apps = mutableMapOf<Pair<String, UserHandle>, Application>()

        override fun findOne(packageName: String, user: UserHandle): Flow<Application?> {
            return flowOf(apps[packageName to user])
        }

        override fun findMany() = flowOf(persistentListOf<Application>())
        override fun search(query: String): Flow<List<Application>> {
            return flowOf(emptyList())
        }
    }

    private class FakeProfileResolver(
        var personal: Profile?,
        var work: Profile?,
    ) : ProfileResolver {
        override fun getProfile(type: Profile.Type): Profile? {
            return when (type) {
                Profile.Type.Personal -> personal
                Profile.Type.Work -> work
                else -> null
            }
        }

        override suspend fun getProfile(userHandle: UserHandle): Profile? {
            return listOfNotNull(personal, work).firstOrNull { it.userHandle == userHandle }
        }
    }

    private class FakeApplication(
        override val componentName: ComponentName,
        override val user: UserHandle,
    ) : Application {
        override val key: String = "app://${componentName.packageName}"
        override val domain: String = "app"
        override val label: String = componentName.packageName
        override val isSuspended: Boolean = false
        override val versionName: String? = null
        override val canUninstall: Boolean = false
        override val canShareApk: Boolean = false

        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?): Boolean = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon =
            throw NotImplementedError()

        override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
        override fun uninstall(context: Context) = throw NotImplementedError()
        override fun openAppDetails(context: Context) = throw NotImplementedError()
    }

    private companion object {
        fun userHandleFor(id: Int): UserHandle {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInt(id)
                parcel.setDataPosition(0)
                return UserHandle.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }
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

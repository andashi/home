package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.GlassContrast
import de.mm20.launcher2.config.GlassDefaults
import de.mm20.launcher2.config.GridLayoutConfig
import de.mm20.launcher2.config.SearchBarPosition
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import de.mm20.launcher2.preferences.seedSettingsFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each test seeds the settings file and creates its own [LauncherDataStore]
 * before any other store instance touches the file, satisfying the DataStore
 * "single active instance per file" constraint.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherConfigSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createGateway(seed: LauncherSettingsData = LauncherSettingsData()): LauncherConfigSettingsImpl {
        seedSettingsFile(context, seed)
        return LauncherConfigSettingsImpl(LauncherDataStore(context))
    }

    @Test
    fun `readState maps settings fields to ConfigState`() = runTest {
        val gateway = createGateway(
            LauncherSettingsData(
                iconsThemed = true,
                iconsForceThemed = true,
                iconsPack = "com.example.iconpack",
                searchBarBottom = true,
                homeScreenWidgets = true,
                homeGridColumns = 5,
                homeGridLocked = true,
                homeGridLabels = false,
                glassBlur = 12f,
                glassTint = 0.6f,
                glassRadius = 20f,
                glassContrast = GlassContrast.High,
                glassWallpaperBlur = false,
            )
        )

        val result = gateway.readState()

        assertEquals(true, result.themedIcons)
        assertEquals(true, result.enforceThemedIcons)
        assertEquals("com.example.iconpack", result.iconPack)
        assertEquals(SearchBarPosition.Bottom, result.searchBarPosition)
        assertEquals(true, result.widgetsEnabled)
        assertEquals(5, result.gridColumns)
        assertEquals(true, result.gridLocked)
        assertEquals(false, result.gridLabels)
        assertEquals(12f, result.glassBlur)
        assertEquals(0.6f, result.glassTint)
        assertEquals(20f, result.glassRadius)
        assertEquals(GlassContrast.High, result.glassContrast)
        assertEquals(false, result.glassWallpaperBlur)
    }

    @Test
    fun `fresh settings read back the documented glass defaults`() = runTest {
        val state = createGateway().readState()

        assertEquals(GlassDefaults.Blur, state.glassBlur)
        assertEquals(GlassDefaults.Tint, state.glassTint)
        assertEquals(GlassDefaults.Radius, state.glassRadius)
        assertEquals(GlassDefaults.Contrast, state.glassContrast)
        assertEquals(GlassDefaults.Labels, state.gridLabels)
    }

    @Test
    fun `apply SetGlass writes only the fields it carries`() = runTest {
        val gateway = createGateway()

        val tint = gateway.applyAndReturn(listOf(ConfigMutation.SetGlass(tint = 0.1f)))
        assertEquals(0.1f, tint.glassTint)
        assertEquals(GlassDefaults.Blur, tint.glassBlur)
        assertEquals(GlassDefaults.Radius, tint.glassRadius)
        assertEquals(GlassDefaults.Contrast, tint.glassContrast)

        val rest = gateway.applyAndReturn(
            listOf(ConfigMutation.SetGlass(blur = 0f, radius = 8f, contrast = GlassContrast.Low))
        )
        assertEquals(0.1f, rest.glassTint)
        assertEquals(0f, rest.glassBlur)
        assertEquals(8f, rest.glassRadius)
        assertEquals(GlassContrast.Low, rest.glassContrast)

        val wallpaper = gateway.applyAndReturn(listOf(ConfigMutation.SetGlass(wallpaperBlur = false)))
        assertEquals(false, wallpaper.glassWallpaperBlur)
        assertEquals(0.1f, wallpaper.glassTint)
    }

    @Test
    fun `apply SetGrid labels updates homeGridLabels and nothing else`() = runTest {
        val seed = LauncherSettingsData(homeGridColumns = 5, homeGridLocked = true)
        val gateway = createGateway(seed)

        val updated = gateway.applyAndReturn(listOf(ConfigMutation.SetGrid(labels = false)))

        assertEquals(seed.copy(homeGridLabels = false), updated)
    }

    @Test
    fun `readState maps searchBarBottom false to Top`() = runTest {
        val gateway = createGateway(LauncherSettingsData(searchBarBottom = false))

        assertEquals(SearchBarPosition.Top, gateway.readState().searchBarPosition)
    }

    @Test
    fun `apply SetIcons updates all icon fields`() = runTest {
        val gateway = createGateway()

        val updated = gateway.applyAndReturn(
            listOf(
                ConfigMutation.SetIcons(
                    themed = true,
                    enforceThemed = true,
                    pack = "com.example.iconpack",
                )
            )
        )

        assertEquals(true, updated.iconsThemed)
        assertEquals(true, updated.iconsForceThemed)
        assertEquals("com.example.iconpack", updated.iconsPack)
    }

    @Test
    fun `apply SetIcons with null fields leaves existing values untouched`() = runTest {
        val gateway = createGateway(
            LauncherSettingsData(
                iconsThemed = true,
                iconsForceThemed = true,
                iconsPack = "com.example.existing",
            )
        )

        val updated = gateway.applyAndReturn(listOf(ConfigMutation.SetIcons(pack = "com.example.new")))

        assertEquals(true, updated.iconsThemed)
        assertEquals(true, updated.iconsForceThemed)
        assertEquals("com.example.new", updated.iconsPack)
    }

    @Test
    fun `apply SetSearchBarPosition Bottom sets searchBarBottom true`() = runTest {
        val gateway = createGateway(LauncherSettingsData(searchBarBottom = false))

        val updated = gateway.applyAndReturn(
            listOf(ConfigMutation.SetSearchBarPosition(SearchBarPosition.Bottom))
        )

        assertEquals(true, updated.searchBarBottom)
    }

    @Test
    fun `apply SetSearchBarPosition Top sets searchBarBottom false`() = runTest {
        val gateway = createGateway(LauncherSettingsData(searchBarBottom = true))

        val updated = gateway.applyAndReturn(
            listOf(ConfigMutation.SetSearchBarPosition(SearchBarPosition.Top))
        )

        assertEquals(false, updated.searchBarBottom)
    }

    @Test
    fun `apply SetGrid updates columns and locked, and leaves an absent field alone`() = runTest {
        val gateway = createGateway(LauncherSettingsData(homeGridColumns = 4, homeGridLocked = false))

        val columns = gateway.applyAndReturn(listOf(ConfigMutation.SetGrid(columns = 5)))
        assertEquals(5, columns.homeGridColumns)
        assertEquals(false, columns.homeGridLocked)

        val locked = gateway.applyAndReturn(listOf(ConfigMutation.SetGrid(locked = true)))
        assertEquals(5, locked.homeGridColumns)
        assertEquals(true, locked.homeGridLocked)
    }

    @Test
    fun `apply SetGrid with only layouts changes no setting`() = runTest {
        val seed = LauncherSettingsData(homeGridColumns = 4, homeGridLocked = true)
        val gateway = createGateway(seed)

        val updated = gateway.applyAndReturn(
            listOf(ConfigMutation.SetGrid(layouts = mapOf("phone" to GridLayoutConfig(emptyList()))))
        )

        assertEquals(seed, updated)
    }

    @Test
    fun `apply SetWidgetsEnabled updates homeScreenWidgets`() = runTest {
        val gateway = createGateway(LauncherSettingsData(homeScreenWidgets = false))

        val updated = gateway.applyAndReturn(listOf(ConfigMutation.SetWidgetsEnabled(true)))

        assertEquals(true, updated.homeScreenWidgets)
    }

    @Test
    fun `apply combined mutations in a single call updates all fields`() = runTest {
        val gateway = createGateway()

        gateway.applyAndReturn(
            listOf(
                ConfigMutation.SetIcons(themed = true, pack = "com.example.iconpack"),
                ConfigMutation.SetSearchBarPosition(SearchBarPosition.Top),
                ConfigMutation.SetGrid(columns = 6, locked = true),
                ConfigMutation.SetWidgetsEnabled(true),
            )
        )

        val result = gateway.readState()
        assertEquals(true, result.themedIcons)
        assertEquals("com.example.iconpack", result.iconPack)
        assertEquals(SearchBarPosition.Top, result.searchBarPosition)
        assertEquals(6, result.gridColumns)
        assertEquals(true, result.gridLocked)
        assertEquals(true, result.widgetsEnabled)
    }

    @Test
    fun `apply ignores mutations not backed by settings`() = runTest {
        val gateway = createGateway(LauncherSettingsData(homeGridLocked = true))

        val updated = gateway.applyAndReturn(
            listOf(
                ConfigMutation.SetFavorites(listOf(Favorite("com.example.app"))),
                ConfigMutation.SetWallpaper("w.jpg", de.mm20.launcher2.config.WallpaperTarget.Both),
            )
        )

        assertEquals(LauncherSettingsData(homeGridLocked = true), updated)
    }

    @Test
    fun `apply result is visible via data flow after return`() = runTest {
        val seed = LauncherSettingsData()
        seedSettingsFile(context, seed)
        val store = LauncherDataStore(context)
        val gateway = LauncherConfigSettingsImpl(store)

        gateway.applyAndReturn(listOf(ConfigMutation.SetGrid(locked = true)))

        assertEquals(true, store.data.first().homeGridLocked)
    }

    /**
     * The Clear look (#76) needs themed icons: without them no monochrome
     * layer or pack glyph is used and every icon is the grey fallback (#86).
     */
    @Test
    fun `fresh settings have themed icons on`() = runTest {
        assertEquals(true, createGateway().readState().themedIcons)
    }

    @Test
    fun `a config that turns themed icons off still wins`() = runTest {
        val gateway = createGateway()
        val updated = gateway.applyAndReturn(listOf(ConfigMutation.SetIcons(themed = false)))
        assertEquals(false, updated.iconsThemed)
    }
}

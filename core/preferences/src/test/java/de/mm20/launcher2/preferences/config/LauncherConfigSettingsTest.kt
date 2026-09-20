package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.Favorite
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
import java.util.UUID

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
        val transparenciesId = UUID(1L, 2L)
        val gateway = createGateway(
            LauncherSettingsData(
                iconsThemed = true,
                iconsForceThemed = true,
                iconsPack = "com.example.iconpack",
                searchBarBottom = true,
                homeScreenDock = true,
                homeScreenWidgets = true,
                uiTransparenciesId = transparenciesId,
            )
        )

        val result = gateway.readState()

        assertEquals(true, result.state.themedIcons)
        assertEquals(true, result.state.enforceThemedIcons)
        assertEquals("com.example.iconpack", result.state.iconPack)
        assertEquals(SearchBarPosition.Bottom, result.state.searchBarPosition)
        assertEquals(true, result.state.dockEnabled)
        assertEquals(true, result.state.widgetsEnabled)
        assertEquals(transparenciesId, result.transparenciesId)
    }

    @Test
    fun `readState maps searchBarBottom false to Top`() = runTest {
        val gateway = createGateway(LauncherSettingsData(searchBarBottom = false))

        assertEquals(SearchBarPosition.Top, gateway.readState().state.searchBarPosition)
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
    fun `apply SetDockEnabled updates homeScreenDock`() = runTest {
        val gateway = createGateway(LauncherSettingsData(homeScreenDock = false))

        val updated = gateway.applyAndReturn(listOf(ConfigMutation.SetDockEnabled(true)))

        assertEquals(true, updated.homeScreenDock)
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
                ConfigMutation.SetDockEnabled(true),
                ConfigMutation.SetWidgetsEnabled(true),
            )
        )

        val result = gateway.readState()
        assertEquals(true, result.state.themedIcons)
        assertEquals("com.example.iconpack", result.state.iconPack)
        assertEquals(SearchBarPosition.Top, result.state.searchBarPosition)
        assertEquals(true, result.state.dockEnabled)
        assertEquals(true, result.state.widgetsEnabled)
    }

    @Test
    fun `apply ignores mutations not backed by settings`() = runTest {
        val gateway = createGateway(LauncherSettingsData(homeScreenDock = true))

        val updated = gateway.applyAndReturn(
            listOf(
                ConfigMutation.SetTransparency(name = "scheme", background = 0.5f),
                ConfigMutation.SetDockFavorites(listOf(Favorite("com.example.app"))),
                ConfigMutation.SetWidgets(listOf(de.mm20.launcher2.config.BuiltinWidget.Apps)),
            )
        )

        assertEquals(LauncherSettingsData(homeScreenDock = true), updated)
    }

    @Test
    fun `setTransparenciesId selects the transparency scheme`() = runTest {
        val gateway = createGateway()
        val id = UUID(3L, 4L)

        val updated = gateway.setTransparenciesIdAndReturn(id)

        assertEquals(id, updated.uiTransparenciesId)
        assertEquals(id, gateway.readState().transparenciesId)
    }

    @Test
    fun `apply result is visible via data flow after return`() = runTest {
        val seed = LauncherSettingsData()
        seedSettingsFile(context, seed)
        val store = LauncherDataStore(context)
        val gateway = LauncherConfigSettingsImpl(store)

        gateway.applyAndReturn(listOf(ConfigMutation.SetDockEnabled(true)))

        assertEquals(true, store.data.first().homeScreenDock)
    }
}

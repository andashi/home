package de.mm20.launcher2.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings the grid reads (D1, D3) and the one side effect the grid
 * removes: `home.dock.enabled` used to switch on the favorite affordances in
 * search results through `favoritesEnabled || homeScreenDock` (#46).
 */
@RunWith(RobolectricTestRunner::class)
class UiSettingsHomeGridTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings(seed: LauncherSettingsData): UiSettings {
        seedSettingsFile(context, seed)
        return UiSettings(LauncherDataStore(context))
    }

    @Test
    fun `favoritesEnabled no longer follows the dead dock flag`() = runTest {
        val settings = settings(LauncherSettingsData(favoritesEnabled = false, homeScreenDock = true))

        assertEquals(false, settings.favoritesEnabled.first())
    }

    @Test
    fun `favoritesEnabled follows its own field`() = runTest {
        assertEquals(true, settings(LauncherSettingsData(favoritesEnabled = true)).favoritesEnabled.first())
        assertEquals(false, settings(LauncherSettingsData(favoritesEnabled = false)).favoritesEnabled.first())
    }

    @Test
    fun `home grid columns and lock are exposed`() = runTest {
        val settings = settings(LauncherSettingsData(homeGridColumns = 6, homeGridLocked = true))

        assertEquals(6, settings.homeGridColumns.first())
        assertEquals(true, settings.homeGridLocked.first())
    }

    @Test
    fun `home grid defaults are four columns and unlocked`() = runTest {
        val settings = settings(LauncherSettingsData())

        assertEquals(4, settings.homeGridColumns.first())
        assertEquals(false, settings.homeGridLocked.first())
    }
}

package de.mm20.launcher2.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.GlassContrast
import de.mm20.launcher2.preferences.ui.GlassSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings the grid reads (D1, D3). `favoritesEnabled` follows its own
 * field alone: `home.dock.enabled` used to switch on the favorite affordances
 * in search results through `favoritesEnabled || homeScreenDock` (#46), and
 * the dock flag itself is gone since #3.
 */
@RunWith(RobolectricTestRunner::class)
class UiSettingsHomeGridTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings(seed: LauncherSettingsData): UiSettings {
        seedSettingsFile(context, seed)
        return UiSettings(LauncherDataStore(context))
    }

    @Test
    fun `favoritesEnabled follows its own field when on`() = runTest {
        assertEquals(true, settings(LauncherSettingsData(favoritesEnabled = true)).favoritesEnabled.first())
    }

    @Test
    fun `favoritesEnabled follows its own field when off`() = runTest {
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

    @Test
    fun `glass values are exposed with the defaults on fresh settings`() = runTest {
        assertEquals(
            GlassSettings(24f, 0.12f, 28f, GlassContrast.Medium, wallpaperBlur = true),
            settings(LauncherSettingsData()).glass.first(),
        )
    }

    @Test
    fun `glass follows the settings a config reload wrote`() = runTest {
        val settings = settings(
            LauncherSettingsData(
                glassBlur = 0f, glassTint = 0.6f, glassRadius = 12f, glassContrast = GlassContrast.High,
                glassWallpaperBlur = false,
                glassSearchWallpaperBlur = false,
            )
        )

        assertEquals(
            GlassSettings(0f, 0.6f, 12f, GlassContrast.High, wallpaperBlur = false, searchWallpaperBlur = false),
            settings.glass.first(),
        )
    }

    @Test
    fun `grid labels are on by default and follow the setting`() = runTest {
        assertEquals(true, settings(LauncherSettingsData()).homeGridLabels.first())
    }

    @Test
    fun `grid labels off is exposed`() = runTest {
        assertEquals(false, settings(LauncherSettingsData(homeGridLabels = false)).homeGridLabels.first())
    }
}

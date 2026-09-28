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

/** The settings the grid reads (D1, D3). */
@RunWith(RobolectricTestRunner::class)
class UiSettingsHomeGridTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings(seed: LauncherSettingsData): UiSettings {
        seedSettingsFile(context, seed)
        return UiSettings(LauncherDataStore(context))
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

    /**
     * #229: the pickers and editor sheets had a column count of their own,
     * labelled "grid columns" and touching no grid. They follow the home grid
     * now, and so does the favorites row's column source.
     */
    @Test
    fun `the pickers and the favorites row follow the home grid's columns`() = runTest {
        seedSettingsFile(context, LauncherSettingsData(homeGridColumns = 6))
        val store = LauncherDataStore(context)

        assertEquals(6, UiSettings(store).gridSettings.first().columnCount)
        assertEquals(6, de.mm20.launcher2.preferences.search.FavoritesSettings(store).first().columns)
    }

    /**
     * Review on #243: the fallback a screen draws with before the store emits
     * is the home grid's own default, or a picker opens one column too wide
     * for a frame on a default device.
     */
    @Test
    fun `the grid settings' fallback column count is the home grid's default`() {
        assertEquals(LauncherSettingsData().homeGridColumns, de.mm20.launcher2.preferences.ui.GridSettings().columnCount)
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

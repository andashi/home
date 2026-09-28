package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import de.mm20.launcher2.preferences.seedSettingsFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `appearance.dimWallpaper` (#229) at the bridge, in both directions: the
 * settings-contract round trip proves the field moves the key, not that true
 * reads as true, so an inverted bridge would pass it.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperDimSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the dim reads and writes as stored`() = runBlocking {
        seedSettingsFile(context, LauncherSettingsData(wallpaperDim = true))
        val store = LauncherDataStore(context)
        val bridge = LauncherConfigSettingsImpl(store)
        assertEquals(true, bridge.readState().wallpaperDimmed)

        bridge.apply(listOf(ConfigMutation.SetWallpaperDim(false)))
        assertEquals(false, store.data.first().wallpaperDim)
        assertEquals(false, bridge.readState().wallpaperDimmed)

        bridge.apply(listOf(ConfigMutation.SetWallpaperDim(true)))
        assertEquals(true, store.data.first().wallpaperDim)
    }
}

package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `appearance.dimWallpaper` (#229): a scrim over the wallpaper that the
 * launcher draws only while it is dark. Beside `glass` and `theme`, not under
 * `appearance.wallpaper`, which declares an image to install.
 */
class WallpaperDimTest {

    @Test
    fun `it parses as a boolean, and nothing else`() {
        fun parse(value: String) = ConfigParser.parse("""{ "schemaVersion": 2, "appearance": { "dimWallpaper": $value } }""")
        assertEquals(true, parse("true").config?.appearance?.dimWallpaper)
        assertTrue(!parse("\"yes\"").isSuccess)
    }

    @Test
    fun `a different dim is a mutation, the one in effect is none`() {
        fun diff(dimmed: Boolean, current: Boolean) =
            ConfigDiffer.diff(LauncherConfig(2, appearance = AppearanceConfig(dimWallpaper = dimmed)), ConfigState(wallpaperDimmed = current))

        assertEquals(listOf(ConfigMutation.SetWallpaperDim(true)), diff(true, false))
        assertEquals(listOf(ConfigMutation.SetWallpaperDim(false)), diff(false, true))
        assertEquals(emptyList<ConfigMutation>(), diff(true, true))
    }

    @Test
    fun `the default is no dim, and it is served back`() {
        assertEquals(false, ConfigState().toLauncherConfig().appearance?.dimWallpaper)
        assertEquals(true, ConfigState(wallpaperDimmed = true).toLauncherConfig().appearance?.dimWallpaper)
    }

    @Test
    fun `it is its own section`() {
        assertEquals("appearance.dimWallpaper", ConfigMutation.SetWallpaperDim(true).section)
    }
}

package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `appearance.theme.colorSource` (#229): where the launcher's Material You
 * colours come from - the system palette, which is a zone's colour (the
 * system overlay provisioning sets, #3), or colours extracted from the
 * wallpaper. `system` is the default. The stored setting is a boolean named
 * `uiCompatModeColors`; the file names what it does.
 */
class ThemeColorSourceTest {

    private fun parse(value: String) = ConfigParser.parse("""{ "schemaVersion": 2, "appearance": { "theme": { "colorSource": $value } } }""")

    @Test
    fun `system and wallpaper parse, anything else is an error`() {
        assertEquals(ThemeColorSource.System, parse("\"system\"").config?.appearance?.theme?.colorSource)
        assertEquals(ThemeColorSource.Wallpaper, parse("\"wallpaper\"").config?.appearance?.theme?.colorSource)
        assertTrue(!parse("\"monet\"").isSuccess)
        assertTrue("a boolean is not the key's form", !parse("true").isSuccess)
    }

    @Test
    fun `a different source is a mutation, the one in effect is none`() {
        fun diff(source: ThemeColorSource, current: ThemeColorSource) = ConfigDiffer.diff(
            LauncherConfig(2, appearance = AppearanceConfig(theme = ThemeConfig(colorSource = source))),
            ConfigState(themeColorSource = current),
        )

        assertEquals(listOf(ConfigMutation.SetTheme(colorSource = ThemeColorSource.Wallpaper)), diff(ThemeColorSource.Wallpaper, ThemeColorSource.System))
        assertEquals(emptyList<ConfigMutation>(), diff(ThemeColorSource.System, ThemeColorSource.System))
    }

    @Test
    fun `the default is the system palette, and it is served back`() {
        assertEquals(ThemeColorSource.System, ConfigState().themeColorSource)
        assertEquals(
            ThemeColorSource.Wallpaper,
            ConfigState(themeColorSource = ThemeColorSource.Wallpaper).toLauncherConfig().appearance?.theme?.colorSource,
        )
    }
}

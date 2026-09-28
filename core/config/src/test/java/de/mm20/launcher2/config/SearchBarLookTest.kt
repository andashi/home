package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `home.searchBar.hidden` and `home.searchBar.colors` (#229), the last two
 * gaps. Both reuse the contract's own vocabulary: `hidden` as the system bars
 * have it, and the colours as the system bars' icons name them - `dark` means
 * dark text on the resting bar, as it means dark icons on a bar.
 */
class SearchBarLookTest {

    private fun parse(searchBar: String) = ConfigParser.parse("""{ "schemaVersion": 2, "home": { "searchBar": $searchBar } }""")

    @Test
    fun `hidden is a boolean and the colours are auto, light or dark`() {
        val config = parse("""{ "hidden": true, "colors": "dark" }""").config?.home?.searchBar
        assertEquals(true, config?.hidden)
        assertEquals(SystemBarIcons.Dark, config?.colors)
        assertEquals(listOf("auto", "light", "dark"), SearchBarColorsSerializer.names)
        assertTrue(!parse("""{ "colors": "solid" }""").isSuccess)
        assertTrue(!parse("""{ "hidden": "yes" }""").isSuccess)
    }

    @Test
    fun `a different value is a mutation, the one in effect is none`() {
        fun diff(searchBar: SearchBarConfig, current: ConfigState = ConfigState()) =
            ConfigDiffer.diff(LauncherConfig(2, home = HomeConfig(searchBar = searchBar)), current)

        assertEquals(listOf(ConfigMutation.SetSearchBarHidden(true)), diff(SearchBarConfig(hidden = true)))
        assertEquals(emptyList<ConfigMutation>(), diff(SearchBarConfig(hidden = true), ConfigState(searchBarHidden = true)))
        assertEquals(listOf(ConfigMutation.SetSearchBarColors(SystemBarIcons.Light)), diff(SearchBarConfig(colors = SystemBarIcons.Light)))
        assertEquals(emptyList<ConfigMutation>(), diff(SearchBarConfig(colors = SystemBarIcons.Auto)))
    }

    @Test
    fun `the defaults are shown and auto, and both are served back`() {
        val defaults = ConfigState().toLauncherConfig().home?.searchBar
        assertEquals(false, defaults?.hidden)
        assertEquals(SystemBarIcons.Auto, defaults?.colors)

        val set = ConfigState(searchBarHidden = true, searchBarColors = SystemBarIcons.Dark).toLauncherConfig().home?.searchBar
        assertEquals(true, set?.hidden)
        assertEquals(SystemBarIcons.Dark, set?.colors)
    }

    @Test
    fun `both belong to the search bar's section`() {
        assertEquals("home.searchBar", ConfigMutation.SetSearchBarHidden(true).section)
        assertEquals("home.searchBar", ConfigMutation.SetSearchBarColors(SystemBarIcons.Auto).section)
    }
}

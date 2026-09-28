package de.mm20.launcher2.ui.component

import de.mm20.launcher2.preferences.SearchBarColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `home.searchBar.colors` to the resting bar's dark text (#229): `dark` and
 * `light` say it outright, `auto` follows the wallpaper. The last unpinned
 * link between the file and the bar; a wrong one would round-trip, draw a
 * colour and do the opposite of what the file asked, which only a table
 * against the consumer catches. Both the launcher and the settings preview
 * ask this one function.
 */
class SearchBarDarkContentTest {

    @Test
    fun `every stored value, over a wallpaper that wants dark text and one that does not`() {
        val expected = mapOf(
            (SearchBarColors.Dark to true) to true,
            (SearchBarColors.Dark to false) to true,
            (SearchBarColors.Light to true) to false,
            (SearchBarColors.Light to false) to false,
            (SearchBarColors.Auto to true) to true,
            (SearchBarColors.Auto to false) to false,
        )
        assertEquals(SearchBarColors.entries.size * 2, expected.size)

        val got = expected.keys.associateWith { (colors, preferDark) -> colors.hasDarkContent(preferDark) }

        assertEquals(expected, got)
    }
}

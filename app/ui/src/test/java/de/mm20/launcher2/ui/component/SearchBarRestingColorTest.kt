package de.mm20.launcher2.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.preferences.SearchBarStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The resting glass search bar takes its text colour from the wallpaper, in
 * every style and either theme (#229). The glass under it follows the
 * wallpaper, not the theme - measured on the emulator, #1c1c1c over a black
 * wallpaper and #f0f0e8 over a light one in both themes - so a colour taken
 * from the theme's surface was illegible whenever theme and wallpaper
 * disagreed: 1.16:1 for the old `Solid` style in both directions, against
 * 7.17 to 17.04:1 for the wallpaper rule.
 *
 * The colour read is the one the bar hands its content, through the menu
 * slot, so the case checks what the placeholder and icons are drawn in, not
 * a value beside them. It runs over every style there is, so a style that
 * brings back a theme colour fails here.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchBarRestingColorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class Case(val style: SearchBarStyle, val dark: Boolean, val darkColors: Boolean, val level: SearchBarLevel)

    /** Every case composed side by side in one composition; each records the colour its bar hands its content. */
    private fun contentColors(cases: List<Case>): Map<Case, Color> {
        val seen = mutableMapOf<Case, Color>()
        composeRule.setContent {
            Column {
                for (case in cases) {
                    MaterialTheme(colorScheme = if (case.dark) darkColorScheme() else lightColorScheme()) {
                        SearchBar(
                            style = case.style,
                            level = case.level,
                            value = "",
                            onValueChange = {},
                            darkColors = case.darkColors,
                            glass = true,
                            menu = { seen[case] = LocalContentColor.current },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("cases composed", cases.toSet(), seen.keys)
        return seen
    }

    private val wallpaperDark = Color(0, 0, 0, 180)

    @Test
    fun `at rest every style takes the wallpaper's text colour, in either theme`() {
        val cases = SearchBarStyle.entries.flatMap { style ->
            listOf(false, true).flatMap { dark -> listOf(false, true).map { Case(style, dark, it, SearchBarLevel.Resting) } }
        }
        val wrong = contentColors(cases).filter { (case, got) -> got != (if (case.darkColors) wallpaperDark else Color.White) }
            .map { (case, got) -> "${case.style}, ${if (case.dark) "dark" else "light"} theme, darkColors=${case.darkColors}: $got" }
        assertEquals("resting colours that do not follow the wallpaper", emptyList<String>(), wrong)
    }

    @Test
    fun `raised, the bar takes the theme's text colour`() {
        // A control: once raised the bar is a card above the content, and the
        // theme colour is right; it passes in both states.
        val cases = listOf(false, true).map { Case(SearchBarStyle.Transparent, it, darkColors = true, level = SearchBarLevel.Raised) }
        for ((case, got) in contentColors(cases)) {
            val scheme = if (case.dark) darkColorScheme() else lightColorScheme()
            assertEquals("${if (case.dark) "dark" else "light"} theme", scheme.onSurface, got)
        }
    }
}

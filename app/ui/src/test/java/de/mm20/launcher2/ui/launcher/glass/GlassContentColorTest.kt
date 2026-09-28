package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import de.mm20.launcher2.ui.theme.LauncherColorSchemes
import de.mm20.launcher2.ui.theme.LocalLauncherColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Text on glass takes its colours from the wallpaper, not the theme (#242).
 * The glass shows the blurred wallpaper under a thin tint, so it is dark over
 * a dark wallpaper and light over a light one in either theme - measured on
 * the emulator, #1c1c1c and #000000 over black, #ececec and #d0d0d0 over a
 * light one. Search's labels, result rows, chips and banners took the theme's
 * colours and measured 1.02 to 1.51:1 whenever theme and wallpaper disagreed,
 * which is what a fresh GrapheneOS image does out of the box.
 *
 * What is read is what the glass hands its content: the content colour and
 * the scheme a child colours itself from, onBackground (the grid label),
 * onSurface (rows, banners) and onSurfaceVariant.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassContentColorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class Case(val darkTheme: Boolean, val darkContentOverWallpaper: Boolean) {
        override fun toString() =
            "${if (darkTheme) "dark" else "light"} theme, ${if (darkContentOverWallpaper) "light" else "dark"} wallpaper"
    }

    private data class Seen(val content: Color, val onBackground: Color, val onSurface: Color, val onSurfaceVariant: Color)

    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    @Test
    fun `text on glass follows the wallpaper in either theme`() {
        val cases = listOf(false, true).flatMap { theme -> listOf(false, true).map { Case(theme, it) } }
        val seen = mutableMapOf<Case, Seen>()
        composeRule.setContent {
            Column {
                for (case in cases) {
                    val schemes = LauncherColorSchemes(light = light, dark = dark, darkTheme = case.darkTheme)
                    CompositionLocalProvider(
                        LocalLauncherColorSchemes provides schemes,
                        LocalPreferDarkContentOverWallpaper provides case.darkContentOverWallpaper,
                    ) {
                        MaterialTheme(colorScheme = schemes.theme) {
                            GlassSurface {
                                val scheme = MaterialTheme.colorScheme
                                seen[case] = Seen(LocalContentColor.current, scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("cases composed", cases.toSet(), seen.keys)

        val wrong = seen.filter { (case, got) ->
            val want = if (case.darkContentOverWallpaper) light else dark
            got != Seen(want.onSurface, want.onBackground, want.onSurface, want.onSurfaceVariant)
        }.map { (case, got) -> "$case: $got" }
        assertEquals("text colours on glass that do not follow the wallpaper", emptyList<String>(), wrong)
    }
}

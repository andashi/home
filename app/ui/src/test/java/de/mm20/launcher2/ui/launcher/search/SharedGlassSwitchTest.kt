package de.mm20.launcher2.ui.launcher.search

import de.mm20.launcher2.ui.launcher.glass.resultHighlight
import de.mm20.launcher2.ui.launcher.glass.LocalClearIcons
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.Banner
import de.mm20.launcher2.ui.launcher.glass.GlassSurfaceKey
import de.mm20.launcher2.ui.launcher.glass.LocalOnGlass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A component shared by the search screen, the settings screens and the
 * opaque Material sheets is glass only on the search screen (#91): inside an
 * opaque sheet a glass surface would be a hole down to the wallpaper.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedGlassSwitchTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun glassSurfaces(onGlass: Boolean, content: @Composable () -> Unit): Int {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOnGlass provides onGlass) { content() }
            }
        }
        return composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).fetchSemanticsNodes().size
    }

    @Composable
    private fun banner() = Banner(
        modifier = Modifier.testTag("banner"),
        text = "Contacts permission is required",
        icon = R.drawable.star_24px,
    )

    @Test
    fun `a banner on the search screen is a glass card`() {
        assertEquals(1, glassSurfaces(onGlass = true) { banner() })
    }

    @Test
    fun `a banner elsewhere stays a Material card`() {
        assertEquals(0, glassSurfaces(onGlass = false) { banner() })
    }

    @Test
    fun `the best match's highlight is a state layer on glass and upstream's color elsewhere`() {
        var onGlass = Color.Unspecified
        var elsewhere = Color.Unspecified
        var scheme = lightColorScheme()
        composeRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalClearIcons provides true) { onGlass = resultHighlight() }
                CompositionLocalProvider(LocalClearIcons provides false) { elsewhere = resultHighlight() }
            }
        }
        composeRule.waitForIdle()
        assertEquals(scheme.onSurface.copy(alpha = 0.12f), onGlass)
        assertEquals(scheme.surfaceVariant, elsewhere)
    }

    /** Review on #98: the settings screen's filters rendered glass chips on an opaque sheet. */
    @Test
    fun `search filters are glass chips on search and Material chips in settings`() {
        fun filters() = @Composable {
            de.mm20.launcher2.ui.launcher.search.filters.SearchFilters(
                filters = de.mm20.launcher2.search.SearchFilters(),
                onFiltersChange = {},
            )
        }
        assertEquals(1, glassSurfaces(onGlass = true, filters()))
    }

    @Test
    fun `search filters outside glass are Material chips`() {
        assertEquals(
            0,
            glassSurfaces(onGlass = false) {
                de.mm20.launcher2.ui.launcher.search.filters.SearchFilters(
                    filters = de.mm20.launcher2.search.SearchFilters(),
                    onFiltersChange = {},
                    settings = true,
                )
            },
        )
    }
}

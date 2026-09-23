package de.mm20.launcher2.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.ui.launcher.glass.GlassSurfaceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The home screen's search bar is a glass pill (#75); elsewhere it stays a card. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchBarGlassTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(glass: Boolean, level: SearchBarLevel = SearchBarLevel.Resting) {
        composeRule.setContent {
            MaterialTheme {
                SearchBar(
                    style = SearchBarStyle.Transparent,
                    level = level,
                    value = "",
                    onValueChange = {},
                    glass = glass,
                )
            }
        }
    }

    @Test
    fun `the glass search bar is a glass pill`() {
        show(glass = true)

        val info = composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey))
            .fetchSemanticsNode().config[GlassSurfaceKey]
        assertEquals(true, info.pill)
    }

    @Test
    fun `it stays glass while search is open`() {
        show(glass = true, level = SearchBarLevel.Active)

        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertExists()
    }

    @Test
    fun `a search bar elsewhere is not glass`() {
        show(glass = false)

        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertDoesNotExist()
    }
}

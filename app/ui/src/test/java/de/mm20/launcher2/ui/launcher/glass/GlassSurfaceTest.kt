package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.RenderedBackdrop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The surface itself (#75): what it draws with, per contrast and shape. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun style(contrast: Contrast) = GlassStyle.resolve(GlassInputs(24f, 0.35f, 28f, contrast))

    private fun show(contrast: Contrast = Contrast.Medium, pill: Boolean = false, backdrop: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassStyle provides style(contrast),
                    LocalGlassBackdrop provides if (backdrop) {
                        RenderedBackdrop(BackdropKey("sha", 1080, 2400, 8), ImageBitmap(135, 300))
                    } else {
                        null
                    },
                ) {
                    GlassSurface(Modifier.size(120.dp).testTag("surface"), pill = pill) {
                        Box(Modifier.size(10.dp))
                    }
                }
            }
        }
    }

    private fun info() = composeRule.onNodeWithTag("surface").fetchSemanticsNode().config[GlassSurfaceKey]

    @Test
    fun `a surface draws the resolved glass, no scrim at medium`() {
        show()
        assertEquals(GlassSurfaceInfo(tint = 0.35f, radiusDp = 28f, scrimAlpha = 0f, pill = false), info())
    }

    @Test
    fun `high contrast raises the tint and adds the scrim`() {
        show(Contrast.High)
        val info = info()
        assertEquals(0.5f, info.tint, 1e-4f)
        assertEquals(0.12f, info.scrimAlpha, 1e-4f)
    }

    @Test
    fun `a pill is a pill`() {
        show(pill = true)
        assertEquals(true, info().pill)
    }

    @Test
    fun `with a backdrop the surface draws its region`() {
        show(backdrop = true)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassBackdropRegion)).assertExists()
    }

    @Test
    fun `without a backdrop it is tint only, still a glass surface`() {
        show(backdrop = false)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassBackdropRegion)).assertDoesNotExist()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertExists()
    }
}

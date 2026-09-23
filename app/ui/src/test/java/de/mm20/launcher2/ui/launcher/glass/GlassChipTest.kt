package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Chips on the search screen (#91): glass pills, a selected one visibly stronger. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a chip is a glass pill and selected raises its tint`() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    GlassChip("Apps", onClick = {}, modifier = Modifier.testTag("off"))
                    GlassChip("Apps", onClick = {}, modifier = Modifier.testTag("on"), selected = true)
                }
            }
        }
        fun info(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[GlassSurfaceKey]
        val off = info("off")
        val on = info("on")

        assertEquals(true, off.pill)
        assertEquals(DefaultStyle.tint, off.tint, 1e-4f)
        assertTrue("selected ${on.tint} > unselected ${off.tint}", on.tint >= off.tint + 0.15f)
    }
}

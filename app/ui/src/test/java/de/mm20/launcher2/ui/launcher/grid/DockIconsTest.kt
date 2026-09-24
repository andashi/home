package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #111: the dock's layout puts each icon at its centred cell. */
@RunWith(AndroidJUnit4::class)
class DockIconsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** One cell in dp; small enough that the dock fits Robolectric's screen. */
    private val Cell = 50

    private fun show(count: Int, columns: Int, rows: Int) {
        composeRule.setContent {
            DockIcons(count, columns, rows, Modifier.size((columns * Cell).dp, (rows * Cell).dp).testTag("dock")) {
                repeat(count) { Box(Modifier.testTag("icon$it")) }
            }
        }
    }

    /** Left and top of an icon relative to the dock, in dp. */
    private fun offset(tag: String): Pair<Float, Float> {
        val dock = composeRule.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
        val icon = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density
        return (icon.left - dock.left) / density to (icon.top - dock.top) / density
    }

    private fun assertAt(tag: String, left: Float, top: Float) {
        val (l, t) = offset(tag)
        assertEquals("$tag left", left, l, 1f)
        assertEquals("$tag top", top, t, 1f)
    }

    @Test
    fun `three icons in a four-wide dock are centred`() {
        show(3, columns = 4, rows = 1)

        assertAt("icon0", 0.5f * Cell, 0f)
        assertAt("icon2", 2.5f * Cell, 0f)
    }

    @Test
    fun `three icons in a seven-high column sit in its middle`() {
        show(3, columns = 1, rows = 7)

        assertAt("icon0", 0f, 2f * Cell)
        assertAt("icon2", 0f, 4f * Cell)
    }

    /** Control: a full dock puts icon k in cell k. */
    @Test
    fun `a full dock places icon k in cell k`() {
        show(4, columns = 4, rows = 1)

        assertAt("icon0", 0f, 0f)
        assertAt("icon3", 3f * Cell, 0f)
    }
}

package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridGeometry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layout pass of the grid: cells land on their rectangles, and the
 * performance rule of the plan holds: an unrelated recomposition does not
 * recompose the grid, a change of the cells recomposes it once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class HomeGridLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    // 400 dp wide: cells of (400 - 24) / 4 = 94 dp, gaps of 8 dp.
    private val geometry = HomeGridGeometry.derive(FormFactor.Phone, 4, widthDp = 400f, heightDp = 600f)

    @Test
    fun `cells are placed at their rectangles`() {
        val cells = listOf(
            "a" to Span(0, 0, 2, 2),
            "b" to Span(2, 1, 1, 1),
            "dock" to Span(0, 4, 4, 1),
        )
        composeRule.setContent {
            Box(Modifier.size(400.dp, 600.dp)) {
                HomeGridLayout(geometry = geometry, cells = cells, modifier = Modifier.fillMaxSize()) { id ->
                    Box(Modifier.fillMaxSize().testTag("cell-$id"))
                }
            }
        }

        composeRule.onNodeWithContentDescription("grid-item:a")
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertTopPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(196.dp)   // 2 cells + 1 gap
            .assertHeightIsEqualTo(196.dp)
        composeRule.onNodeWithContentDescription("grid-item:b")
            .assertLeftPositionInRootIsEqualTo(204.dp) // 2 * (94 + 8)
            .assertTopPositionInRootIsEqualTo(102.dp)
            .assertWidthIsEqualTo(94.dp)
        composeRule.onNodeWithContentDescription("grid-item:dock")
            .assertTopPositionInRootIsEqualTo(408.dp)
            .assertWidthIsEqualTo(400.dp)
            .assertHeightIsEqualTo(94.dp)
        composeRule.onNodeWithTag("cell-a").assertWidthIsEqualTo(196.dp)
    }

    @Test
    fun `an unrelated recomposition leaves the grid alone, a cell change recomposes it once`() {
        var recompositions = 0
        var unrelated by mutableIntStateOf(0)
        val cells = mutableStateOf(listOf("a" to Span(0, 0, 2, 2)))

        composeRule.setContent {
            Column {
                UnrelatedCounter { unrelated }
                HomeGridLayout(
                    geometry = geometry,
                    cells = cells.value,
                    onRecomposed = { recompositions++ },
                ) { id -> Box(Modifier.fillMaxSize().testTag("cell-$id")) }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, recompositions)

        repeat(30) { unrelated++ }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("counter").assertExists()
        assertEquals(1, recompositions)

        cells.value = listOf("a" to Span(0, 0, 2, 2), "b" to Span(2, 0, 1, 1))
        composeRule.waitForIdle()
        assertEquals(2, recompositions)
    }
}

/** Reads the state in its own scope, so only this text recomposes when it changes. */
@Composable
private fun UnrelatedCounter(value: () -> Int) {
    Text(text = "${value()}", modifier = Modifier.testTag("counter"))
}

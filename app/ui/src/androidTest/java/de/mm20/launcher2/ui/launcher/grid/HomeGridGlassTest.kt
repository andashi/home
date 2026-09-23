package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.ui.base.ProvideAppWidgetHost
import de.mm20.launcher2.ui.launcher.glass.GlassSurfaceKey
import de.mm20.launcher2.ui.launcher.glass.LocalGlassStyle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Glass smoke on a device (#75, ADR 0005 L2): every card on the grid is a
 * glass surface, and `contrast: high` adds the scrim. Pixels are L3's job.
 */
@RunWith(AndroidJUnit4::class)
class HomeGridGlassTest {

    @get:Rule(order = 0)
    val koin = KoinGridRule(
        items = mapOf(
            HomeGridLayouts.Phone to listOf(
                gridItem("clock", 0, 0, 2, 2, position = 0),
                gridItem("note", 2, 0, 2, 1, position = 1),
                dockItem(0, 5, 4, 1),
            ),
        ),
    )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<GridTestActivity>()

    private fun show(contrast: Contrast) {
        val vm = koin.viewModel()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassStyle provides GlassStyle.resolve(GlassInputs(24f, 0.35f, 28f, contrast)),
                ) {
                    ProvideAppWidgetHost {
                        Box(Modifier.size(396.dp, 700.dp)) {
                            HomeGrid(viewModel = vm) { columns, rows -> Text("favorites ${columns}x$rows") }
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("grid-item:dock").fetchSemanticsNodes()
                .any { it.size.height > 0 }
        }
    }

    @Test
    fun everyCardAndTheDockAreGlass() {
        show(Contrast.Medium)

        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).assertCountEquals(3)
    }

    @Test
    fun highContrastAddsTheScrim() {
        show(Contrast.High)

        val surfaces = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(GlassSurfaceKey)).fetchSemanticsNodes()
        assertTrue(surfaces.isNotEmpty())
        assertTrue(surfaces.all { it.config[GlassSurfaceKey].scrimAlpha > 0f })
    }
}

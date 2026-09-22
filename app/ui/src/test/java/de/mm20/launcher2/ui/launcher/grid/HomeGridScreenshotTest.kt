package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridArrangement
import de.mm20.launcher2.homegrid.HomeGridGeometry
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridLayouts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 goldens of the grid (ADR 0005): the same items on a phone, on a fold's
 * inner display and on its cover, so a change to the geometry or the card is
 * seen as pixels. Cells are placeholders (see [PlaceholderCell]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGridScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val phoneItems = listOf(
        gridItem("weather", 0, 0, 2, 2, position = 0),
        gridItem("car", 2, 0, 2, 2, position = 1),
        gridItem("calendar", 0, 2, 2, 2, position = 2),
        gridItem("media", 2, 3, 2, 1, position = 3),
        dockItem(0, 5, 4, 1),
    )

    private val foldItems = listOf(
        gridItem("weather", 0, 0, 2, 2, HomeGridLayouts.Fold, position = 0),
        gridItem("car", 2, 0, 2, 2, HomeGridLayouts.Fold, position = 1),
        gridItem("calendar", 0, 2, 2, 2, HomeGridLayouts.Fold, position = 2),
        gridItem("media", 2, 3, 2, 1, HomeGridLayouts.Fold, position = 3),
        gridItem("home-assistant", 4, 0, 4, 2, HomeGridLayouts.Fold, position = 4),
        gridItem("notes", 4, 2, 2, 2, HomeGridLayouts.Fold, position = 5),
        gridItem("tasks", 6, 2, 2, 1, HomeGridLayouts.Fold, position = 6),
        dockItem(0, 5, 8, 1, HomeGridLayouts.Fold),
    )

    @Composable
    private fun Frame(formFactor: FormFactor, items: List<HomeGridItem>) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF5B93D9), Color(0xFF3F4A52)))),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val geometry = HomeGridGeometry.derive(formFactor, 4, maxWidth.value, maxHeight.value)
                    val cells = HomeGridArrangement.arrange(geometry, items).cells
                    val spans = cells.associate { it.item.id to it.span }
                    HomeGridLayout(
                        geometry = geometry,
                        cells = cells.map { it.item.id to it.span },
                        modifier = Modifier.fillMaxSize(),
                    ) { id -> PlaceholderCell(id, spans.getValue(id)) }
                }
            }
        }
    }

    private fun golden(formFactor: FormFactor, items: List<HomeGridItem>) {
        composeRule.setContent { Frame(formFactor, items) }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
    fun phone() = golden(FormFactor.Phone, phoneItems)

    @Test
    @Config(qualifiers = "w790dp-h820dp-normal-notlong-notround-port-420dpi")
    fun foldInner() = golden(FormFactor.Fold, foldItems)

    @Test
    @Config(qualifiers = "w412dp-h923dp-normal-long-notround-port-420dpi")
    fun foldCover() = golden(FormFactor.Fold, foldItems)
}

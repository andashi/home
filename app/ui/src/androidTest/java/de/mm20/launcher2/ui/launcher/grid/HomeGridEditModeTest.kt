package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.ui.base.ProvideAppWidgetHost
import de.mm20.launcher2.ui.locals.LocalSnackbarHostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edit mode on a device (plan section D): entry by long press that the
 * scaffold's gesture must not see, drag with visible push-down, resize
 * buttons that stop at the limits, remove with undo, the favorites editor,
 * a locked layout, and reduced motion.
 */
@RunWith(AndroidJUnit4::class)
class HomeGridEditModeTest {

    private val clock = gridItem("clock", 0, 0, 2, 2, position = 0)
    private val note = gridItem("note", 0, 2, 2, 1, position = 1)
    private val dock = dockItem(0, 5, 4, 1)

    @get:Rule(order = 0)
    val koin = KoinGridRule(
        items = mapOf(HomeGridLayouts.Phone to listOf(clock, note, dock)),
        limits = mapOf("clock" to SizeLimits(minW = 1, minH = 1, maxW = 3, maxH = 2)),
    )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<GridTestActivity>()

    private var parentLongPressed = false
    private var editFavoritesRequested = false
    private val snackbars = SnackbarHostState()

    private fun show(vm: HomeGridVM, reducedMotion: Boolean = true) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides snackbars) {
                    ProvideAppWidgetHost {
                        // A fixed 396x700 dp host: a 4x6 grid on every device, the
                        // geometry the assertions are written against.
                        Box(
                            Modifier
                                .size(396.dp, 700.dp)
                                .testTag("parent")
                                .pointerInput(Unit) {
                                    // The scaffold's configured long-press gesture, stood in for.
                                    detectTapGestures(onLongPress = { parentLongPressed = true })
                                },
                        ) {
                            HomeGrid(
                                viewModel = vm,
                                reducedMotion = reducedMotion,
                                onEditFavorites = { editFavoritesRequested = true },
                            ) { columns, rows -> Text("favorites ${columns}x$rows") }
                            SnackbarHost(snackbars)
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

    /** Long-presses the free area right of the note (columns 2..3, rows 2..4). */
    private fun longPressEmptyArea() {
        composeRule.onNodeWithTag("home-grid").performTouchInput {
            longClick(Offset(width * 0.8f, height * 0.55f))
        }
        composeRule.waitForIdle()
    }

    private fun HomeGridVM.spanOf(id: String) = state.value!!.cells.first { it.item.id == id }.span

    @Test
    fun longPressEntersEditModeAndTheParentGestureDoesNotFire() {
        val vm = koin.viewModel()
        show(vm)

        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").assertIsDisplayed()
        assertTrue(vm.editing.value)
        assertFalse(parentLongPressed)
    }

    @Test
    fun draggingACellOntoAnOccupiedCellPushesTheOccupantDown() {
        val vm = koin.viewModel()
        show(vm)
        longPressEmptyArea()
        val geometry = vm.geometry.value!!
        val pitchPx = with(composeRule.density) { (geometry.cellDp + geometry.gapDp).dp.toPx() }

        composeRule.onNodeWithContentDescription("grid-item:clock").performTouchInput {
            down(center)
            // Past the touch slop first, then two rows down, in small steps like a finger.
            repeat(10) { moveBy(Offset(0f, pitchPx * 0.2f)) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(0, 2), vm.spanOf("clock").let { listOf(it.x, it.y) })
        assertEquals(listOf(0, 4), vm.spanOf("note").let { listOf(it.x, it.y) })
    }

    @Test
    fun plusAndMinusStopAtTheLimits() {
        val vm = koin.viewModel()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-item:clock").performClick()
        composeRule.onNodeWithContentDescription("grid-resize-wider").performClick()
        composeRule.waitForIdle()
        assertEquals(3, vm.spanOf("clock").w)
        composeRule.onNodeWithContentDescription("grid-resize-wider").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("grid-resize-taller").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("grid-resize-narrower").performClick()
        composeRule.onNodeWithContentDescription("grid-resize-narrower").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.spanOf("clock").w)
        composeRule.onNodeWithContentDescription("grid-resize-narrower").assertDoesNotExist()
    }

    @Test
    fun removeThenUndoRestoresTheItem() {
        val vm = koin.viewModel()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-item:note").performClick()
        composeRule.onNodeWithContentDescription("grid-remove").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("clock", "dock"), vm.state.value!!.cells.map { it.item.id })

        composeRule.onNodeWithText(composeRule.activity.getString(de.mm20.launcher2.ui.R.string.action_undo))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(0, 2, 2, 1), vm.spanOf("note").let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun tappingTheFavoritesCellInEditModeOpensTheFavoritesEditor() {
        val vm = koin.viewModel()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-item:dock").performClick()
        composeRule.waitForIdle()

        assertTrue(editFavoritesRequested)
    }

    @Test
    fun aLockedLayoutRefusesEditMode() {
        koin.locked.value = true
        val vm = koin.viewModel()
        show(vm)

        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").assertDoesNotExist()
        assertFalse(vm.editing.value)
    }

    @Test
    fun reducedMotionDisablesTheWiggle() {
        val vm = koin.viewModel()
        show(vm, reducedMotion = true)
        longPressEmptyArea()

        composeRule.onNodeWithTag("home-grid").assert(SemanticsMatcher.expectValue(GridWiggling, false))
    }

    @Test
    fun theWiggleRunsWhenMotionIsAllowed() {
        val vm = koin.viewModel()
        show(vm, reducedMotion = false)
        longPressEmptyArea()

        composeRule.onNodeWithTag("home-grid").assert(SemanticsMatcher.expectValue(GridWiggling, true))
    }

    @Test
    fun doneWritesBackOnceAndLeavesEditMode() {
        val vm = koin.viewModel()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { !vm.editing.value }

        assertEquals(1, koin.writeBack.writes.size)
        composeRule.onNodeWithContentDescription("grid-edit-done").assertDoesNotExist()
    }
}

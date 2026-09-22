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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridWriteResult
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.base.ProvideAppWidgetHost
import de.mm20.launcher2.ui.locals.LocalSnackbarHostState
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Edit mode's chrome under Robolectric: the same gestures the device suite
 * drives, on the JVM so the coverage gate sees them. Entry by long press,
 * the edit bar, selection with its remove badge and resize buttons, the
 * drag with push-down, undo, the favorites editor probe, a locked layout,
 * and the wiggle's reduced-motion switch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class HomeGridEditUiTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()


    private val clock = gridItem("clock", 0, 0, 2, 2, position = 0)
    private val note = gridItem("note", 0, 2, 2, 1, position = 1)
    private val dock = dockItem(0, 5, 4, 1)

    private lateinit var repository: FakeHomeGridRepository
    private lateinit var writeBack: FakeWriteBack
    private val locked = MutableStateFlow(false)
    private val snackbars = SnackbarHostState()
    private var parentLongPressed = false
    private var editFavoritesRequested = false

    @Before
    fun setUp() {
        repository = FakeHomeGridRepository(mapOf(HomeGridLayouts.Phone to listOf(clock, note, dock)))
        writeBack = FakeWriteBack()
        loadKoinModules(
            module {
                single { ProfileManager(androidContext(), get()) }
            },
        )
    }

    private fun vm(): HomeGridVM {
        val uiSettings: UiSettings = GlobalContext.get().get()
        return HomeGridVM(
            repository = repository,
            uiSettings = uiSettings,
            formFactorDetector = FakeFormFactorDetector(FormFactor.Phone),
            measuredRows = MeasuredGridRows(),
            initFlag = FakeInitFlag(initialized = true),
            writeBack = writeBack,
            itemLimits = GridItemLimits { item, _ ->
                if (item.id == "clock") SizeLimits(minW = 1, minH = 1, maxW = 3, maxH = 2) else SizeLimits.Unbounded
            },
            locked = locked,
        )
    }

    private fun show(vm: HomeGridVM, reducedMotion: Boolean = true) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides snackbars) {
                    ProvideAppWidgetHost {
                        Box(
                            Modifier
                                .size(396.dp, 700.dp)
                                .testTag("parent")
                                .pointerInput(Unit) {
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
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("grid-item:dock").fetchSemanticsNodes()
                .any { it.size.height > 0 }
        }
    }

    private fun string(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    /** Long-presses the free area right of the note (columns 2..3, rows 2..4). */
    private fun longPressEmptyArea() {
        composeRule.onNodeWithTag("home-grid").performTouchInput {
            longClick(Offset(width * 0.8f, height * 0.55f))
        }
        composeRule.waitForIdle()
    }

    private fun HomeGridVM.spanOf(id: String) = state.value!!.cells.first { it.item.id == id }.span

    @Test
    fun `a long press enters edit mode and the parent's gesture does not fire`() {
        val vm = vm()
        show(vm)

        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("grid-edit-add").assertIsDisplayed()
        assertTrue(vm.editing.value)
        assertFalse(parentLongPressed)
    }

    @Test
    fun `done writes back once and leaves edit mode`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").performClick()
        composeRule.waitUntil(5_000) { !vm.editing.value }
        composeRule.waitForIdle()

        assertEquals(1, writeBack.writes.size)
        composeRule.onNodeWithContentDescription("grid-edit-done").assertDoesNotExist()
    }

    @Test
    fun `a skipped write-back shows its reason`() {
        writeBack.result = HomeGridWriteResult.Skipped("locked", "home.grid.locked is true")
        val vm = vm()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").performClick()
        composeRule.waitUntil(5_000) { !vm.editing.value }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.grid_write_back_skipped).replace("%1\$s", "home.grid.locked is true"))
            .assertIsDisplayed()
    }

    @Test
    fun `dragging a cell onto an occupied cell pushes the occupant down`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()
        val geometry = vm.geometry.value!!
        val pitchPx = with(composeRule.density) { (geometry.cellDp + geometry.gapDp).dp.toPx() }

        composeRule.onNodeWithContentDescription("grid-item:clock").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, pitchPx * 0.2f)) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(0, 2), vm.spanOf("clock").let { listOf(it.x, it.y) })
        assertEquals(listOf(0, 4), vm.spanOf("note").let { listOf(it.x, it.y) })
    }

    @Test
    fun `the resize buttons stop at the limits`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-item:clock").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("grid-resize-wider").performClick()
        composeRule.waitForIdle()
        assertEquals(3, vm.spanOf("clock").w)
        composeRule.onNodeWithContentDescription("grid-resize-wider").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("grid-resize-taller").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("grid-resize-shorter").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.spanOf("clock").h)
        composeRule.onNodeWithContentDescription("grid-resize-taller").performClick()
        composeRule.waitForIdle()
        assertEquals(2, vm.spanOf("clock").h)

        composeRule.onNodeWithContentDescription("grid-resize-narrower").performClick()
        composeRule.onNodeWithContentDescription("grid-resize-narrower").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.spanOf("clock").w)
        composeRule.onNodeWithContentDescription("grid-resize-narrower").assertDoesNotExist()
    }

    @Test
    fun `the corner handle resizes in whole cells`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()
        val geometry = vm.geometry.value!!
        val pitchPx = with(composeRule.density) { (geometry.cellDp + geometry.gapDp).dp.toPx() }

        composeRule.onNodeWithContentDescription("grid-item:note").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("grid-resize-handle").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(pitchPx * 0.12f, pitchPx * 0.12f)) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(3, 2), vm.spanOf("note").let { listOf(it.w, it.h) })
    }

    @Test
    fun `remove then undo restores the item`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-item:note").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("grid-remove").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("clock", "dock"), vm.state.value!!.cells.map { it.item.id })

        composeRule.onNodeWithText(string(R.string.action_undo)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(0, 2, 2, 1), vm.spanOf("note").let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun `a tap on free cells clears the selection`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()
        composeRule.onNodeWithContentDescription("grid-item:note").performClick()
        composeRule.waitForIdle()
        assertEquals("note", vm.selectedId.value)

        composeRule.onNodeWithTag("home-grid").performTouchInput { click(Offset(width * 0.8f, height * 0.55f)) }
        composeRule.waitForIdle()

        assertEquals(null, vm.selectedId.value)
        composeRule.onNodeWithContentDescription("grid-remove").assertDoesNotExist()
    }

    @Test
    fun `tapping the favorites cell in edit mode asks for the favorites editor`() {
        val vm = vm()
        show(vm)
        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-item:dock").performClick()
        composeRule.waitForIdle()

        assertTrue(editFavoritesRequested)
    }

    @Test
    fun `a locked layout refuses edit mode`() {
        locked.value = true
        val vm = vm()
        show(vm)

        longPressEmptyArea()

        composeRule.onNodeWithContentDescription("grid-edit-done").assertDoesNotExist()
        assertFalse(vm.editing.value)
    }

    @Test
    fun `reduced motion disables the wiggle and motion enables it`() {
        val vm = vm()
        show(vm, reducedMotion = true)
        longPressEmptyArea()
        composeRule.onNodeWithTag("home-grid").assert(SemanticsMatcher.expectValue(GridWiggling, false))
    }

    @Test
    fun `the wiggle runs when motion is allowed`() {
        val vm = vm()
        show(vm, reducedMotion = false)
        longPressEmptyArea()
        composeRule.onNodeWithTag("home-grid").assert(SemanticsMatcher.expectValue(GridWiggling, true))
    }
}

package de.mm20.launcher2.ui.launcher.grid

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.homegrid.DevicePostures
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.ui.base.ProvideAppWidgetHost
import java.io.FileInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Fold on a foldable device or AVD (D7): one layout, eight columns
 * opened, the left four on the cover. Posture is switched through the
 * device-state shell command; on a device without postures every test is
 * skipped with an assumption, so the phone job stays green.
 */
@RunWith(AndroidJUnit4::class)
class HomeGridFoldTest {

    private val digital = gridItem("digital", 0, 0, 3, 1, layout = HomeGridLayouts.Fold, position = 0)
    private val analog = gridItem("analog", 0, 1, 2, 2, layout = HomeGridLayouts.Fold, position = 1)
    private val right = gridItem("right", 5, 0, 3, 1, layout = HomeGridLayouts.Fold, position = 2)
    private val dock = dockItem(0, 5, 8, 1, layout = HomeGridLayouts.Fold, position = 3)
    private val phoneOnly = gridItem("phone-only", 0, 0, 1, 1, layout = HomeGridLayouts.Phone)

    @get:Rule(order = 0)
    val koin = KoinGridRule(
        items = mapOf(
            HomeGridLayouts.Fold to listOf(digital, analog, right, dock),
            HomeGridLayouts.Phone to listOf(phoneOnly),
        ),
        formFactor = FormFactor.Fold,
    )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<GridTestActivity>()

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(fd.fileDescriptor).use { it.readBytes().decodeToString() }.also { fd.close() }
    }

    /** Posture ids of this device, by name: they differ between foldables. */
    private lateinit var postures: DevicePostures

    /**
     * Switches the posture, then wakes and unlocks: on a two-display
     * foldable the cover comes up locked when the device closes (measured
     * on the Pixel-Fold-shaped AVD), and a locked screen hides the test
     * activity from the semantics tree.
     */
    private fun posture(state: Int) {
        shell("cmd device_state state $state")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        composeRule.waitForIdle()
    }

    @Before
    fun requireAFoldable() {
        val states = shell("cmd device_state print-states")
        val parsed = DevicePostures.parse(states)
        assumeTrue("no CLOSED/HALF_OPENED/OPENED postures on this device: $states", parsed != null)
        postures = parsed!!
        posture(postures.opened)
    }

    @After
    fun reset() {
        runCatching { shell("cmd device_state state reset") }
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun show(vm: HomeGridVM) {
        composeRule.setContent {
            MaterialTheme {
                ProvideAppWidgetHost {
                    Box(Modifier.fillMaxSize()) {
                        HomeGrid(viewModel = vm, reducedMotion = true) { columns, rows ->
                            Text("favorites ${columns}x$rows")
                        }
                    }
                }
            }
        }
        waitForColumns(vm, 8)
    }

    /**
     * Waits for the window to show [columns] and the dock cell to be laid
     * out. A display switch on a software-rendered CI emulator takes tens
     * of seconds the first time (the run on 2026-09-22 saw the first fold of
     * the job miss a 15 s bound while the later folds of the same run
     * passed), and the cover may lock again meanwhile, so the wait is long
     * and re-wakes the screen on the way.
     */
    private fun waitForColumns(vm: HomeGridVM, columns: Int) {
        val deadline = System.currentTimeMillis() + 90_000
        while (true) {
            val settled = runCatching {
                composeRule.waitUntil(5_000) {
                    vm.state.value?.geometry?.visibleColumns == columns &&
                            composeRule.onAllNodesWithContentDescription("grid-item:dock").fetchSemanticsNodes()
                                .any { it.size.height > 0 }
                }
            }.isSuccess
            if (settled) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "no $columns-column grid within 90 s; geometry=${vm.state.value?.geometry}",
                )
            }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    private fun HomeGridVM.spanOf(id: String) = state.value!!.cells.first { it.item.id == id }.span

    @Test
    fun openedShowsEightColumnsAndTheRightHalfItem() {
        val vm = koin.viewModel()
        show(vm)

        assertEquals(8, vm.state.value!!.geometry.visibleColumns)
        composeRule.onNodeWithContentDescription("grid-item:right").assertIsDisplayed()
        assertEquals(8, vm.spanOf("dock").w)
    }

    @Test
    fun closedShowsTheCoverWithFourColumnsAndWithoutTheRightHalfItem() {
        val vm = koin.viewModel()
        show(vm)

        posture(postures.closed)
        waitForColumns(vm, 4)

        composeRule.onNodeWithContentDescription("grid-item:right").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("grid-item:digital").assertIsDisplayed()
        assertEquals(listOf(0, 0, 3, 1), vm.spanOf("digital").let { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(4, vm.spanOf("dock").w)
        assertEquals(0, vm.spanOf("dock").x)
    }

    @Test
    fun anItemMovedIntoTheRightHalfStaysThereWhenClosedAndReturnsWhenOpened() {
        val vm = koin.viewModel()
        show(vm)

        runBlocking { vm.enterEdit() }
        assertTrue(vm.move("analog", 5, 2))
        runBlocking { vm.exitEdit() }
        composeRule.waitForIdle()

        val stored = runBlocking { koin.repository.observe(HomeGridLayouts.Fold).first() }
        assertEquals(listOf(5, 2), stored.first { it.id == "analog" }.let { listOf(it.x, it.y) })
        assertEquals(listOf(0, 0), stored.first { it.id == "digital" }.let { listOf(it.x, it.y) })

        posture(postures.closed)
        waitForColumns(vm, 4)
        composeRule.onNodeWithContentDescription("grid-item:analog").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("grid-item:digital").assertIsDisplayed()

        posture(postures.opened)
        waitForColumns(vm, 8)
        composeRule.onNodeWithContentDescription("grid-item:analog").assertIsDisplayed()
        assertEquals(listOf(5, 2), vm.spanOf("analog").let { listOf(it.x, it.y) })
    }

    @Test
    fun halfOpenedRendersAsOpened() {
        val vm = koin.viewModel()
        show(vm)

        posture(postures.halfOpened)
        waitForColumns(vm, 8)

        composeRule.onNodeWithContentDescription("grid-item:right").assertIsDisplayed()
        assertEquals(8, vm.state.value!!.geometry.visibleColumns)
    }

    @Test
    fun rotationKeepsTheGridAndPersistsNothing() {
        val vm = koin.viewModel()
        show(vm)

        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        waitForColumns(vm, 8)

        val geometry = vm.state.value!!.geometry
        assertEquals(HomeGridLayouts.Fold, geometry.layout)
        assertEquals(8, geometry.spec.columns)
        assertTrue(geometry.rows >= 1)
        assertTrue(koin.writeBack.writes.isEmpty())
        composeRule.onNodeWithContentDescription("grid-item:dock").assertIsDisplayed()
    }

    @Test
    fun aFoldNeverShowsThePhoneLayout() {
        val vm = koin.viewModel()
        show(vm)

        composeRule.onNodeWithContentDescription("grid-item:phone-only").assertDoesNotExist()
        posture(postures.closed)
        waitForColumns(vm, 4)
        composeRule.onNodeWithContentDescription("grid-item:phone-only").assertDoesNotExist()
        assertEquals(HomeGridLayouts.Fold, vm.state.value!!.geometry.layout)
    }
}

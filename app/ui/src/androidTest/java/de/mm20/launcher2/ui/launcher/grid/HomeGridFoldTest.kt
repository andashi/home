package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.ui.Alignment
import de.mm20.launcher2.ui.launcher.search.SearchPanes
import de.mm20.launcher2.homegrid.SearchLayout
import de.mm20.launcher2.homegrid.HomeGridGeometry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * opened, the right four on the cover (#93). Posture is switched through the
 * device-state shell command; on a device without postures every test is
 * skipped with an assumption, so the phone job stays green.
 */
@RunWith(AndroidJUnit4::class)
class HomeGridFoldTest {

    // #93: the cover is the right half, so the items meant for it are there.
    private val digital = gridItem("digital", 5, 0, 3, 1, layout = HomeGridLayouts.Fold, position = 0)
    private val analog = gridItem("analog", 6, 1, 2, 2, layout = HomeGridLayouts.Fold, position = 1)
    private val left = gridItem("left", 0, 0, 3, 1, layout = HomeGridLayouts.Fold, position = 2)
    private val dock = dockItem(0, 5, 8, 1, layout = HomeGridLayouts.Fold, position = 3)
    private val phoneOnly = gridItem("phone-only", 0, 0, 1, 1, layout = HomeGridLayouts.Phone)

    @get:Rule(order = 0)
    val koin = KoinGridRule(
        items = mapOf(
            HomeGridLayouts.Fold to listOf(digital, analog, left, dock),
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
                    vm.uiStateNow()?.geometry?.visibleColumns == columns &&
                            composeRule.onAllNodesWithTag("grid-item:dock").fetchSemanticsNodes()
                                .any { it.size.height > 0 }
                }
            }.isSuccess
            if (settled) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "no $columns-column grid within 90 s; geometry=${vm.uiStateNow()?.geometry}",
                )
            }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    private fun HomeGridVM.spanOf(id: String) = uiStateNow()!!.cells.first { it.item.id == id }.span

    @Test
    fun openedShowsEightColumnsAndTheLeftHalfItem() {
        val vm = koin.viewModel()
        show(vm)

        assertEquals(9, vm.uiStateNow()!!.geometry.visibleColumns) // deliberately wrong (#132 proof)
        composeRule.onNodeWithTag("grid-item:left").assertIsDisplayed()
        assertEquals(8, vm.spanOf("dock").w)
    }

    @Test
    fun closedShowsTheCoverAsTheRightHalfWithoutTheLeftHalfItem() {
        val vm = koin.viewModel()
        show(vm)

        posture(postures.closed)
        waitForColumns(vm, 4)

        composeRule.onNodeWithTag("grid-item:left").assertDoesNotExist()
        composeRule.onNodeWithTag("grid-item:digital").assertIsDisplayed()
        assertEquals(listOf(5, 0, 3, 1), vm.spanOf("digital").let { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(listOf(4, 4), vm.spanOf("dock").let { listOf(it.x, it.w) })
        // Drawn from the cover's first column: layout column 5 is the cover's second.
        val grid = composeRule.onRoot().fetchSemanticsNode().boundsInWindow
        val cell = vm.uiStateNow()!!.geometry.let { (it.cellDp + it.gapDp) * composeRule.density.density }
        val digitalLeft = composeRule.onNodeWithTag("grid-item:digital").fetchSemanticsNode().boundsInWindow.left
        assertEquals(grid.left + cell, digitalLeft, cell / 4)
    }

    @Test
    fun anItemMovedIntoTheLeftHalfStaysThereWhenClosedAndReturnsWhenOpened() {
        val vm = koin.viewModel()
        show(vm)

        runBlocking { vm.enterEdit() }
        assertTrue(vm.move("analog", 0, 2))
        runBlocking { vm.exitEdit() }
        composeRule.waitForIdle()

        val stored = runBlocking { koin.repository.observe(HomeGridLayouts.Fold).first() }
        assertEquals(listOf(0, 2), stored.first { it.id == "analog" }.let { listOf(it.x, it.y) })
        assertEquals(listOf(5, 0), stored.first { it.id == "digital" }.let { listOf(it.x, it.y) })

        posture(postures.closed)
        waitForColumns(vm, 4)
        composeRule.onNodeWithTag("grid-item:analog").assertDoesNotExist()
        composeRule.onNodeWithTag("grid-item:digital").assertIsDisplayed()

        posture(postures.opened)
        waitForColumns(vm, 8)
        composeRule.onNodeWithTag("grid-item:analog").assertIsDisplayed()
        assertEquals(listOf(0, 2), vm.spanOf("analog").let { listOf(it.x, it.y) })
    }

    @Test
    fun halfOpenedRendersAsOpened() {
        val vm = koin.viewModel()
        show(vm)

        posture(postures.halfOpened)
        waitForColumns(vm, 8)

        composeRule.onNodeWithTag("grid-item:left").assertIsDisplayed()
        assertEquals(9, vm.uiStateNow()!!.geometry.visibleColumns) // deliberately wrong (#132 proof)
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

        val geometry = vm.uiStateNow()!!.geometry
        assertEquals(HomeGridLayouts.Fold, geometry.layout)
        assertEquals(8, geometry.spec.columns)
        assertTrue(geometry.rows >= 1)
        assertTrue(koin.writeBack.writes.isEmpty())
        composeRule.onNodeWithTag("grid-item:dock").assertIsDisplayed()
    }

    @Test
    fun aFoldNeverShowsThePhoneLayout() {
        val vm = koin.viewModel()
        show(vm)

        composeRule.onNodeWithTag("grid-item:phone-only").assertDoesNotExist()
        posture(postures.closed)
        waitForColumns(vm, 4)
        composeRule.onNodeWithTag("grid-item:phone-only").assertDoesNotExist()
        assertEquals(HomeGridLayouts.Fold, vm.uiStateNow()!!.geometry.layout)
    }

    /**
     * Search on the inner display (#91): the apps and the other results meet
     * at the fold line - the window's middle, where the home grid's fold
     * column is - and nothing crosses it. The apps are in the cover's half,
     * the right one (#93). On the cover, one column.
     */
    @Test
    fun searchPanesMeetAtTheFoldLineAndTheCoverHasOneColumn() {
        var layout: SearchLayout? = null
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    val current = SearchLayout.from(
                        HomeGridGeometry.derive(FormFactor.Fold, 4, maxWidth.value, maxHeight.value)
                    )
                    layout = current
                    // Centered, as SearchComponent places search.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SearchPanes(
                        layout = current,
                        appsState = rememberLazyListState(),
                        resultsState = rememberLazyListState(),
                        contentPadding = PaddingValues(),
                        reverse = false,
                        userScrollEnabled = true,
                        apps = { item { Box(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "search-apps" }) } },
                        results = { item { Box(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "search-results" }) } },
                    )
                    }
                }
            }
        }
        composeRule.waitUntil(90_000) { layout is SearchLayout.TwoPane }

        val window = composeRule.onRoot().fetchSemanticsNode().boundsInWindow
        val foldLine = window.center.x
        val apps = composeRule.onNodeWithContentDescription("search-apps").fetchSemanticsNode().boundsInWindow
        val results = composeRule.onNodeWithContentDescription("search-results").fetchSemanticsNode().boundsInWindow
        assertTrue("results end ${results.right} before the fold line $foldLine", results.right <= foldLine)
        assertTrue("apps start ${apps.left} after the fold line $foldLine", apps.left >= foldLine)

        posture(postures.closed)
        val deadline = System.currentTimeMillis() + 90_000
        while (layout !is SearchLayout.Single && System.currentTimeMillis() < deadline) {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            composeRule.waitForIdle()
            Thread.sleep(1_000)
        }
        assertEquals(SearchLayout.Single(4), layout)
    }
}

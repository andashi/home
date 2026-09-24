package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridGeometry
import de.mm20.launcher2.homegrid.HomeGridInitLock
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
 * #118: on unfold the grid shows the dock at its place on the inner display
 * in the first frame after the window changed - not missing, not at the
 * cover's column window mid-screen. With the real view model: the round trip
 * through it took frames, which is what provisioning recorded.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class HomeGridUnfoldTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        loadKoinModules(module { single { ProfileManager(androidContext(), get()) } })
    }

    @Test
    fun `the first frame after unfolding shows the dock at column 7`() {
        val repository = FakeHomeGridRepository(
            mapOf(HomeGridLayouts.Fold to listOf(dockItem(7, 0, 1, 6, HomeGridLayouts.Fold))),
        )
        val vm = HomeGridVM(
            repository = repository,
            uiSettings = GlobalContext.get().get<UiSettings>(),
            formFactorDetector = FakeFormFactorDetector(FormFactor.Fold),
            measuredRows = MeasuredGridRows(),
            initFlag = FakeInitFlag(initialized = true),
            initLock = HomeGridInitLock(),
            writeBack = FakeWriteBack(),
            itemLimits = GridItemLimits.Unbounded,
            locked = MutableStateFlow(false),
        )
        var width by mutableStateOf(396.dp)
        composeRule.setContent {
            MaterialTheme {
                ProvideAppWidgetHost {
                    Box(Modifier.requiredSize(width, 700.dp)) {
                        HomeGrid(viewModel = vm, reducedMotion = true) { columns, rows -> Text("favorites ${columns}x$rows") }
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("grid-item:dock").fetchSemanticsNodes().any { it.size.height > 0 }
        }
        composeRule.mainClock.autoAdvance = false

        width = 790.dp
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()

        val inner = HomeGridGeometry.derive(FormFactor.Fold, 4, 790f, 700f)
        val grid = composeRule.onNodeWithTag("home-grid").fetchSemanticsNode().boundsInRoot
        val docks = composeRule.onAllNodesWithContentDescription("grid-item:dock").fetchSemanticsNodes()
        assertEquals("the dock is on screen in the first frame", 1, docks.size)
        val expected = grid.left + with(composeRule.density) { (7 * (inner.cellDp + inner.gapDp)).dp.toPx() }
        assertEquals("at column 7 of the inner display", expected, docks.single().boundsInRoot.left, 1f)
    }
}

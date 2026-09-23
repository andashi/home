package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridInitFlag
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.base.ProvideAppWidgetHost
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * The grid composable end to end under Robolectric: measured, arranged, cells
 * on screen with their content descriptions, the reconciler run against the
 * real host (which binds nothing here, so the AppWidget cell shows the
 * banner) and the view model built through its Koin factory.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class HomeGridTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()


    private val repository = FakeHomeGridRepository(
        mapOf(
            HomeGridLayouts.Phone to listOf(
                gridItem("clock", 0, 0, 3, 1, position = 0),
                dockItem(0, 5, 4, 1),
            ),
        ),
    )

    @Before
    fun setUp() {
        loadKoinModules(
            module {
                single { ProfileManager(androidContext(), get()) }
                single<de.mm20.launcher2.homegrid.HomeGridRepository> { repository }
                single<de.mm20.launcher2.homegrid.FormFactorDetector> { FakeFormFactorDetector(FormFactor.Phone) }
                single { MeasuredGridRows() }
                single<HomeGridInitFlag> { FakeInitFlag(initialized = true) }
                single { HomeGridInitLock() }
                single<de.mm20.launcher2.homegrid.HomeGridWriteBack> { FakeWriteBack() }
                single<GridItemLimits> { GridItemLimits.Unbounded }
            },
        )
    }

    private fun string(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `cells are on screen with their ids and the unbound widget shows the banner`() {
        val uiSettings: UiSettings = GlobalContext.get().get()
        val vm = HomeGridVM(
            repository = repository,
            uiSettings = uiSettings,
            formFactorDetector = FakeFormFactorDetector(FormFactor.Phone),
            measuredRows = MeasuredGridRows(),
            initFlag = FakeInitFlag(initialized = true),
            initLock = HomeGridInitLock(),
            writeBack = FakeWriteBack(),
            itemLimits = GridItemLimits.Unbounded,
            locked = flowOf(false),
        )

        composeRule.setContent {
            MaterialTheme {
                ProvideAppWidgetHost {
                    Box(Modifier.size(396.dp, 700.dp)) {
                        // The favorites widget needs the search stack; the
                        // grid does not, so the cell is a placeholder here.
                        HomeGrid(viewModel = vm) { columns, rows ->
                            Text("favorites ${columns}x$rows")
                        }
                    }
                }
            }
        }
        // The state arrives through several Main-dispatcher hops; wait for the
        // placed cell, not for the first idle frame.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("grid-item:dock").fetchSemanticsNodes()
                .any { it.size.height > 0 }
        }
        composeRule.onNodeWithContentDescription("grid-item:dock").assertIsDisplayed()
        composeRule.onNodeWithText("favorites 4x1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("grid-item:clock").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.app_widget_loading_failed)).assertIsDisplayed()
    }

    @Test
    fun `the view model factory resolves everything from Koin`() {
        val factory = HomeGridVM.factory()
        val vm = factory.create(HomeGridVM::class.java, androidx.lifecycle.viewmodel.CreationExtras.Empty)

        org.junit.Assert.assertEquals(FormFactor.Phone, vm.formFactor)
    }
}

package de.mm20.launcher2.ui.launcher.grid


import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.homegrid.HomeGridDefaults
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridWidgets
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeGridVMTest {

    @get:Rule
    val koin = KoinSettingsRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    private fun vm(
        formFactor: FormFactor,
        repository: FakeHomeGridRepository = FakeHomeGridRepository(),
        measuredRows: MeasuredGridRows = MeasuredGridRows(),
        initialized: Boolean = true,
    ): HomeGridVM {
        val uiSettings: UiSettings = GlobalContext.get().get()
        val flag = FakeInitFlag(initialized)
        return HomeGridVM(
            repository = repository,
            uiSettings = uiSettings,
            formFactorDetector = FakeFormFactorDetector(formFactor),
            measuredRows = measuredRows,
            initFlag = flag,
            initLock = HomeGridInitLock(),
            writeBack = FakeWriteBack(),
            itemLimits = GridItemLimits.Unbounded,
            locked = flowOf(false),
        )
    }

    @Test
    fun `the geometry follows the measured window and the configured columns`() = runTest(dispatcher) {
        val rows = MeasuredGridRows()
        val vm = vm(FormFactor.Phone, measuredRows = rows)

        vm.onWindowMeasured(396f, 800f)
        val geometry = vm.geometry.filterNotNull().first()

        assertEquals(HomeGridLayouts.Phone, geometry.layout)
        assertEquals(4, geometry.spec.columns)
        assertEquals(8, geometry.rows)
        assertEquals(93f, geometry.cellDp, 0.001f)
        // The config store reads the rows this device really has.
        assertEquals(8, rows.rows(HomeGridLayouts.Phone))
    }

    @Test
    fun `a fold's cover shows the right half of the fold layout`() = runTest(dispatcher) {
        val repository = FakeHomeGridRepository(
            mapOf(
                HomeGridLayouts.Fold to listOf(
                    gridItem("left", 0, 0, 2, 2, HomeGridLayouts.Fold, position = 0),
                    gridItem("right", 5, 0, 2, 2, HomeGridLayouts.Fold, position = 1),
                    dockItem(0, 5, 8, 1, HomeGridLayouts.Fold),
                ),
            ),
        )
        val vm = vm(FormFactor.Fold, repository)

        vm.onWindowMeasured(396f, 622f)
        val state = vm.state.filterNotNull().first()

        assertTrue(state.geometry.isCover)
        assertEquals(4, state.geometry.visibleColumns)
        assertEquals(8, state.geometry.spec.columns)
        // #93: the right half, in layout coordinates.
        assertEquals(listOf("right", "dock"), state.cells.map { it.item.id })
        assertEquals(Span(4, 5, 4, 1), state.cells.first { it.item.isFavorites }.span)
    }

    /** #93: something added on the cover lands in the columns the cover shows. */
    @Test
    fun `a widget added on the cover lands on the cover`() = runTest(dispatcher) {
        val repository = FakeHomeGridRepository(
            mapOf(HomeGridLayouts.Fold to listOf(gridItem("right", 4, 0, 2, 2, HomeGridLayouts.Fold, position = 0))),
        )
        val vm = vm(FormFactor.Fold, repository)
        vm.onWindowMeasured(396f, 622f)
        vm.state.filterNotNull().first()
        vm.enterEdit()

        assertTrue(vm.addWidget("com.example/.New", null, null, CellSize(2, 1), SizeLimits.Unbounded))

        val state = vm.state.filterNotNull().first { s -> s.cells.any { it.item.widget == "com.example/.New" } }
        assertEquals(Span(6, 0, 2, 1), state.cells.first { it.item.widget == "com.example/.New" }.span)
    }

    /**
     * #114 review: undoing the removal of the eight-wide dock on the cover puts
     * it back where it was; it may span the fold, the cover only clips it.
     */
    @Test
    fun `undo on the cover restores a dock wider than the cover`() = runTest(dispatcher) {
        val repository = FakeHomeGridRepository(
            mapOf(
                HomeGridLayouts.Fold to listOf(
                    gridItem("right", 5, 0, 2, 2, HomeGridLayouts.Fold, position = 0),
                    dockItem(0, 5, 8, 1, HomeGridLayouts.Fold),
                ),
            ),
        )
        val vm = vm(FormFactor.Fold, repository)
        vm.onWindowMeasured(396f, 622f)
        vm.state.filterNotNull().first()
        vm.enterEdit()

        val removed = vm.removeEditing(HomeGridDefaults.FavoritesId)!!
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value!!.cells.none { it.item.isFavorites })

        vm.restore(removed)
        dispatcher.scheduler.advanceUntilIdle()

        val dock = vm.state.value!!.cells.firstOrNull { it.item.isFavorites }?.item
        assertEquals(listOf(0, 5, 8, 1), dock?.let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun `a fold's inner display shows both halves`() = runTest(dispatcher) {
        val repository = FakeHomeGridRepository(
            mapOf(
                HomeGridLayouts.Fold to listOf(
                    gridItem("left", 0, 0, 2, 2, HomeGridLayouts.Fold, position = 0),
                    gridItem("right", 5, 0, 2, 2, HomeGridLayouts.Fold, position = 1),
                ),
            ),
        )
        val vm = vm(FormFactor.Fold, repository)

        vm.onWindowMeasured(790f, 780f)
        val state = vm.state.filterNotNull().first()

        assertFalse(state.geometry.isCover)
        assertEquals(8, state.geometry.visibleColumns)
        assertEquals(listOf("left", "right"), state.cells.map { it.item.id })
    }

    @Test
    fun `a phone reads the phone layout and ignores the fold layout`() = runTest(dispatcher) {
        val repository = FakeHomeGridRepository(
            mapOf(
                HomeGridLayouts.Phone to listOf(gridItem("p", 0, 0, position = 0)),
                HomeGridLayouts.Fold to listOf(gridItem("f", 0, 0, layout = HomeGridLayouts.Fold, position = 0)),
            ),
        )
        val vm = vm(FormFactor.Phone, repository)

        vm.onWindowMeasured(396f, 800f)

        assertEquals(listOf("p"), vm.state.filterNotNull().first().cells.map { it.item.id })
    }

    @Test
    fun `a never configured launcher gets the default favorites row once the window is known`() = runTest(dispatcher) {
        val repository = FakeHomeGridRepository()
        val vm = vm(FormFactor.Phone, repository, initialized = false)

        vm.onWindowMeasured(396f, 622f)
        val state = vm.state.filterNotNull().first { it.cells.isNotEmpty() }

        val dock = state.cells.single()
        assertEquals(HomeGridWidgets.Favorites, dock.item.widget)
        assertEquals(5, dock.span.y)
    }

    @Test
    fun `remove deletes the item from its layout`() = runTest(dispatcher) {
        val item = gridItem("clock", 0, 0, 2, 2, position = 0)
        val repository = FakeHomeGridRepository(mapOf(HomeGridLayouts.Phone to listOf(item)))
        val vm = vm(FormFactor.Phone, repository)

        vm.remove(item)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.observe(HomeGridLayouts.Phone).first().isEmpty())
    }

    @Test
    fun `bind records the host id the system dialog returned`() = runTest(dispatcher) {
        val item = gridItem("clock", 0, 0, 2, 2, position = 0)
        val repository = FakeHomeGridRepository(mapOf(HomeGridLayouts.Phone to listOf(item)))
        val vm = vm(FormFactor.Phone, repository)

        vm.bind(item, 77)
        dispatcher.scheduler.advanceUntilIdle()

        val bound = repository.observe(HomeGridLayouts.Phone).first().single()
        assertEquals(77, bound.appWidgetId)
        assertEquals(item.widget, bound.widget)
    }

    @Test
    fun `replace points the item at the new provider and host id`() = runTest(dispatcher) {
        val item = gridItem("clock", 0, 0, 2, 2, position = 0)
        val repository = FakeHomeGridRepository(mapOf(HomeGridLayouts.Phone to listOf(item)))
        val vm = vm(FormFactor.Phone, repository)

        vm.replace(item, "com.other/.Clock", 42)
        dispatcher.scheduler.advanceUntilIdle()

        val replaced = repository.observe(HomeGridLayouts.Phone).first().single()
        assertEquals("com.other/.Clock", replaced.widget)
        assertEquals(42, replaced.appWidgetId)
        assertEquals(listOf(0, 0, 2, 2), listOf(replaced.x, replaced.y, replaced.w, replaced.h))
    }
}

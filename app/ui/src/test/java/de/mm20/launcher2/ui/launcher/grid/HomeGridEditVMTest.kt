package de.mm20.launcher2.ui.launcher.grid

import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.homegrid.HomeGridCell
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridWriteBack
import de.mm20.launcher2.homegrid.HomeGridWriteResult
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner

/**
 * Edit mode as the view model sees it (plan section D): a working copy that
 * the engine edits, persisted once on Done through the write-back, refused
 * while the layout is locked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeGridEditVMTest {

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

    private val emptyColumn = object : WidgetRepository {
        override fun get(parent: UUID?, limit: Int, offset: Int): Flow<List<Widget>> = flowOf(emptyList())
        override fun update(widget: Widget) = Unit
        override fun create(widget: Widget, position: Int, parentId: UUID?) = Unit
        override fun delete(widget: Widget) = Unit
        override fun set(widgets: List<Widget>, parentId: UUID?) = Unit
        override suspend fun setAwaited(widgets: List<Widget>, parentId: UUID?) = Unit
        override fun exists(type: String): Flow<Boolean> = flowOf(false)
        override fun count(type: String): Flow<Int> = flowOf(0)
    }

    private val clock = gridItem("clock", 0, 0, 2, 2, position = 0)
    private val note = gridItem("note", 0, 2, 2, 1, position = 1)
    private val dock = dockItem(0, 5, 4, 1)

    private class Fixture(
        val vm: HomeGridVM,
        val repository: FakeHomeGridRepository,
        val writeBack: FakeWriteBack,
        val locked: MutableStateFlow<Boolean>,
        /** Every event the view model emitted, collected in the background. */
        val events: List<GridEditEvent>,
    )

    /**
     * A view model over fakes with its state and events collected in the
     * test's background scope, the way the composable would collect them.
     */
    private fun TestScope.fixture(
        items: List<HomeGridItem> = listOf(clock, note, dock),
        locked: Boolean = false,
        limits: Map<String, SizeLimits> = emptyMap(),
        leftovers: Int = 0,
        writeBack: FakeWriteBack = FakeWriteBack(),
    ): Fixture {
        val repository = FakeHomeGridRepository(mapOf(HomeGridLayouts.Phone to items))
        val lockedFlow = MutableStateFlow(locked)
        val uiSettings: UiSettings = GlobalContext.get().get()
        val vm = HomeGridVM(
            repository = repository,
            uiSettings = uiSettings,
            formFactorDetector = FakeFormFactorDetector(FormFactor.Phone),
            measuredRows = MeasuredGridRows(),
            seeder = FakeSeeding(leftovers),
            widgetRepository = emptyColumn,
            writeBack = writeBack,
            itemLimits = GridItemLimits { item, _ -> limits[item.id] ?: SizeLimits.Unbounded },
            locked = lockedFlow,
        )
        // A 4x6 phone grid.
        vm.onWindowMeasured(396f, 622f)
        val events = mutableListOf<GridEditEvent>()
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.events.collect { events += it } }
        testScheduler.advanceUntilIdle()
        return Fixture(vm, repository, writeBack, lockedFlow, events)
    }

    /**
     * The cells after every pending dispatch has run. The first value waits
     * for the settings DataStore (real IO, outside the test scheduler); later
     * values only need the test dispatcher to run.
     */
    private suspend fun HomeGridVM.cells(): List<HomeGridCell> {
        if (state.value == null) state.filterNotNull().first()
        dispatcher.scheduler.advanceUntilIdle()
        return state.value!!.cells
    }

    private suspend fun HomeGridVM.spanOf(id: String) = cells().first { it.item.id == id }.span

    @Test
    fun `enterEdit takes a working copy and editing becomes true`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.cells()

        assertTrue(f.vm.enterEdit())

        assertTrue(f.vm.editing.value)
        assertEquals(3, f.vm.cells().size)
        assertTrue(f.writeBack.writes.isEmpty())
    }

    @Test
    fun `enterEdit is refused while the layout is locked`() = runTest(dispatcher) {
        val f = fixture(locked = true)
        f.vm.cells()

        assertFalse(f.vm.enterEdit())

        assertFalse(f.vm.editing.value)
    }

    @Test
    fun `exitEdit writes the working copy back exactly once, in file order`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()
        f.vm.move("note", 2, 3)

        f.vm.exitEdit()

        assertFalse(f.vm.editing.value)
        assertEquals(1, f.writeBack.writes.size)
        val (layout, written) = f.writeBack.writes.single()
        assertEquals(HomeGridLayouts.Phone, layout)
        assertEquals(listOf("clock", "note", "dock"), written.map { it.id })
        assertEquals(written.indices.toList(), written.map { it.position })
        val movedNote = written.first { it.id == "note" }
        assertEquals(listOf(2, 3), listOf(movedNote.x, movedNote.y))
    }

    @Test
    fun `a skipped write-back is surfaced as an event`() = runTest(dispatcher) {
        val writeBack = FakeWriteBack(HomeGridWriteResult.Skipped("locked", "home.grid.locked is true"))
        val f = fixture(writeBack = writeBack)
        f.vm.cells()

        f.vm.enterEdit()
        f.vm.exitEdit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(GridEditEvent.WriteBackSkipped("locked", "home.grid.locked is true")), f.events)
    }

    @Test
    fun `move runs the engine with push-down on the working copy`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()

        // The clock (2x2 at 0,0) is dropped onto the note (2x1 at 0,2): the note goes below it.
        assertTrue(f.vm.move("clock", 0, 2))

        assertEquals(listOf(0, 2, 2, 2), f.vm.spanOf("clock").let { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(listOf(0, 4, 2, 1), f.vm.spanOf("note").let { listOf(it.x, it.y, it.w, it.h) })
        // Nothing reached the repository yet: the working copy is the only thing that changed.
        val stored = f.repository.observe(HomeGridLayouts.Phone).first().first { it.id == "clock" }
        assertEquals(listOf(0, 0), listOf(stored.x, stored.y))
    }

    @Test
    fun `a drag through occupied rows does not accumulate push-down`() = runTest(dispatcher) {
        // Seen on emulator-5556: dragging the digital clock three rows down
        // passed over the analog clock, which was pushed down at every
        // intermediate row until the last move overflowed and was rejected,
        // leaving the clock one row short. Every move of a drag is applied
        // to the layout as it was when the drag began.
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()

        f.vm.beginDrag("clock")
        assertTrue(f.vm.move("clock", 0, 1))
        assertTrue(f.vm.move("clock", 0, 2))
        assertTrue(f.vm.move("clock", 0, 3))
        f.vm.endDrag()

        assertEquals(listOf(0, 3), f.vm.spanOf("clock").let { listOf(it.x, it.y) })
        // The note (2x1 at row 2) is not under the clock's final rows 3..4: it is back where it was.
        assertEquals(listOf(0, 2), f.vm.spanOf("note").let { listOf(it.x, it.y) })
    }

    @Test
    fun `a write-back that throws keeps the session and reports it`() = runTest(dispatcher) {
        // Review on #70: exitEdit cleared the edit state before the write
        // and nothing caught an exception from it, so a SQLite error would
        // have crashed the launcher or left the working copy on screen with
        // no way to save it.
        val throwing = object : HomeGridWriteBack {
            var calls = 0
            override suspend fun write(layout: String, items: List<HomeGridItem>): HomeGridWriteResult {
                calls++
                throw IllegalStateException("disk full")
            }
        }
        val f = fixture(writeBack = FakeWriteBack())
        f.vm.cells()
        val vm = HomeGridVM(
            repository = f.repository,
            uiSettings = GlobalContext.get().get(),
            formFactorDetector = FakeFormFactorDetector(FormFactor.Phone),
            measuredRows = MeasuredGridRows(),
            seeder = FakeSeeding(),
            widgetRepository = emptyColumn,
            writeBack = throwing,
            itemLimits = GridItemLimits.Unbounded,
            locked = f.locked,
        )
        vm.onWindowMeasured(396f, 622f)
        val events = mutableListOf<GridEditEvent>()
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.cells()
        vm.enterEdit()
        vm.move("note", 2, 3)

        vm.exitEdit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, throwing.calls)
        assertTrue(vm.editing.value)
        assertEquals(listOf(2, 3), vm.spanOf("note").let { listOf(it.x, it.y) })
        assertEquals(listOf<GridEditEvent>(GridEditEvent.WriteBackFailed("disk full")), events)
    }

    @Test
    fun `restore never writes an overlap, the item is re-placed with push-down`() = runTest(dispatcher) {
        // Review on #70: remove the note, move the clock into its cells,
        // undo within the snackbar window. The note comes back through the
        // engine, below what now occupies its old cells.
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()
        val removed = f.vm.removeEditing("note")!!
        assertTrue(f.vm.move("clock", 0, 2))

        f.vm.restore(removed)
        f.vm.exitEdit()
        dispatcher.scheduler.advanceUntilIdle()

        // What was written, not what the arrangement corrected for display.
        val written = f.writeBack.writes.single().second
        assertEquals(setOf("clock", "note", "dock"), written.map { it.id }.toSet())
        for (a in written) for (b in written) if (a !== b) {
            val sa = Span(a.x, a.y, a.w, a.h); val sb = Span(b.x, b.y, b.w, b.h)
            assertFalse("${a.id} overlaps ${b.id}", sa.overlaps(sb))
        }
        // The clock keeps the cells the user moved it into; the note takes
        // the first free cells at or below its old row, here right of the clock.
        assertEquals(listOf(0, 2, 2, 2), written.first { it.id == "clock" }.let { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(listOf(2, 2), written.first { it.id == "note" }.let { listOf(it.x, it.y) })
    }

    @Test
    fun `two concurrent exitEdit calls write back once`() = runTest(dispatcher) {
        // Review on #70: Done tapped twice, or Done and Back, while the
        // write suspends must not write the layout twice.
        val gate = CompletableDeferred<Unit>()
        val slow = object : HomeGridWriteBack {
            var calls = 0
            override suspend fun write(layout: String, items: List<HomeGridItem>): HomeGridWriteResult {
                calls++
                gate.await()
                return HomeGridWriteResult.Written
            }
        }
        val f = fixture(writeBack = FakeWriteBack())
        f.vm.cells()
        val vm = HomeGridVM(
            repository = f.repository,
            uiSettings = GlobalContext.get().get(),
            formFactorDetector = FakeFormFactorDetector(FormFactor.Phone),
            measuredRows = MeasuredGridRows(),
            seeder = FakeSeeding(),
            widgetRepository = emptyColumn,
            writeBack = slow,
            itemLimits = GridItemLimits.Unbounded,
            locked = f.locked,
        )
        vm.onWindowMeasured(396f, 622f)
        backgroundScope.launch { vm.state.collect {} }
        vm.cells()
        vm.enterEdit()

        val first = launch { vm.exitEdit() }
        val second = launch { vm.exitEdit() }
        dispatcher.scheduler.advanceUntilIdle()
        gate.complete(Unit)
        first.join(); second.join()

        assertEquals(1, slow.calls)
        assertFalse(vm.editing.value)
    }

    @Test
    fun `resize clamps to the item's limits`() = runTest(dispatcher) {
        val f = fixture(limits = mapOf("clock" to SizeLimits(minW = 2, minH = 1, maxW = 3, maxH = 2)))
        f.vm.cells()
        f.vm.enterEdit()

        f.vm.resize("clock", 4, 3)
        assertEquals(listOf(3, 2), f.vm.spanOf("clock").let { listOf(it.w, it.h) })

        f.vm.resize("clock", 1, 1)
        assertEquals(listOf(2, 1), f.vm.spanOf("clock").let { listOf(it.w, it.h) })
        assertEquals(SizeLimits(2, 1, 3, 2), f.vm.limitsOf("clock"))
        assertEquals(SizeLimits.Unbounded, f.vm.limitsOf("dock"))
    }

    @Test
    fun `removeEditing takes the item out of the working copy and restore puts it back`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()

        val removed = f.vm.removeEditing("note")

        assertEquals("note", removed?.id)
        assertEquals(listOf("clock", "dock"), f.vm.cells().map { it.item.id })

        f.vm.restore(removed!!)

        assertEquals(listOf(0, 2, 2, 1), f.vm.spanOf("note").let { listOf(it.x, it.y, it.w, it.h) })
        assertNull(f.vm.removeEditing("missing"))
    }

    @Test
    fun `addWidget places the new item at the first free cells and gives it an id`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()

        assertTrue(f.vm.addWidget("com.example/.New", null, 55, CellSize(2, 1), SizeLimits(1, 1, 4, 2)))

        val added = f.vm.cells().map { it.item }.first { it.widget == "com.example/.New" }
        assertTrue(added.id, Regex("^[a-z0-9][a-z0-9-]{0,31}$").matches(added.id))
        assertEquals(55, added.appWidgetId)
        // First free 2x1 in reading order: right of the clock in row 0.
        assertEquals(listOf(2, 0, 2, 1), f.vm.spanOf(added.id).let { listOf(it.x, it.y, it.w, it.h) })
    }

    @Test
    fun `addWidget without room reports no-room and adds nothing`() = runTest(dispatcher) {
        val f = fixture(items = listOf(dockItem(0, 0, 4, 6)))
        f.vm.cells()
        f.vm.enterEdit()

        assertFalse(f.vm.addWidget("com.example/.New", null, null, CellSize(1, 1), SizeLimits.Unbounded))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf<GridEditEvent>(GridEditEvent.NoRoom), f.events)
        assertEquals(1, f.vm.cells().size)
    }

    @Test
    fun `seed leftovers are announced once on the first edit`() = runTest(dispatcher) {
        val f = fixture(leftovers = 2)
        f.vm.cells()

        f.vm.enterEdit()
        f.vm.exitEdit()
        f.vm.enterEdit()
        f.vm.exitEdit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf<GridEditEvent>(GridEditEvent.SeedLeftovers(2)), f.events)
    }

    @Test
    fun `the working copy is what the grid shows while editing, the repository after`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.cells()
        f.vm.enterEdit()
        f.vm.move("note", 2, 3)
        assertEquals(listOf(2, 3), f.vm.spanOf("note").let { listOf(it.x, it.y) })

        f.vm.exitEdit()
        dispatcher.scheduler.advanceUntilIdle()

        // The fake write-back does not touch the repository, so the grid shows the stored rows again.
        assertEquals(listOf(0, 2), f.vm.spanOf("note").let { listOf(it.x, it.y) })
        assertNull(f.vm.selectedId.value)
    }
}

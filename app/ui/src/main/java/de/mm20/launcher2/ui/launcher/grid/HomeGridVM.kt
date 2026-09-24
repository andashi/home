package de.mm20.launcher2.ui.launcher.grid

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.mm20.launcher2.homegrid.AppWidgetHostPort
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.FormFactorDetector
import de.mm20.launcher2.homegrid.GridGeometry
import de.mm20.launcher2.homegrid.HomeGridArrangement
import de.mm20.launcher2.homegrid.HomeGridCell
import de.mm20.launcher2.homegrid.HomeGridGeometry
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridReconciler
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.homegrid.HomeGridDefaults
import de.mm20.launcher2.homegrid.HomeGridInitFlag
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.homegrid.HomeGridWriteBack
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.GridItem
import de.mm20.launcher2.grid.GridLayout
import de.mm20.launcher2.grid.LayoutIssue
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.HomeGridWriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.homegrid.ReconcileReport
import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

/** What edit mode has to tell the user, shown as a snackbar by the grid. */
sealed class GridEditEvent {
    /** The database holds the layout, `launcher.json` does not (ADR 0003, section 5). */
    data class WriteBackSkipped(val code: String, val reason: String) : GridEditEvent()

    /** A widget could not be added: no free cells of its default size. */
    data object NoRoom : GridEditEvent()

    /** The write-back threw; the session is kept so the user can retry. */
    data class WriteBackFailed(val reason: String) : GridEditEvent()
}

/** What the composable draws: the window's geometry and the cells arranged for it. */
data class HomeGridUiState(
    val geometry: GridGeometry,
    val cells: List<HomeGridCell>,
)

/**
 * Derives the grid's geometry from the measured window (D1), arranges what
 * the repository holds for it, and gives a never-configured launcher its one
 * default, the favorites row (PR 5b). Constructor-injected so tests build it
 * with fakes; the composable obtains it through [factory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeGridVM(
    private val repository: HomeGridRepository,
    uiSettings: UiSettings,
    formFactorDetector: FormFactorDetector,
    private val measuredRows: MeasuredGridRows,
    private val initFlag: HomeGridInitFlag,
    private val initLock: HomeGridInitLock,
    private val writeBack: HomeGridWriteBack,
    private val itemLimits: GridItemLimits,
    private val locked: Flow<Boolean>,
) : ViewModel() {

    // ----- edit mode (plan section D) -----

    private val _editing = MutableStateFlow(false)

    /** True between [enterEdit] and [exitEdit]; the grid draws its edit chrome while it is. */
    val editing: StateFlow<Boolean> = _editing

    private val _selectedId = MutableStateFlow<String?>(null)

    /** The cell whose handles are shown; null when none. */
    val selectedId: StateFlow<String?> = _selectedId

    /** `home.grid.labels`: whether cells other than the dock show a label. */
    val labels: StateFlow<Boolean> = uiSettings.homeGridLabels
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _events = MutableSharedFlow<GridEditEvent>(extraBufferCapacity = 8)

    /** What to show the user; collected by the grid into a snackbar. */
    val events: SharedFlow<GridEditEvent> = _events

    /**
     * The layout as edit mode has changed it, or null outside edit mode.
     * Every edit changes this list; the repository and the file change once,
     * on [exitEdit]. A crash mid-edit loses the session's moves, which is
     * the price of writing the file once instead of per gesture.
     */
    private val working = MutableStateFlow<List<HomeGridItem>?>(null)

    /**
     * The working copy as it was when a drag began. Every move of the drag
     * is applied to this, not to the previous move's result, so an item the
     * ghost passed over returns to its place instead of being pushed again
     * at every row (which ended in an overflow that rejected the drop).
     */
    private var dragOrigin: List<HomeGridItem>? = null

    fun beginDrag(id: String) {
        dragOrigin = working.value
        _selectedId.value = id
    }

    fun endDrag() {
        dragOrigin = null
    }

    /**
     * Enters edit mode with a working copy of the layout, or returns false
     * when `home.grid.locked` is true (D3, D9). Edits change the working copy
     * only; [exitEdit] persists it once.
     */
    suspend fun enterEdit(): Boolean {
        if (locked.first()) return false
        val geometry = geometry.filterNotNull().first()
        working.value = repository.observe(geometry.layout).first()
        _selectedId.value = null
        _editing.value = true
        return true
    }

    /**
     * Leaves edit mode and writes the working copy back exactly once, on
     * Done. The edit state is cleared only after the write returned: a
     * write that throws (a SQLite error under the repository, say) keeps
     * the working copy and edit mode, reports [GridEditEvent.WriteBackFailed],
     * and the user taps Done again.
     */
    suspend fun exitEdit() = exitMutex.withLock {
        // Re-checked under the lock: a second Done (or Done and Back) that
        // waited here finds the copy already written and does nothing.
        val items = working.value ?: return@withLock
        val geometry = geometry.filterNotNull().first()
        val result = try {
            writeBack.write(geometry.layout, items.mapIndexed { index, item -> item.copy(position = index) })
        } catch (e: Exception) {
            Log.e(Tag, "write-back of ${geometry.layout} failed; staying in edit mode", e)
            _events.tryEmit(GridEditEvent.WriteBackFailed(e.message ?: e.javaClass.simpleName))
            return@withLock
        }
        _editing.value = false
        _selectedId.value = null
        working.value = null
        if (result is HomeGridWriteResult.Skipped) {
            _events.tryEmit(GridEditEvent.WriteBackSkipped(result.code, result.reason))
        }
    }

    /** Serialises [exitEdit]: one write-back per session, however often Done is tapped. */
    private val exitMutex = Mutex()

    fun select(id: String?) {
        _selectedId.value = id
    }

    /** The spans [id] may take; the favorites widget is unbounded. */
    fun limitsOf(id: String): SizeLimits {
        val item = working.value?.firstOrNull { it.id == id } ?: return SizeLimits.Unbounded
        val geometry = geometry.value ?: return SizeLimits.Unbounded
        return if (item.isFavorites) SizeLimits.Unbounded else itemLimits.limitsFor(item, geometry)
    }

    /**
     * Moves [id] to the cell ([x], [y]) in the working copy with push-down
     * (ADR 0001) and returns true when the item is now there.
     */
    fun move(id: String, x: Int, y: Int): Boolean {
        val items = dragOrigin ?: working.value ?: return false
        val geometry = geometry.value ?: return false
        val current = items.firstOrNull { it.id == id } ?: return false
        val result = GridLayout.move(geometry.spec, items.map { it.toGridItem(geometry) }, id, Span(x, y, current.w, current.h))
        working.value = items.applying(result.items)
        val moved = result.items.first { it.id == id }.span
        return moved.x == x && moved.y == y
    }

    /** Resizes [id] to [w] x [h] in the working copy, clamped to its limits, with push-down. */
    fun resize(id: String, w: Int, h: Int) {
        val items = working.value ?: return
        val geometry = geometry.value ?: return
        val result = GridLayout.resize(geometry.spec, items.map { it.toGridItem(geometry) }, id, w, h)
        working.value = items.applying(result.items)
    }

    /** Takes [id] out of the working copy and returns it for [restore] (undo). */
    fun removeEditing(id: String): HomeGridItem? {
        val items = working.value ?: return null
        val removed = items.firstOrNull { it.id == id } ?: return null
        working.value = items.filter { it.id != id }
        if (_selectedId.value == id) _selectedId.value = null
        return removed
    }

    /**
     * Puts an item removed by [removeEditing] back: at its old cells when
     * they are still free, else at the first free cells at or below its old
     * row (what the user moved into those cells meanwhile stays where it
     * was put), else anywhere free, else nowhere with a
     * [GridEditEvent.NoRoom]. The working copy never holds an overlap, so
     * neither does the file (review on #70).
     */
    fun restore(item: HomeGridItem) {
        val items = working.value ?: return
        if (items.any { it.id == item.id }) return
        val geometry = geometry.value ?: return
        val others = items.map { it.toGridItem(geometry) }
        val candidate = item.toGridItem(geometry)
        val spec = geometry.spec
        var found: Span? = null
        // Only issues that involve the candidate count: a stored item that
        // the display arrangement slides or clips (out of bounds on a
        // smaller window, say) must not block every cell.
        fun LayoutIssue.involves(id: String) = when (this) {
            is LayoutIssue.Overlap -> a == id || b == id
            is LayoutIssue.OutOfBounds -> this.id == id
            is LayoutIssue.CrossesFold -> this.id == id
            is LayoutIssue.BelowMinimum -> this.id == id
            is LayoutIssue.Overflow -> this.id == id
        }
        fun fits(span: Span) = GridLayout.validate(spec, others + candidate.copy(span = span))
            .none { it.involves(item.id) }
        // Its own cells first, checked against the whole layout: a dock that
        // spans the fold comes back as it was, the cover only clips it
        // (review on #114).
        val original = Span(item.x, item.y, item.w, item.h)
        if (fits(original)) found = original
        // Else only the columns this window shows: on the cover the item
        // comes back where the user can see it (#93).
        val window = geometry.visibleRange
        if (found == null) {
            search@ for (y in item.y until spec.rows) {
                for (x in window.first..(window.last + 1 - item.w)) {
                    val span = Span(x, y, item.w, item.h)
                    if (fits(span)) {
                        found = span
                        break@search
                    }
                }
            }
        }
        val span = found
            ?: GridLayout.place(spec, others, candidate, columns = window)?.span
            // Wider than the window: anywhere in the layout, clipped on the cover.
            ?: GridLayout.place(spec, others, candidate)?.span
        if (span == null) {
            _events.tryEmit(GridEditEvent.NoRoom)
            return
        }
        working.value = (items + item.copy(x = span.x, y = span.y, w = span.w, h = span.h)).sortedBy { it.position }
    }

    /**
     * Adds a widget at the first free cells of its default span; false, with
     * a [GridEditEvent.NoRoom], when nothing fits.
     */
    fun addWidget(
        widget: String,
        profile: String?,
        appWidgetId: Int?,
        default: CellSize,
        limits: SizeLimits,
    ): Boolean {
        val items = working.value ?: return false
        val geometry = geometry.value ?: return false
        val id = newItemId(items)
        val placed = GridLayout.place(
            geometry.spec,
            items.map { it.toGridItem(geometry) },
            GridItem(id, Span(0, 0, default.w, default.h), limits, mayCrossFold = false),
            // On the cover, where the user can see it (#93).
            columns = geometry.visibleRange,
        )
        if (placed == null) {
            _events.tryEmit(GridEditEvent.NoRoom)
            return false
        }
        working.value = items + HomeGridItem(
            layout = geometry.layout,
            id = id,
            widget = widget,
            profile = profile,
            x = placed.span.x,
            y = placed.span.y,
            w = placed.span.w,
            h = placed.span.h,
            appWidgetId = appWidgetId,
            position = items.size,
        )
        _selectedId.value = id
        return true
    }

    private fun HomeGridItem.toGridItem(geometry: GridGeometry) = GridItem(
        id = id,
        span = Span(x, y, w, h),
        limits = if (isFavorites) SizeLimits.Unbounded else itemLimits.limitsFor(this, geometry),
        mayCrossFold = isFavorites,
    )

    /** The working copy with the spans the engine produced; items the engine dropped are kept where they were. */
    private fun List<HomeGridItem>.applying(result: List<GridItem>): List<HomeGridItem> {
        val spans = result.associate { it.id to it.span }
        return map { item ->
            val span = spans[item.id] ?: return@map item
            if (span.x == item.x && span.y == item.y && span.w == item.w && span.h == item.h) item
            else item.copy(x = span.x, y = span.y, w = span.w, h = span.h)
        }
    }

    /** `w-<n>`: matches the contract's id pattern and is unique in the layout. */
    private fun newItemId(items: List<HomeGridItem>): String {
        val taken = items.map { it.id }.toSet()
        var n = items.size + 1
        while ("w-$n" in taken) n++
        return "w-$n"
    }

    val formFactor: FormFactor = formFactorDetector.detect()

    /** The usable window in dp, set by the composable once it is measured. */
    private val window = MutableStateFlow<Pair<Float, Float>?>(null)

    val geometry: StateFlow<GridGeometry?> =
        combine(window, uiSettings.homeGridColumns) { size, columns ->
            size?.let { (width, height) ->
                HomeGridGeometry.derive(formFactor, columns, width, height)
            }
        }
            // The config store derives its `grid-overflow` diagnostics from
            // the rows this device really has (GridRowsSource).
            .onEach { geometry ->
                if (geometry != null) {
                    measuredRows.update(geometry.layout, geometry.rows)
                    Log.i(
                        Tag,
                        "geometry ${geometry.layout}: ${geometry.spec.columns}x${geometry.rows} " +
                                "(visible ${geometry.visibleColumns}, cover=${geometry.isCover}, cell ${geometry.cellDp} dp)",
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val state: StateFlow<HomeGridUiState?> = geometry
        .filterNotNull()
        .flatMapLatest { geometry ->
            combine(repository.observe(geometry.layout), working) { stored, edited ->
                val arranged = HomeGridArrangement.arrange(geometry, edited ?: stored)
                for (issue in arranged.issues) {
                    Log.w(Tag, "layout ${geometry.layout}: corrected $issue")
                }
                HomeGridUiState(geometry, arranged.cells)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    init {
        viewModelScope.launch {
            val first = geometry.filterNotNull().first()
            HomeGridDefaults.ensureFavoritesRow(
                repository, initFlag, initLock, first.layout, columns = first.spec.columns, rows = first.spec.rows,
            )
        }
    }

    fun onWindowMeasured(widthDp: Float, heightDp: Float) {
        window.value = widthDp to heightDp
    }

    /** Binds what needs binding and releases what nothing references; see [HomeGridReconciler]. */
    suspend fun reconcile(port: AppWidgetHostPort): ReconcileReport {
        return HomeGridReconciler(repository, port).reconcile()
    }

    fun remove(item: HomeGridItem) {
        viewModelScope.launch { repository.delete(item.layout, item.id) }
    }

    /** Records the host id the system's bind dialog gave an item (see `BindProviderContract`). */
    fun bind(item: HomeGridItem, appWidgetId: Int) {
        viewModelScope.launch { repository.setAppWidgetId(item.layout, item.id, appWidgetId) }
    }

    /** Points an item at another provider, already bound to [appWidgetId]. */
    fun replace(item: HomeGridItem, widget: String, appWidgetId: Int) {
        viewModelScope.launch {
            val items = repository.observe(item.layout).first()
            repository.replace(
                item.layout,
                items.map {
                    if (it.id == item.id) it.copy(widget = widget, appWidgetId = appWidgetId) else it
                },
            )
        }
    }

    companion object {
        private const val Tag = "HomeGridVM"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val koin = GlobalContext.get()
                HomeGridVM(
                    repository = koin.get(),
                    uiSettings = koin.get(),
                    formFactorDetector = koin.get(),
                    measuredRows = koin.get(),
                    initFlag = koin.get(),
                    initLock = koin.get(),
                    writeBack = koin.get(),
                    itemLimits = koin.get(),
                    locked = koin.get<UiSettings>().homeGridLocked,
                )
            }
        }
    }
}

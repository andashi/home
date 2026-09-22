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
import de.mm20.launcher2.homegrid.HomeGridSeeder
import de.mm20.launcher2.homegrid.HomeGridSeeding
import de.mm20.launcher2.homegrid.HomeGridWriteBack
import de.mm20.launcher2.homegrid.GridItemLimits
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.homegrid.ReconcileReport
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.widgets.WidgetRepository
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
import org.koin.core.context.GlobalContext

/** What edit mode has to tell the user, shown as a snackbar by the grid. */
sealed class GridEditEvent {
    /** The database holds the layout, `launcher.json` does not (ADR 0003, section 5). */
    data class WriteBackSkipped(val code: String, val reason: String) : GridEditEvent()

    /** Widgets of the old column that found no room when the grid was seeded. */
    data class SeedLeftovers(val count: Int) : GridEditEvent()

    /** A widget could not be added: no free cells of its default size. */
    data object NoRoom : GridEditEvent()
}

/** What the composable draws: the window's geometry and the cells arranged for it. */
data class HomeGridUiState(
    val geometry: GridGeometry,
    val cells: List<HomeGridCell>,
)

/**
 * Derives the grid's geometry from the measured window (D1), arranges what
 * the repository holds for it, and runs the one-time seeding of the old
 * widget column. Constructor-injected so tests build it with fakes; the
 * composable obtains it through [factory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeGridVM(
    private val repository: HomeGridRepository,
    uiSettings: UiSettings,
    formFactorDetector: FormFactorDetector,
    private val measuredRows: MeasuredGridRows,
    private val seeder: HomeGridSeeding,
    private val widgetRepository: WidgetRepository,
    private val writeBack: HomeGridWriteBack,
    private val itemLimits: GridItemLimits,
    private val locked: Flow<Boolean>,
) : ViewModel() {

    // ----- edit mode (PR 5) -----

    private val _editing = MutableStateFlow(false)

    /** True between [enterEdit] and [exitEdit]; the grid draws its edit chrome while it is. */
    val editing: StateFlow<Boolean> = _editing

    private val _selectedId = MutableStateFlow<String?>(null)

    /** The cell whose handles are shown; null when none. */
    val selectedId: StateFlow<String?> = _selectedId

    private val _events = MutableSharedFlow<GridEditEvent>(extraBufferCapacity = 8)

    /** What to show the user; collected by the grid into a snackbar. */
    val events: SharedFlow<GridEditEvent> = _events

    /**
     * Enters edit mode with a working copy of the layout, or returns false
     * when `home.grid.locked` is true (D3, D9). Edits change the working copy
     * only; [exitEdit] persists it once.
     */
    suspend fun enterEdit(): Boolean = TODO("PR 5")

    /** Leaves edit mode and writes the working copy back exactly once, on Done. */
    suspend fun exitEdit(): Unit = TODO("PR 5")

    fun select(id: String?) {
        _selectedId.value = id
    }

    /** The spans [id] may take; the favorites widget is unbounded. */
    fun limitsOf(id: String): SizeLimits = TODO("PR 5")

    /**
     * Moves [id] to the cell ([x], [y]) in the working copy with push-down
     * (ADR 0001) and returns true when the item is now there.
     */
    fun move(id: String, x: Int, y: Int): Boolean = TODO("PR 5")

    /** Resizes [id] to [w] x [h] in the working copy, clamped to its limits, with push-down. */
    fun resize(id: String, w: Int, h: Int): Unit = TODO("PR 5")

    /** Takes [id] out of the working copy and returns it for [restore] (undo). */
    fun removeEditing(id: String): HomeGridItem? = TODO("PR 5")

    /** Puts an item removed by [removeEditing] back at its cells. */
    fun restore(item: HomeGridItem): Unit = TODO("PR 5")

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
    ): Boolean = TODO("PR 5")

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
            repository.observe(geometry.layout).map { items ->
                val arranged = HomeGridArrangement.arrange(geometry, items)
                for (issue in arranged.issues) {
                    Log.w(Tag, "layout ${geometry.layout}: corrected $issue")
                }
                HomeGridUiState(geometry, arranged.cells)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    init {
        viewModelScope.launch {
            seeder.seedIfNeeded(geometry.filterNotNull().first())
        }
    }

    fun onWindowMeasured(widthDp: Float, heightDp: Float) {
        window.value = widthDp to heightDp
    }

    /** Binds what needs binding and releases what nothing references; see [HomeGridReconciler]. */
    suspend fun reconcile(port: AppWidgetHostPort): ReconcileReport {
        return HomeGridReconciler(repository, widgetRepository, port).reconcile()
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
                    seeder = koin.get<HomeGridSeeder>(),
                    widgetRepository = koin.get(),
                    writeBack = koin.get(),
                    itemLimits = koin.get(),
                    locked = koin.get<UiSettings>().homeGridLocked,
                )
            }
        }
    }
}

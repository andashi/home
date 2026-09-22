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
    private val seeder: HomeGridSeeder,
    private val widgetRepository: WidgetRepository,
) : ViewModel() {

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
            .onEach { it?.let { g -> measuredRows.update(g.layout, g.rows) } }
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
                    seeder = koin.get(),
                    widgetRepository = koin.get(),
                )
            }
        }
    }
}

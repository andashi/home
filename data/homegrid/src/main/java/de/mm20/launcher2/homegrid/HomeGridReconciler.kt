package de.mm20.launcher2.homegrid

import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.widgets.AppWidget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.flow.first

/**
 * The AppWidget host as the reconciler needs it, so the reconciliation can be
 * tested without one. Ids are the host's device-local integers.
 */
interface AppWidgetHostPort {
    /** Every id currently bound in this host. */
    fun boundIds(): List<Int>
    fun allocate(): Int
    fun release(id: Int)
    /** False when the provider behind [id] is gone (uninstalled, profile removed). */
    fun isProviderAvailable(id: Int): Boolean
    /**
     * Binds [id] to the provider named by [widget] (a flattened
     * `ComponentName`) in [profile]; false when the provider is unknown or
     * the host may not bind without a dialog.
     */
    fun bind(id: Int, widget: String, profile: String?): Boolean
}

data class ReconcileReport(
    /** Item ids that were bound in this pass. */
    val bound: List<String> = emptyList(),
    /** Item ids whose provider could not be bound; their cells show the banner. */
    val failed: List<String> = emptyList(),
    /** Item ids bound to a provider that is gone; their cells show the banner. */
    val unavailable: List<String> = emptyList(),
    /** Host ids no item and no widget-column entry referenced; released. */
    val released: List<Int> = emptyList(),
)

/**
 * Keeps the AppWidget host and the grid table in step (plan, section C):
 * items that name a provider but hold no host id get one bound (the HOME
 * role holder may bind without a dialog), items whose provider is gone are
 * reported so the cell shows the existing "replace or remove" banner, and
 * host ids nothing references any more are released so they do not leak.
 *
 * The widget column's own AppWidgets (secondary widget pages, ADR 0001)
 * share the host, so their ids count as referenced.
 */
class HomeGridReconciler(
    private val homeGridRepository: HomeGridRepository,
    private val widgetRepository: WidgetRepository,
    private val port: AppWidgetHostPort,
) {
    suspend fun reconcile(
        layouts: List<String> = listOf(HomeGridLayouts.Phone, HomeGridLayouts.Fold),
    ): ReconcileReport {
        val bound = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val referenced = mutableSetOf<Int>()

        for (layout in layouts) {
            for (item in homeGridRepository.observe(layout).first()) {
                if (item.isFavorites) continue
                val id = item.appWidgetId
                if (id == null) {
                    val allocated = port.allocate()
                    if (port.bind(allocated, item.widget, item.profile)) {
                        homeGridRepository.setAppWidgetId(layout, item.id, allocated)
                        referenced += allocated
                        bound += item.id
                    } else {
                        port.release(allocated)
                        failed += item.id
                    }
                } else {
                    referenced += id
                    if (!port.isProviderAvailable(id)) unavailable += item.id
                }
            }
        }

        // The widget column pages keep their AppWidgets in the same host.
        val parents = WidgetScreenTarget.entries.map<WidgetScreenTarget, java.util.UUID?> { it.id } + null
        for (parent in parents) {
            referenced += widgetRepository.get(parent).first()
                .filterIsInstance<AppWidget>()
                .map { it.config.widgetId }
        }

        val released = port.boundIds().filter { it !in referenced }
        for (id in released) port.release(id)

        return ReconcileReport(bound, failed, unavailable, released)
    }
}

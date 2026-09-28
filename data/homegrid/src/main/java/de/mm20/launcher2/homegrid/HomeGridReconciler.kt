package de.mm20.launcher2.homegrid

import kotlinx.coroutines.delay
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
 * items that name a provider but hold no host id - or an id the widget
 * service deleted when the provider was uninstalled - get one bound (the HOME
 * role holder may bind without a dialog), items whose provider is gone are
 * reported so the cell shows the existing "replace or remove" banner, and
 * host ids nothing references any more are released so they do not leak.

 */
class HomeGridReconciler(
    private val homeGridRepository: HomeGridRepository,
    private val port: AppWidgetHostPort,
) {
    suspend fun reconcile(
        layouts: List<String> = listOf(HomeGridLayouts.Phone, HomeGridLayouts.Fold),
    ): ReconcileReport {
        val bound = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val referenced = mutableSetOf<Int>()
        val held = port.boundIds().toSet()

        for (layout in layouts) {
            for (item in homeGridRepository.observe(layout).first()) {
                if (item.isFavorites) continue
                val id = liveId(item, held)
                if (id == null) {
                    val allocated = port.allocate()
                    if (port.bind(allocated, item.widget, item.profile)) {
                        homeGridRepository.setAppWidgetId(layout, item.id, allocated)
                        referenced += allocated
                        bound += item.id
                    } else {
                        port.release(allocated)
                        if (item.appWidgetId != null) homeGridRepository.setAppWidgetId(layout, item.id, null)
                        failed += item.id
                    }
                } else {
                    referenced += id
                    if (!port.isProviderAvailable(id)) unavailable += item.id
                }
            }
        }

        val released = port.boundIds().filter { it !in referenced }
        for (id in released) port.release(id)

        return ReconcileReport(bound, failed, unavailable, released)
    }

    /**
     * A pass for [packageName] having arrived. An item naming it holds no
     * host id - its bind was refused while the package was missing - or one
     * the widget service deleted when the package was removed (#245), and
     * nothing about the item changes when the package comes, so the grid's
     * own pass, which runs when an item or its host id changes, would not
     * bind it (review on #219). The widget service learns of a package on
     * its own and can do so after the launcher does, so a refused bind for
     * this package is tried again, [attempts] passes in all with a [pause]
     * before each retry. What still fails then stays unbound and reported:
     * no host id is recorded, so the next pass tries again.
     *
     * Whether anything waits is read from the items themselves - one naming
     * the package with no live host id ([liveId]), in either layout - before every pass, the
     * first included: an id can stand for different widgets on the phone and
     * the fold, and most package events concern nothing the grid names, so
     * they run no pass at all (review on #213).
     */
    suspend fun reconcileArrival(
        packageName: String,
        attempts: Int = 5,
        pause: suspend () -> Unit = { delay(500) },
    ): ReconcileReport {
        if (!waitsFor(packageName)) return ReconcileReport()
        var report = reconcile()
        repeat(attempts - 1) {
            if (!waitsFor(packageName)) return report
            pause()
            report = reconcile()
        }
        return report
    }

    private suspend fun waitsFor(packageName: String): Boolean {
        // Read only once an item names the package: most package events
        // concern nothing on the grid and cost no call to the widget service.
        val held by lazy { port.boundIds().toSet() }
        return listOf(HomeGridLayouts.Phone, HomeGridLayouts.Fold).any { layout ->
            homeGridRepository.observe(layout).first().any {
                !it.isFavorites && it.widget.startsWith("$packageName/") && liveId(it, held) == null
            }
        }
    }

    /**
     * The item's host id, or null when it has none or the widget service
     * deleted it. One rule for both the pass, which binds what this returns
     * null for, and the arrival, which waits for exactly those items. A
     * deleted id is no id: bound anew, or cleared when the bind fails, so the
     * item waits for its package like any widget whose provider is missing.
     */
    private fun liveId(item: HomeGridItem, held: Set<Int>): Int? =
        item.appWidgetId?.takeUnless { deleted(it, held) }

    /**
     * Whether the widget service deleted [id] - as it does with every widget
     * of a provider whose package is uninstalled (#245): the host no longer
     * holds it, and there is no provider info for it. Both, not either: a
     * slow or updating provider keeps its record, and a host list that came
     * back short says nothing on its own - rebinding then would lose a
     * configured widget's setup.
     */
    private fun deleted(id: Int, held: Set<Int>): Boolean = id !in held && !port.isProviderAvailable(id)

    companion object {
        /** The widget repository's page size (its `limit` default). */
        const val PageSize = 100
    }
}

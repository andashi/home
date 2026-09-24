package de.mm20.launcher2.homegrid

import java.util.concurrent.ConcurrentHashMap

/**
 * How many rows a layout has on this device. Rows are derived from the
 * usable screen height (D1), which only the rendered grid knows, so the
 * config store asks here instead of guessing.
 */
interface GridRowsSource {
    /**
     * The rows [layout] has on this device, or null when this device does
     * not render that layout (a phone and the `fold` layout): only the
     * device that shows a layout knows its rows (#90).
     */
    fun rows(layout: String): Int?
}

/**
 * The rows the renderer measured, per layout. Before the first render the
 * answer is [defaultRows], so a config that fits six rows converges the same
 * way before and after, and one that needs more gets its `grid-overflow`
 * diagnostic already. One instance per process (Koin `single`), written by
 * the grid's view model, read by the config store on any thread.
 */
class MeasuredGridRows(
    private val defaultRows: Int = DefaultRows,
    /** The layout this device renders; null answers for every layout. */
    private val ownLayout: String? = null,
) : GridRowsSource {

    private val measured = ConcurrentHashMap<String, Int>()

    /** Records the rows [layout] has on this device; later calls win. */
    fun update(layout: String, rows: Int) {
        measured[layout] = rows
    }

    override fun rows(layout: String): Int? = measured[layout] ?: defaultRows

    companion object {
        const val DefaultRows = 6
    }
}

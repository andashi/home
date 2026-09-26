package de.mm20.launcher2.homegrid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * How many rows a layout has on this device. Rows are derived from the
 * usable screen height (D1), which only the rendered grid knows, so the
 * config store asks here instead of guessing.
 */
interface GridRowsSource {
    /**
     * The rows [layout] has on this device, or null when they are not known:
     * this device does not render that layout (a phone and the `fold`
     * layout), or it has not measured it yet. Only the device that shows a
     * layout knows its rows (#90), and only once it has drawn it; a layout
     * whose rows are null is kept as written.
     */
    fun rows(layout: String): Int?
}

/**
 * The rows the renderer measured, per layout. Before the first render there
 * is no answer: a guess of six fitted a config pushed before the Fold's
 * first draw to six rows although the Fold has seven, and dropped its
 * seventh row, silently (the 0.7.3 h 7 -> 6 very likely, and grid-overflow
 * on the dock on 2026-09-26). [measurements] lets the config store fit such
 * a layout once it is measured. One instance per process (Koin `single`),
 * written by the grid's view model, read by the config store on any thread.
 */
class MeasuredGridRows(
    /** The layout this device renders; null answers for every layout. */
    private val ownLayout: String? = null,
) : GridRowsSource {

    private val measured = ConcurrentHashMap<String, Int>()
    private val _measurements = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Every layout's measured rows, for whoever has to act on a measurement. */
    val measurements: StateFlow<Map<String, Int>> = _measurements.asStateFlow()

    /** Records the rows [layout] has on this device; later calls win. */
    fun update(layout: String, rows: Int) {
        measured[layout] = rows
        _measurements.update { it + (layout to rows) }
    }

    override fun rows(layout: String): Int? =
        if (ownLayout != null && layout != ownLayout) null else measured[layout]

    companion object {
        /** The fewest rows a layout kept as written is given (rowsToKeep). */
        const val DefaultRows = 6
    }
}

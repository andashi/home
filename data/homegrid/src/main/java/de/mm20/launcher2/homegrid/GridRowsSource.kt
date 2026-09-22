package de.mm20.launcher2.homegrid

/**
 * How many rows a layout has on this device. Rows are derived from the
 * usable screen height (D1), which only the rendered grid knows, so the
 * config store asks here instead of guessing.
 */
interface GridRowsSource {
    fun rows(layout: String): Int
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
) : GridRowsSource {

    /** Records the rows [layout] has on this device; later calls win. */
    fun update(layout: String, rows: Int) {
        TODO("PR 4")
    }

    override fun rows(layout: String): Int {
        TODO("PR 4")
    }

    companion object {
        const val DefaultRows = 6
    }
}

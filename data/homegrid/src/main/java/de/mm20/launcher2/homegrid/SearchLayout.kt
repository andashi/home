package de.mm20.launcher2.homegrid

/**
 * How search is laid out in one window, derived from the home grid's
 * geometry (#91), so search shows the home grid's columns at the home grid's
 * pitch: one column of results on a phone and on the fold's cover, two panes
 * at the seam on the fold's inner display.
 */
sealed interface SearchLayout {
    /** App columns: the home grid's configured columns, in every window. */
    val columns: Int

    data class Single(override val columns: Int) : SearchLayout

    /**
     * The inner display of a fold. [apps] holds favorites and apps - the half
     * the cover shows, in the home grid's columns; [results] holds the other
     * results. Offsets and widths are dp from the grid area's left edge.
     */
    data class TwoPane(
        override val columns: Int,
        val apps: Pane,
        val results: Pane,
    ) : SearchLayout

    data class Pane(val startDp: Float, val widthDp: Float) {
        val endDp: Float get() = startDp + widthDp
    }

    companion object {
        fun from(geometry: GridGeometry): SearchLayout = Single(geometry.visibleColumns)
    }
}

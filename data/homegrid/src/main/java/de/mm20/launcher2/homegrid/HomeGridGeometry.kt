package de.mm20.launcher2.homegrid

import de.mm20.launcher2.grid.GridSpec

/**
 * What one window of the grid looks like: the layout it draws, the full
 * [spec] of that layout (twice the configured columns on a fold, with the
 * fold line in the middle), how many of those columns this window shows,
 * and the cell size in dp.
 */
data class GridGeometry(
    val layout: String,
    val spec: GridSpec,
    /** Columns drawn in this window: `spec.columns`, or half of them on the cover. */
    val visibleColumns: Int,
    /** True on a foldable's cover display: the layout is clipped to its left half (D7). */
    val isCover: Boolean,
    val cellDp: Float,
    val gapDp: Float,
) {
    val rows: Int get() = spec.rows
}

/**
 * Derives the geometry from the form factor, the configured column count
 * and the measured window (D1): cells are square, the column count is fixed,
 * rows are whatever square cells fit in the usable height.
 */
object HomeGridGeometry {
    /** The gap between cells; the config store uses the same value for its limits. */
    const val GapDp = 8f

    /**
     * Invariants: `cellDp * visibleColumns + gap * (visibleColumns - 1) == widthDp`;
     * `rows >= 1`; on a fold `spec.columns == 2 * columns` and
     * `spec.foldColumn == columns`; `isCover` only on a fold whose window is
     * narrower than [FormFactorRule.ExpandedWidthDp].
     */
    fun derive(
        formFactor: FormFactor,
        columns: Int,
        widthDp: Float,
        heightDp: Float,
        gapDp: Float = GapDp,
    ): GridGeometry {
        TODO("PR 4")
    }
}

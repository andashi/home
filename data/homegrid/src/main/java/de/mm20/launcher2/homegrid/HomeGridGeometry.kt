package de.mm20.launcher2.homegrid

import de.mm20.launcher2.grid.CellMath
import de.mm20.launcher2.grid.GridSpec
import kotlin.math.floor

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
    /** True on a foldable's cover display: the layout is clipped to its right half (D7, #93). */
    val isCover: Boolean,
    val cellDp: Float,
    val gapDp: Float,
    /**
     * The first layout column the cover shows, on a fold (both displays):
     * the cover is the right half of the inner display (#93), so this is the
     * fold column. Search puts its apps there (#91), so the apps on the cover
     * and inside sit in the same half. 0 on a phone.
     */
    val coverFirstColumn: Int = 0,
) {
    val rows: Int get() = spec.rows

    /** The first layout column this window draws: the cover's on the cover, else 0. */
    val firstVisibleColumn: Int get() = if (isCover) coverFirstColumn else 0

    /** The layout columns this window draws. */
    val visibleRange: IntRange get() = firstVisibleColumn until firstVisibleColumn + visibleColumns
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
     * A fold window narrower than this is the cover (Material's `Expanded`
     * breakpoint): the Pixel Fold cover is about 412 dp, its inner display
     * about 790 dp.
     */
    const val CoverMaxWidthDp = 600f

    /**
     * Invariants: `cellDp * visibleColumns + gap * (visibleColumns - 1) == widthDp`;
     * `rows >= 1`; on a fold `spec.columns == 2 * columns` and
     * `spec.foldColumn == columns`; `isCover` only on a fold whose window is
     * narrower than [CoverMaxWidthDp]; on a fold the cover shows columns
     * `columns until 2 * columns` (#93).
     */
    fun derive(
        formFactor: FormFactor,
        columns: Int,
        widthDp: Float,
        heightDp: Float,
        gapDp: Float = GapDp,
    ): GridGeometry {
        require(columns >= 1) { "columns must be positive, got $columns" }
        val folds = formFactor == FormFactor.Fold
        val isCover = folds && widthDp < CoverMaxWidthDp
        val visibleColumns = if (folds && !isCover) columns * 2 else columns
        val cellDp = CellMath.cellDp(widthDp, visibleColumns, gapDp)
        val rows = maxOf(1, floor((heightDp + gapDp) / (cellDp + gapDp)).toInt())
        return GridGeometry(
            layout = formFactor.layout,
            spec = GridSpec(
                columns = if (folds) columns * 2 else columns,
                rows = rows,
                foldColumn = if (folds) columns else null,
            ),
            visibleColumns = visibleColumns,
            isCover = isCover,
            cellDp = cellDp,
            gapDp = gapDp,
            coverFirstColumn = if (folds) columns else 0,
        )
    }
}

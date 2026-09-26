package de.mm20.launcher2.grid

/**
 * The shape of one home grid: [columns] x [rows] cells, and on a foldable the
 * column index at which the fold line runs ([foldColumn], `columns / 2`), so
 * that items which may not cross it can be kept on one side (ADR 0001, D7).
 */
data class GridSpec(
    val columns: Int,
    val rows: Int,
    val foldColumn: Int? = null,
) {
    init {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        if (foldColumn != null) {
            require(foldColumn in 1 until columns) {
                "foldColumn must lie strictly inside the grid, got $foldColumn for $columns columns"
            }
        }
    }
}

/**
 * A rectangle of cells: top-left corner ([x], [y]) and size ([w], [h]), all in
 * cell units. [right] and [bottom] are exclusive.
 */
data class Span(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    /** True when the two rectangles share at least one cell. */
    fun overlaps(other: Span): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom

    /** True when the rectangle lies completely inside a [columns] x [rows] grid. */
    fun fitsIn(columns: Int, rows: Int): Boolean =
        x >= 0 && y >= 0 && w >= 1 && h >= 1 && right <= columns && bottom <= rows
}

/**
 * The sizes an item may take, in cells. For an AppWidget these come from the
 * provider's declared minimum and maximum (see [CellMath.limitsFor]); the
 * favorites widget is [Unbounded].
 */
data class SizeLimits(val minW: Int, val minH: Int, val maxW: Int, val maxH: Int) {
    init {
        require(minW >= 1 && minH >= 1) { "minimum span must be at least 1x1, got ${minW}x$minH" }
        require(maxW >= minW && maxH >= minH) { "maximum must not be below minimum: $this" }
    }

    companion object {
        val Unbounded = SizeLimits(minW = 1, minH = 1, maxW = Int.MAX_VALUE, maxH = Int.MAX_VALUE)
    }
}

/**
 * One item on the grid. [mayCrossFold] is true only for the favorites widget,
 * the one item allowed to span the fold line (D7).
 */
data class GridItem(
    val id: String,
    val span: Span,
    val limits: SizeLimits = SizeLimits.Unbounded,
    val mayCrossFold: Boolean = false,
)

/** Everything the engine can find wrong with a layout, or had to change about it. */
sealed class LayoutIssue {
    /** The item does not fit inside the grid and was dropped. */
    data class OutOfBounds(val id: String, val span: Span) : LayoutIssue()

    /** Two items share at least one cell. */
    data class Overlap(val a: String, val b: String) : LayoutIssue()

    /** The item spans the fold line without being allowed to. */
    data class CrossesFold(val id: String) : LayoutIssue()

    /** The requested span was smaller than the item's minimum and was enlarged. */
    data class BelowMinimum(val id: String, val requested: Span, val clamped: Span) : LayoutIssue()

    /**
     * The requested span was larger than the item may be and was shrunk (#140):
     * [bound] says whether the widget's own maximum, the grid's size, or each
     * on a different axis set the limit.
     */
    data class AboveMaximum(val id: String, val requested: Span, val clamped: Span, val bound: Bound) : LayoutIssue()

    enum class Bound { Widget, Grid, Both }

    /**
     * The item is in the layout, but not where it asked to be: slid back into
     * the grid, or pushed down below [pushedBy], an earlier item it overlapped
     * (#140). A nudge off the fold line is [NudgedOffFold], not this.
     */
    data class Moved(val id: String, val from: Span, val to: Span, val pushedBy: String? = null) : LayoutIssue()

    /** The item crossed the fold line and was kept, nudged to one side of it. */
    data class NudgedOffFold(val id: String) : LayoutIssue()

    /** No free cells were left for the item. */
    data class Overflow(val id: String) : LayoutIssue()
}

/** A span fitted to an item's limits and the grid, and what fitting it changed. */
data class SizeFit(val span: Span, val issues: List<LayoutIssue>)

/** The outcome of an engine operation: the resulting items and what happened on the way. */
data class LayoutResult(val items: List<GridItem>, val issues: List<LayoutIssue> = emptyList()) {
    val isClean: Boolean get() = issues.isEmpty()
}

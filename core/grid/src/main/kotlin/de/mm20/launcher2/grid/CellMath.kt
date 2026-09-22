package de.mm20.launcher2.grid

/**
 * What an AppWidget provider declares about its size, converted to dp by the
 * caller (`AppWidgetProviderInfo` reports px). Zero means "not declared", as
 * in the platform: a zero `minResize*` falls back to `min*`, a zero
 * `maxResize*` means unbounded, a zero `targetCell*` means "use the minimum".
 */
data class ProviderSizes(
    val minWidthDp: Float,
    val minHeightDp: Float,
    val minResizeWidthDp: Float = 0f,
    val minResizeHeightDp: Float = 0f,
    val maxResizeWidthDp: Float = 0f,
    val maxResizeHeightDp: Float = 0f,
    val targetCellWidth: Int = 0,
    val targetCellHeight: Int = 0,
    val resizeHorizontal: Boolean = true,
    val resizeVertical: Boolean = true,
)

/** The cell geometry of one device profile (portrait or landscape), in dp. */
data class CellMetrics(val cellWidthDp: Float, val cellHeightDp: Float, val gapDp: Float)

/** A size in cells. */
data class CellSize(val w: Int, val h: Int)

/**
 * Conversions between dp and cells, following Launcher3's
 * `LauncherAppWidgetProviderInfo.initSpans` so that widgets get the same span
 * here as on a Pixel.
 */
object CellMath {

    /**
     * The width of one cell when [columns] cells and the gaps between them
     * fill [widthDp]. Invariant: `columns * cell + (columns - 1) * gap == widthDp`.
     */
    fun cellDp(widthDp: Float, columns: Int, gapDp: Float): Float = TODO()

    /**
     * The number of cells needed to give a widget at least [sizeDp]:
     * `max(1, ceil((size + gap) / (cell + gap)))`, i.e. n cells and the n-1
     * gaps between them must reach the requested size.
     */
    fun spanFor(sizeDp: Float, cellDp: Float, gapDp: Float): Int = TODO()

    /**
     * The span a widget gets when it is added: `targetCell*` when declared
     * and inside the limits, otherwise the span of its declared minimum size,
     * computed for both profiles with the larger taken (Launcher3).
     * Invariant: the result lies inside [limitsFor] of the same inputs.
     */
    fun defaultSpanFor(provider: ProviderSizes, portrait: CellMetrics, landscape: CellMetrics? = null): CellSize = TODO()

    /**
     * The resize limits of a widget in cells: the minimum from
     * `minResize*` (falling back to `min*`, and never above the default span),
     * the maximum from `maxResize*` (unbounded when not declared), each
     * computed per profile and combined the safe way (largest minimum,
     * smallest maximum). An axis the widget does not allow resizing on is
     * pinned to the default span.
     */
    fun limitsFor(provider: ProviderSizes, portrait: CellMetrics, landscape: CellMetrics? = null): SizeLimits = TODO()
}

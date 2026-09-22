package de.mm20.launcher2.grid

import kotlin.math.ceil

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
    fun cellDp(widthDp: Float, columns: Int, gapDp: Float): Float =
        (widthDp - gapDp * (columns - 1)) / columns

    /**
     * The number of cells needed to give a widget at least [sizeDp]:
     * `max(1, ceil((size + gap) / (cell + gap)))`, i.e. n cells and the n-1
     * gaps between them must reach the requested size.
     */
    fun spanFor(sizeDp: Float, cellDp: Float, gapDp: Float): Int =
        maxOf(1, ceil((sizeDp + gapDp) / (cellDp + gapDp) - Epsilon).toInt())

    /**
     * The span a widget gets when it is added: `targetCell*` when declared
     * and inside the limits, otherwise the span of its declared minimum size,
     * computed for both profiles with the larger taken (Launcher3).
     * Invariant: the result lies inside [limitsFor] of the same inputs.
     */
    fun defaultSpanFor(provider: ProviderSizes, portrait: CellMetrics, landscape: CellMetrics? = null): CellSize {
        val spans = spans(provider, portrait, landscape)
        return CellSize(spans.defaultW, spans.defaultH)
    }

    /**
     * The resize limits of a widget in cells: the minimum from
     * `minResize*` (falling back to `min*`, and never above the default span),
     * the maximum from `maxResize*` (unbounded when not declared), each
     * computed per profile and combined the safe way (largest minimum,
     * smallest maximum). An axis the widget does not allow resizing on is
     * pinned to the default span.
     */
    fun limitsFor(provider: ProviderSizes, portrait: CellMetrics, landscape: CellMetrics? = null): SizeLimits {
        val spans = spans(provider, portrait, landscape)
        return SizeLimits(
            minW = if (provider.resizeHorizontal) spans.minW else spans.defaultW,
            minH = if (provider.resizeVertical) spans.minH else spans.defaultH,
            maxW = if (provider.resizeHorizontal) spans.maxW else spans.defaultW,
            maxH = if (provider.resizeVertical) spans.maxH else spans.defaultH,
        )
    }

    private class Spans(
        val defaultW: Int, val defaultH: Int,
        val minW: Int, val minH: Int,
        val maxW: Int, val maxH: Int,
    )

    private fun spans(provider: ProviderSizes, portrait: CellMetrics, landscape: CellMetrics?): Spans {
        val profiles = listOfNotNull(portrait, landscape)

        fun widthSpan(dp: Float) = profiles.maxOf { spanFor(dp, it.cellWidthDp, it.gapDp) }
        fun heightSpan(dp: Float) = profiles.maxOf { spanFor(dp, it.cellHeightDp, it.gapDp) }

        val minSpanW = widthSpan(provider.minWidthDp)
        val minSpanH = heightSpan(provider.minHeightDp)

        // minResize is honoured only when it is declared and not above the minimum,
        // exactly as AppWidgetProviderInfo documents it.
        val resizeMinW = provider.minResizeWidthDp
            .takeIf { it > 0f && it <= provider.minWidthDp }
            ?.let { minOf(minSpanW, widthSpan(it)) } ?: minSpanW
        val resizeMinH = provider.minResizeHeightDp
            .takeIf { it > 0f && it <= provider.minHeightDp }
            ?.let { minOf(minSpanH, heightSpan(it)) } ?: minSpanH

        // maxResize: the smallest span over the profiles that still contains the
        // declared maximum, never below the minimum.
        val maxW = provider.maxResizeWidthDp.takeIf { it > 0f }
            ?.let { dp -> maxOf(resizeMinW, profiles.minOf { spanFor(dp, it.cellWidthDp, it.gapDp) }) }
            ?: Int.MAX_VALUE
        val maxH = provider.maxResizeHeightDp.takeIf { it > 0f }
            ?.let { dp -> maxOf(resizeMinH, profiles.minOf { spanFor(dp, it.cellHeightDp, it.gapDp) }) }
            ?: Int.MAX_VALUE

        val defaultW = provider.targetCellWidth.takeIf { it in resizeMinW..maxW } ?: minSpanW.coerceIn(resizeMinW, maxW)
        val defaultH = provider.targetCellHeight.takeIf { it in resizeMinH..maxH } ?: minSpanH.coerceIn(resizeMinH, maxH)

        return Spans(defaultW, defaultH, resizeMinW, resizeMinH, maxW, maxH)
    }

    /** Guards `ceil` against float noise: 73 dp on a 57 + 16 pitch is exactly one cell. */
    private const val Epsilon = 1e-4f
}

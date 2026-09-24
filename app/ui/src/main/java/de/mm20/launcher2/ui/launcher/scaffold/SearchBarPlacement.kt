package de.mm20.launcher2.ui.launcher.scaffold

/**
 * Where the search bar sits between the home page and open search (#107):
 * the two can differ (`search.barPosition`), and the bar moves between them
 * with the search transition's progress, so the eye can follow it.
 */
internal object SearchBarPlacement {
    /** Vertical bias of the bar: -1 at the top, 1 at the bottom. */
    fun bias(home: SearchBarPosition, search: SearchBarPosition, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return home.bias + (search.bias - home.bias) * t
    }

    /** The position the bar counts as at [progress], for its level, insets and chips. */
    fun positionAt(home: SearchBarPosition, search: SearchBarPosition, progress: Float): SearchBarPosition =
        if (progress < 0.5f) home else search

    /**
     * The Hidden style's slide-in offset in dp at [progress], toward the edge
     * the bar is at. The bias itself is the signed factor and blends the two
     * insets, so the offset stays continuous while the bar moves between the
     * edges (review on #115); at either edge it is what the style always did.
     */
    fun hiddenOffset(bias: Float, progress: Float, topInset: Float, bottomInset: Float): Float {
        val inset = topInset + (bottomInset - topInset) * (bias + 1f) / 2f
        return bias * (1f - progress) * (1f - progress) * (128f + inset)
    }

    private val SearchBarPosition.bias: Float
        get() = if (this == SearchBarPosition.Top) -1f else 1f
}

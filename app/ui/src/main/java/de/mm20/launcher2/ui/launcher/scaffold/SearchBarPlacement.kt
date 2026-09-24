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
     * the bar is at. Stub until the fix: the edge switches at [bias] 0.
     */
    fun hiddenOffset(bias: Float, progress: Float, topInset: Float, bottomInset: Float): Float {
        val factor = if (bias < 0f) -1f else 1f
        val inset = if (bias < 0f) topInset else bottomInset
        return factor * (1f - progress) * (1f - progress) * (128f + inset)
    }

    private val SearchBarPosition.bias: Float
        get() = if (this == SearchBarPosition.Top) -1f else 1f
}

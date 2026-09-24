package de.mm20.launcher2.ui.launcher.scaffold

/**
 * Where the search bar sits between the home page and open search (#107):
 * the two can differ (`search.barPosition`), and the bar moves between them
 * with the search transition's progress, so the eye can follow it.
 * Stub until the fix: the home position throughout.
 */
internal object SearchBarPlacement {
    /** Vertical bias of the bar: -1 at the top, 1 at the bottom. */
    fun bias(home: SearchBarPosition, search: SearchBarPosition, progress: Float): Float = home.bias

    /** The position the bar counts as at [progress], for its level, insets and chips. */
    fun positionAt(home: SearchBarPosition, search: SearchBarPosition, progress: Float): SearchBarPosition = home

    private val SearchBarPosition.bias: Float
        get() = if (this == SearchBarPosition.Top) -1f else 1f
}

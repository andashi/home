package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What edit mode changes about how cells are drawn, held in snapshot state
 * that only the cells' graphics layers read (performance rules of the
 * plan): the dragged cell's ghost position, and the wiggle angle.
 *
 * Nothing in the composition reads these fields, so a drag frame or a
 * wiggle frame updates the layers and recomposes nothing; the
 * recomposition-count test pins that.
 */
@Stable
class GridEditVisual {
    /** The cell under the finger, drawn at the ghost position instead of its rectangle. */
    var draggedId: String? by mutableStateOf(null)

    /** Top-left of the ghost, in px, in the grid's coordinates. */
    var ghostLeftPx: Float by mutableFloatStateOf(0f)
    var ghostTopPx: Float by mutableFloatStateOf(0f)

    /** The wiggle angle of the moment in degrees; zero outside edit mode. */
    var wiggleDegrees: Float by mutableFloatStateOf(0f)

    /** Alternate the wiggle's direction per cell so neighbours do not swing in step. */
    fun wiggleFor(id: String): Float = if (id.hashCode() and 1 == 0) wiggleDegrees else -wiggleDegrees
}

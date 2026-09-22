package de.mm20.launcher2.homegrid

import de.mm20.launcher2.grid.SizeLimits

/**
 * The spans an item may be resized to in edit mode (D4): an AppWidget's
 * declared limits converted to this geometry's cells, the favorites
 * widget's none. Looked up on the device from the provider info of the
 * bound host id; tests answer from a map.
 */
fun interface GridItemLimits {
    fun limitsFor(item: HomeGridItem, geometry: GridGeometry): SizeLimits

    companion object {
        /** Every item unbounded: what a test or a headless run uses. */
        val Unbounded = GridItemLimits { _, _ -> SizeLimits.Unbounded }
    }
}

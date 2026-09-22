package de.mm20.launcher2.homegrid

import android.content.Context
import de.mm20.launcher2.grid.SizeLimits

/**
 * The resize limits of a bound AppWidget from its provider info, converted
 * to this geometry's cells the way the config store converts them when a
 * layout is applied (D4). An unbound item, a vanished provider and the
 * favorites widget are unbounded.
 */
class AndroidGridItemLimits(private val context: Context) : GridItemLimits {
    override fun limitsFor(item: HomeGridItem, geometry: GridGeometry): SizeLimits = TODO("PR 5")
}

package de.mm20.launcher2.homegrid

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import de.mm20.launcher2.grid.CellMath
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.CellMetrics
import de.mm20.launcher2.grid.ProviderSizes
import de.mm20.launcher2.grid.SizeLimits

/**
 * The resize limits of a bound AppWidget from its provider info, converted
 * to this geometry's cells the way the config store converts them when a
 * layout is applied (D4). An unbound item, a vanished provider and the
 * favorites widget are unbounded.
 */
class AndroidGridItemLimits(
    private val context: Context,
    /** The provider info of a bound host id; the [AppWidgetManager] in production, a map in tests. */
    private val infoOf: (appWidgetId: Int) -> AppWidgetProviderInfo? = { id ->
        AppWidgetManager.getInstance(context)?.getAppWidgetInfo(id)
    },
) : GridItemLimits {

    override fun limitsFor(item: HomeGridItem, geometry: GridGeometry): SizeLimits {
        if (item.isFavorites) return SizeLimits.Unbounded
        val appWidgetId = item.appWidgetId ?: return SizeLimits.Unbounded
        val info = infoOf(appWidgetId) ?: return SizeLimits.Unbounded
        return limitsFor(info, geometry, context.resources.displayMetrics.density)
    }

    companion object {
        /** Provider info in px at [density] to cells of [geometry]; the same rule as the config store's. */
        fun limitsFor(info: AppWidgetProviderInfo, geometry: GridGeometry, density: Float): SizeLimits =
            CellMath.limitsFor(sizesOf(info, density), metricsOf(geometry))

        /** The span a widget gets when it is added in edit mode (its target cells, or its minimum). */
        fun defaultSpanFor(info: AppWidgetProviderInfo, geometry: GridGeometry, density: Float): CellSize =
            CellMath.defaultSpanFor(sizesOf(info, density), metricsOf(geometry))

        private fun metricsOf(geometry: GridGeometry) = CellMetrics(geometry.cellDp, geometry.cellDp, geometry.gapDp)

        private fun sizesOf(info: AppWidgetProviderInfo, density: Float) = ProviderSizes(
            minWidthDp = info.minWidth / density,
            minHeightDp = info.minHeight / density,
            minResizeWidthDp = info.minResizeWidth / density,
            minResizeHeightDp = info.minResizeHeight / density,
            maxResizeWidthDp = info.maxResizeWidth / density,
            maxResizeHeightDp = info.maxResizeHeight / density,
            targetCellWidth = info.targetCellWidth,
            targetCellHeight = info.targetCellHeight,
            resizeHorizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
            resizeVertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        )
    }
}

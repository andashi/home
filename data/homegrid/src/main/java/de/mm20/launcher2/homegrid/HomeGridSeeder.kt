package de.mm20.launcher2.homegrid

import android.appwidget.AppWidgetManager
import android.content.Context
import de.mm20.launcher2.grid.CellMath
import de.mm20.launcher2.grid.GridItem
import de.mm20.launcher2.grid.GridLayout
import de.mm20.launcher2.grid.GridSpec
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.widgets.AppWidget
import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.flow.first

/** Remembers that the one-time conversion of the widget column ran. */
interface HomeGridSeedFlag {
    suspend fun isSeeded(): Boolean
    suspend fun markSeeded()
}

/**
 * The migration ADR 0001 describes, run once from the UI because the target
 * rows depend on the cell size only the display knows: every entry of the
 * home widget column becomes a full-width grid item stacked from the top,
 * and the favorites widget lands in the bottom row (the dock, D2).
 *
 * Idempotent twice over: a layout that already holds items is left alone,
 * and the [HomeGridSeedFlag] is set afterwards so a grid the user emptied on
 * purpose is never refilled from the old column.
 *
 * [providerOf] resolves a bound AppWidget id to its provider's flattened
 * `ComponentName`, which is what a grid item names; the real one asks the
 * [AppWidgetManager]. A widget whose provider is unknown cannot render and
 * is skipped.
 */
class HomeGridSeeder(
    private val widgetRepository: WidgetRepository,
    private val homeGridRepository: HomeGridRepository,
    private val flag: HomeGridSeedFlag,
    private val providerOf: (appWidgetId: Int) -> String?,
) {
    constructor(
        context: Context,
        widgetRepository: WidgetRepository,
        homeGridRepository: HomeGridRepository,
        flag: HomeGridSeedFlag,
    ) : this(
        widgetRepository,
        homeGridRepository,
        flag,
        providerOf = { id ->
            AppWidgetManager.getInstance(context)?.getAppWidgetInfo(id)?.provider?.flattenToString()
        },
    )

    /**
     * Returns the items written for [geometry]'s layout, or an empty list
     * when nothing was seeded. On a fold the same rows are written for the
     * `phone` layout too, at the configured column count, so the document
     * describes both.
     */
    suspend fun seedIfNeeded(geometry: GridGeometry): List<HomeGridItem> {
        if (flag.isSeeded()) return emptyList()
        val layout = geometry.layout
        if (homeGridRepository.observe(layout).first().isNotEmpty()) {
            flag.markSeeded()
            return emptyList()
        }
        val column = widgetRepository.get(WidgetScreenTarget.Default.id).first()

        val items = convert(column, layout, geometry.spec, geometry.cellDp, geometry.gapDp)
        homeGridRepository.replace(layout, items)

        if (layout == HomeGridLayouts.Fold) {
            val columns = geometry.spec.columns / 2
            val phoneSpec = GridSpec(columns = columns, rows = geometry.spec.rows)
            homeGridRepository.replace(
                HomeGridLayouts.Phone,
                convert(column, HomeGridLayouts.Phone, phoneSpec, geometry.cellDp, geometry.gapDp),
            )
        }
        flag.markSeeded()
        return items
    }

    /**
     * AppWidgets in column order, each the full cover width and as many rows as
     * its height needs (never more than the rows above the dock), placed
     * from the top by the engine; the dock last, in the bottom row.
     */
    private fun convert(
        column: List<Widget>,
        layout: String,
        spec: GridSpec,
        cellDp: Float,
        gapDp: Float,
    ): List<HomeGridItem> {
        val dockSpan = Span(0, spec.rows - 1, spec.columns, 1)
        val occupied = mutableListOf(GridItem(DockId, dockSpan, SizeLimits.Unbounded, mayCrossFold = true))
        val result = mutableListOf<HomeGridItem>()
        for (widget in column) {
            if (widget !is AppWidget) continue
            val provider = providerOf(widget.config.widgetId) ?: continue
            val h = CellMath.spanFor(widget.config.height.toFloat(), cellDp, gapDp)
                .coerceIn(1, maxOf(1, spec.rows - 1))
            val id = "widget-${widget.config.widgetId}"
            // Full width of the cover: on a fold that is the left half, since
            // an AppWidget may not span the fold line (D7).
            val width = spec.foldColumn ?: spec.columns
            val candidate = GridItem(id, Span(0, 0, width, h), SizeLimits.Unbounded)
            val placed = GridLayout.place(spec, occupied, candidate) ?: continue
            occupied += placed
            result += HomeGridItem(
                layout = layout,
                id = id,
                widget = provider,
                x = placed.span.x,
                y = placed.span.y,
                w = placed.span.w,
                h = placed.span.h,
                appWidgetId = widget.config.widgetId,
                config = HomeGridItemConfig(
                    borderless = widget.config.borderless,
                    background = widget.config.background,
                    themeColors = widget.config.themeColors,
                ),
                position = result.size,
            )
        }
        result += HomeGridItem(
            layout = layout,
            id = DockId,
            widget = HomeGridWidgets.Favorites,
            x = dockSpan.x,
            y = dockSpan.y,
            w = dockSpan.w,
            h = dockSpan.h,
            position = result.size,
        )
        return result
    }

    companion object {
        /** The id the seeded favorites widget gets; a config may rename it. */
        const val DockId = "dock"
    }
}

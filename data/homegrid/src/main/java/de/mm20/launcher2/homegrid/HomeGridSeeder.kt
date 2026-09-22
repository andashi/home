package de.mm20.launcher2.homegrid

import android.appwidget.AppWidgetManager
import android.content.Context
import de.mm20.launcher2.widgets.WidgetRepository

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
        TODO("PR 4")
    }
}

package de.mm20.launcher2.config.service

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import de.mm20.launcher2.config.Profile as ConfigProfile
import de.mm20.launcher2.grid.CellMath
import de.mm20.launcher2.grid.CellMetrics
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.ProviderSizes
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.profiles.Profile

/** What the layout engine needs to know about one AppWidget provider. */
data class ProviderLimits(
    /** The span the widget gets when it is added without geometry. */
    val default: CellSize,
    /** The spans it may be resized to (D4). */
    val limits: SizeLimits,
)

/**
 * Resolves a grid item's `widget` (a flattened provider `ComponentName`) to
 * its size limits in cells. An interface so the config store can be tested
 * without an [AppWidgetManager]; the real one is [AppWidgetGridLimitsSource].
 */
interface GridLimitsSource {
    /** Null when no installed provider matches [widget] in [profile]. */
    fun lookup(widget: String, profile: ConfigProfile?, columns: Int): ProviderLimits?
}

/**
 * How many rows a layout has on this device. Rows are derived from the
 * usable screen height (D1), which only the rendered grid knows; until the
 * renderer lands (PR 4) the default source answers with a conservative
 * constant, so a config that fits six rows converges the same way then and
 * now, and one that needs more gets its `grid-overflow` diagnostic already.
 */
interface GridRowsSource {
    fun rows(layout: String): Int
}

class DefaultGridRowsSource : GridRowsSource {
    override fun rows(layout: String): Int = DefaultRows

    companion object {
        const val DefaultRows = 6
    }
}

/**
 * The real [GridLimitsSource]: the provider's declared sizes, converted from
 * px to dp, against the cell size the grid will have at [columns] on this
 * display (portrait and landscape both, the Launcher3 way: the larger minimum
 * span wins, the smaller maximum).
 */
class AppWidgetGridLimitsSource(
    private val context: Context,
    private val profileResolver: ProfileResolver,
) : GridLimitsSource {

    override fun lookup(widget: String, profile: ConfigProfile?, columns: Int): ProviderLimits? {
        val component = ComponentName.unflattenFromString(widget) ?: return null
        val profileType = when (profile ?: ConfigProfile.Personal) {
            ConfigProfile.Personal -> Profile.Type.Personal
            ConfigProfile.Work -> Profile.Type.Work
            ConfigProfile.Private -> Profile.Type.Private
        }
        val userHandle = profileResolver.getProfile(profileType)?.userHandle ?: return null
        val manager = AppWidgetManager.getInstance(context) ?: return null
        val info = manager.getInstalledProvidersForProfile(userHandle)
            .firstOrNull { it.provider == component } ?: return null
        return limitsOf(info, columns)
    }

    private fun limitsOf(info: AppWidgetProviderInfo, columns: Int): ProviderLimits {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val configuration = context.resources.configuration
        val portraitWidth = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
        val landscapeWidth = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
        val portrait = CellMetrics(
            cellWidthDp = CellMath.cellDp(portraitWidth, columns, GapDp),
            cellHeightDp = CellMath.cellDp(portraitWidth, columns, GapDp),
            gapDp = GapDp,
        )
        val landscape = CellMetrics(
            cellWidthDp = CellMath.cellDp(landscapeWidth, columns, GapDp),
            // Square cells in portrait; in landscape the same cell height is
            // what fits the shorter edge (ADR 0001: rotation keeps the grid).
            cellHeightDp = portrait.cellHeightDp,
            gapDp = GapDp,
        )
        val sizes = ProviderSizes(
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
        return ProviderLimits(
            default = CellMath.defaultSpanFor(sizes, portrait, landscape),
            limits = CellMath.limitsFor(sizes, portrait, landscape),
        )
    }

    companion object {
        /** The gap between cells, the same value the renderer uses. */
        const val GapDp = 8f
    }
}

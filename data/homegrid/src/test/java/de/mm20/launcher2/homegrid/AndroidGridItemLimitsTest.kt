package de.mm20.launcher2.homegrid

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Edit mode's resize limits (D4) come from the same conversion the config
 * store applies: provider px at the device density, the Launcher3 span
 * rule, this geometry's cells.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGridItemLimitsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A 4x6 phone grid with 97 dp cells.
    private val geometry = HomeGridGeometry.derive(FormFactor.Phone, 4, widthDp = 412f, heightDp = 622f)

    private fun info(
        minWidthDp: Int,
        minHeightDp: Int,
        minResizeWidthDp: Int = 0,
        minResizeHeightDp: Int = 0,
        maxResizeWidthDp: Int = 0,
        maxResizeHeightDp: Int = 0,
        resizeMode: Int = AppWidgetProviderInfo.RESIZE_BOTH,
        density: Float = 2f,
    ) = AppWidgetProviderInfo().apply {
        minWidth = (minWidthDp * density).toInt()
        minHeight = (minHeightDp * density).toInt()
        minResizeWidth = (minResizeWidthDp * density).toInt()
        minResizeHeight = (minResizeHeightDp * density).toInt()
        maxResizeWidth = (maxResizeWidthDp * density).toInt()
        maxResizeHeight = (maxResizeHeightDp * density).toInt()
        this.resizeMode = resizeMode
    }

    @Test
    fun `the digital clock resizes between two columns and its default`() {
        // AOSP DeskClock digital on the GrapheneOS image: 300x170 dp, resizable down to 136x59 dp.
        val info = info(300, 170, minResizeWidthDp = 136, minResizeHeightDp = 59)

        val limits = AndroidGridItemLimits.limitsFor(info, geometry, density = 2f)
        val default = AndroidGridItemLimits.defaultSpanFor(info, geometry, density = 2f)

        assertEquals(2, limits.minW)
        assertEquals(1, limits.minH)
        assertEquals(CellSize(3, 2), default)
    }

    @Test
    fun `a widget that does not resize has one size`() {
        val info = info(180, 80, resizeMode = AppWidgetProviderInfo.RESIZE_NONE)

        val limits = AndroidGridItemLimits.limitsFor(info, geometry, density = 2f)

        assertEquals(limits.minW, limits.maxW)
        assertEquals(limits.minH, limits.maxH)
        assertEquals(2, limits.minW)
        assertEquals(1, limits.minH)
    }

    @Test
    fun `the favorites widget, an unbound item and an unknown host id are unbounded`() {
        val lookup = AndroidGridItemLimits(context)
        val favorites = HomeGridItem(HomeGridLayouts.Phone, "dock", HomeGridWidgets.Favorites, x = 0, y = 5, w = 4, h = 1, position = 0)
        val unbound = HomeGridItem(HomeGridLayouts.Phone, "a", "com.example/.W", x = 0, y = 0, w = 1, h = 1, position = 1)
        val unknown = unbound.copy(id = "b", appWidgetId = 4711)

        assertEquals(SizeLimits.Unbounded, lookup.limitsFor(favorites, geometry))
        assertEquals(SizeLimits.Unbounded, lookup.limitsFor(unbound, geometry))
        assertEquals(SizeLimits.Unbounded, lookup.limitsFor(unknown, geometry))
        assertEquals(SizeLimits.Unbounded, GridItemLimits.Unbounded.limitsFor(unknown, geometry))
    }

    @Test
    fun `a bound item takes its limits from its provider info at the device density`() {
        val density = context.resources.displayMetrics.density
        val info = info(300, 170, minResizeWidthDp = 136, minResizeHeightDp = 59, density = density)
        val lookup = AndroidGridItemLimits(context) { id -> info.takeIf { id == 7 } }
        val bound = HomeGridItem(HomeGridLayouts.Phone, "clock", "com.android.deskclock/.Digital", x = 0, y = 0, w = 3, h = 1, appWidgetId = 7, position = 0)

        val limits = lookup.limitsFor(bound, geometry)

        assertEquals(AndroidGridItemLimits.limitsFor(info, geometry, density), limits)
        assertEquals(2, limits.minW)
    }
}

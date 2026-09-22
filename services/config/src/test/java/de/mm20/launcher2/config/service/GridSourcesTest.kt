package de.mm20.launcher2.config.service

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.Profile as ConfigProfile
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.profiles.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The real [GridLimitsSource]: a provider's declared px sizes against the
 * cell size of this display. Robolectric's default display is 320x470 dp at
 * mdpi (density 1), so px and dp coincide and four columns give cells of
 * (320 - 3 * 8) / 4 = 74 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h470dp-mdpi")
class GridSourcesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val personal: UserHandle = Process.myUserHandle()
    private val provider = ComponentName("com.example.clock", "com.example.clock.Widget")

    private lateinit var source: AppWidgetGridLimitsSource

    private class Resolver(private val personal: Profile?) : ProfileResolver {
        override fun getProfile(type: Profile.Type): Profile? =
            if (type == Profile.Type.Personal) personal else null

        override suspend fun getProfile(userHandle: UserHandle): Profile? =
            personal?.takeIf { it.userHandle == userHandle }
    }

    @Before
    fun setUp() {
        source = AppWidgetGridLimitsSource(context, Resolver(Profile(Profile.Type.Personal, personal, 0)))
    }

    private fun install(
        minWidth: Int, minHeight: Int,
        minResizeWidth: Int = 0, minResizeHeight: Int = 0,
        maxResizeWidth: Int = 0, maxResizeHeight: Int = 0,
        targetCellWidth: Int = 0, targetCellHeight: Int = 0,
        resizeMode: Int = AppWidgetProviderInfo.RESIZE_BOTH,
    ) {
        val info = AppWidgetProviderInfo().also {
            it.provider = provider
            it.minWidth = minWidth
            it.minHeight = minHeight
            it.minResizeWidth = minResizeWidth
            it.minResizeHeight = minResizeHeight
            it.maxResizeWidth = maxResizeWidth
            it.maxResizeHeight = maxResizeHeight
            it.targetCellWidth = targetCellWidth
            it.targetCellHeight = targetCellHeight
            it.resizeMode = resizeMode
        }
        shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(personal, info)
    }

    @Test
    fun `a declared minimum of one cell gives a 1x1 default and limits from the resize sizes`() {
        install(minWidth = 40, minHeight = 40, maxResizeWidth = 320, maxResizeHeight = 160)

        val limits = source.lookup(provider.flattenToString(), ConfigProfile.Personal, columns = 4)!!

        assertEquals(CellSize(1, 1), limits.default)
        assertEquals(1, limits.limits.minW)
        assertEquals(1, limits.limits.minH)
        // The maximum is the smaller of the two profiles' spans: 320 dp is
        // 4 portrait cells but only 3 landscape cells (111.5 dp each); 160 dp
        // needs 3 rows of 74 dp either way.
        assertEquals(3, limits.limits.maxW)
        assertEquals(3, limits.limits.maxH)
    }

    @Test
    fun `a wide minimum spans several cells, the Launcher3 way`() {
        // 250 dp needs ceil((250 + 8) / (74 + 8)) = 4 cells, 110 dp needs 2.
        install(minWidth = 250, minHeight = 110)

        val limits = source.lookup(provider.flattenToString(), null, columns = 4)!!

        assertEquals(CellSize(4, 2), limits.default)
    }

    @Test
    fun `target cells win when they lie inside the limits`() {
        install(minWidth = 250, minHeight = 40, minResizeWidth = 110, targetCellWidth = 3, targetCellHeight = 1)

        val limits = source.lookup(provider.flattenToString(), ConfigProfile.Personal, columns = 4)!!

        assertEquals(CellSize(3, 1), limits.default)
    }

    @Test
    fun `an axis without resize mode is pinned to its default span`() {
        install(minWidth = 110, minHeight = 40, resizeMode = AppWidgetProviderInfo.RESIZE_NONE)

        val limits = source.lookup(provider.flattenToString(), ConfigProfile.Personal, columns = 4)!!

        assertEquals(SizeLimits(2, 1, 2, 1), limits.limits)
    }

    @Test
    fun `an unknown component, a profile the device lacks and an unflattenable string give null`() {
        install(minWidth = 40, minHeight = 40)

        assertNull(source.lookup("com.other/.Widget", ConfigProfile.Personal, 4))
        assertNull(source.lookup(provider.flattenToString(), ConfigProfile.Work, 4))
        assertNull(source.lookup("not a component", ConfigProfile.Personal, 4))
        assertNull(AppWidgetGridLimitsSource(context, Resolver(null)).lookup(provider.flattenToString(), null, 4))
    }

    @Test
    fun `the default rows source answers six for every layout until the renderer lands`() {
        val rows = DefaultGridRowsSource()

        assertEquals(6, rows.rows("phone"))
        assertEquals(6, rows.rows("fold"))
        assertTrue(DefaultGridRowsSource.DefaultRows == 6)
    }
}

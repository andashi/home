package de.mm20.launcher2.homegrid

import org.junit.Assert.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MeasuredGridRowsTest {

    /**
     * Only the device that shows a layout knows its rows (#90), and before the
     * first render not even that one does. A guess of six fitted a config
     * pushed before the Fold's first draw to six rows although it has seven,
     * and dropped its seventh row (grid-overflow on the dock, 2026-09-26).
     */
    @Test
    fun `before anything was measured no layout has rows`() {
        val rows = MeasuredGridRows(ownLayout = HomeGridLayouts.Fold)

        assertEquals(null, rows.rows(HomeGridLayouts.Fold))
        assertEquals(null, rows.rows(HomeGridLayouts.Phone))
    }

    @Test
    fun `a measurement answers for its layout only`() {
        val rows = MeasuredGridRows()

        rows.update(HomeGridLayouts.Phone, 8)

        assertEquals(8, rows.rows(HomeGridLayouts.Phone))
        assertEquals(null, rows.rows(HomeGridLayouts.Fold))
    }

    @Test
    fun `later measurements win`() {
        val rows = MeasuredGridRows()

        rows.update(HomeGridLayouts.Fold, 7)
        rows.update(HomeGridLayouts.Fold, 5)

        assertEquals(5, rows.rows(HomeGridLayouts.Fold))
    }

    /** #90: a phone does not know the Fold's rows and must not guess six. */
    @Test
    fun `a layout this device does not render has no rows`() {
        val rows = MeasuredGridRows(ownLayout = HomeGridLayouts.Phone)
        rows.update(HomeGridLayouts.Phone, 6)

        assertEquals(6, rows.rows(HomeGridLayouts.Phone))
        assertEquals(null, rows.rows(HomeGridLayouts.Fold))
    }

    /**
     * What the config store waits for to fit a layout it kept as written:
     * collected, the same rows twice are one emission, and a change is another
     * (#178 review: the final value alone would not show a repeat).
     */
    @Test
    fun `the measurements are observable, a repeated one once`() = runTest {
        val rows = MeasuredGridRows(ownLayout = HomeGridLayouts.Fold)
        val seen = mutableListOf<Map<String, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rows.measurements.collect { seen += it } }

        rows.update(HomeGridLayouts.Fold, 7)
        rows.update(HomeGridLayouts.Fold, 7)
        rows.update(HomeGridLayouts.Fold, 6)

        assertEquals(
            listOf(emptyMap(), mapOf(HomeGridLayouts.Fold to 7), mapOf(HomeGridLayouts.Fold to 6)),
            seen,
        )
    }
}

package de.mm20.launcher2.homegrid

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasuredGridRowsTest {

    @Test
    fun `answers the default for every layout before anything was measured`() {
        val rows = MeasuredGridRows()

        assertEquals(6, rows.rows(HomeGridLayouts.Phone))
        assertEquals(6, rows.rows(HomeGridLayouts.Fold))
        assertEquals(6, MeasuredGridRows.DefaultRows)
    }

    @Test
    fun `a measurement replaces the default for its layout only`() {
        val rows = MeasuredGridRows()

        rows.update(HomeGridLayouts.Phone, 8)

        assertEquals(8, rows.rows(HomeGridLayouts.Phone))
        assertEquals(6, rows.rows(HomeGridLayouts.Fold))
    }

    @Test
    fun `later measurements win`() {
        val rows = MeasuredGridRows()

        rows.update(HomeGridLayouts.Fold, 7)
        rows.update(HomeGridLayouts.Fold, 5)

        assertEquals(5, rows.rows(HomeGridLayouts.Fold))
    }

    @Test
    fun `a custom default is honoured`() {
        assertEquals(4, MeasuredGridRows(defaultRows = 4).rows(HomeGridLayouts.Phone))
    }
}

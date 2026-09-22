package de.mm20.launcher2.homegrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class HomeGridGeometryTest {

    @Test
    fun `phone cells are square and fill the width, rows follow the height`() {
        // A 412 dp phone window minus the 8 dp gutters on each side.
        val g = HomeGridGeometry.derive(FormFactor.Phone, columns = 4, widthDp = 396f, heightDp = 800f)

        assertEquals(HomeGridLayouts.Phone, g.layout)
        assertEquals(4, g.spec.columns)
        assertNull(g.spec.foldColumn)
        assertEquals(4, g.visibleColumns)
        assertFalse(g.isCover)
        assertEquals(93f, g.cellDp, 0.001f) // (396 - 3 * 8) / 4
        assertEquals(396f, g.cellDp * 4 + g.gapDp * 3, 0.001f)
        assertEquals(floor((800f + 8f) / (93f + 8f)).toInt(), g.rows)
        assertEquals(8, g.rows)
    }

    @Test
    fun `a fold's inner display doubles the columns with the fold line in the middle`() {
        val g = HomeGridGeometry.derive(FormFactor.Fold, columns = 4, widthDp = 790f, heightDp = 780f)

        assertEquals(HomeGridLayouts.Fold, g.layout)
        assertEquals(8, g.spec.columns)
        assertEquals(4, g.spec.foldColumn)
        assertEquals(8, g.visibleColumns)
        assertFalse(g.isCover)
        assertEquals((790f - 7 * 8f) / 8, g.cellDp, 0.001f)
        assertEquals(floor((780f + 8f) / (g.cellDp + 8f)).toInt(), g.rows)
    }

    @Test
    fun `a fold's cover shows the left half of the same layout`() {
        val g = HomeGridGeometry.derive(FormFactor.Fold, columns = 4, widthDp = 396f, heightDp = 800f)

        assertEquals(HomeGridLayouts.Fold, g.layout)
        assertEquals(8, g.spec.columns)
        assertEquals(4, g.spec.foldColumn)
        assertEquals(4, g.visibleColumns)
        assertTrue(g.isCover)
        // Cells are sized to the four visible columns, the same as a phone.
        assertEquals(93f, g.cellDp, 0.001f)
    }

    @Test
    fun `the cover threshold is the expanded breakpoint`() {
        assertTrue(HomeGridGeometry.derive(FormFactor.Fold, 4, 599f, 800f).isCover)
        assertFalse(HomeGridGeometry.derive(FormFactor.Fold, 4, 600f, 800f).isCover)
    }

    @Test
    fun `at least one row even on a tiny window`() {
        val g = HomeGridGeometry.derive(FormFactor.Phone, 4, widthDp = 396f, heightDp = 10f)
        assertEquals(1, g.rows)
    }

    @Test
    fun `the configured column count is honoured on a phone`() {
        val g = HomeGridGeometry.derive(FormFactor.Phone, columns = 5, widthDp = 396f, heightDp = 800f)
        assertEquals(5, g.spec.columns)
        assertEquals(5, g.visibleColumns)
        assertEquals((396f - 4 * 8f) / 5, g.cellDp, 0.001f)
    }

    @Test
    fun `the gap is a parameter with the documented default`() {
        assertEquals(8f, HomeGridGeometry.GapDp, 0f)
        val g = HomeGridGeometry.derive(FormFactor.Phone, 4, 400f, 800f, gapDp = 0f)
        assertEquals(100f, g.cellDp, 0.001f)
        assertEquals(0f, g.gapDp, 0f)
    }
}

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
    fun `a fold's cover shows the right half of the same layout`() {
        val g = HomeGridGeometry.derive(FormFactor.Fold, columns = 4, widthDp = 396f, heightDp = 800f)

        // #93: the cover is the right half of the inner display.
        assertEquals(4, g.coverFirstColumn)
        assertEquals(4, g.firstVisibleColumn)
        assertEquals(4 until 8, g.visibleRange)

        assertEquals(HomeGridLayouts.Fold, g.layout)
        assertEquals(8, g.spec.columns)
        assertEquals(4, g.spec.foldColumn)
        assertEquals(4, g.visibleColumns)
        assertTrue(g.isCover)
        // Cells are sized to the four visible columns, the same as a phone.
        assertEquals(93f, g.cellDp, 0.001f)
    }

    /** The inner display draws everything; it still knows which half the cover is. */
    @Test
    fun `the inner display draws all columns and knows the cover's half`() {
        val g = HomeGridGeometry.derive(FormFactor.Fold, columns = 4, widthDp = 774f, heightDp = 800f)

        assertEquals(4, g.coverFirstColumn)
        assertEquals(0, g.firstVisibleColumn)
        assertEquals(0 until 8, g.visibleRange)
    }

    /** Control: a phone draws all its columns from 0. */
    @Test
    fun `a phone draws its columns from zero`() {
        val g = HomeGridGeometry.derive(FormFactor.Phone, columns = 4, widthDp = 396f, heightDp = 800f)

        assertEquals(0, g.firstVisibleColumn)
        assertEquals(0 until 4, g.visibleRange)
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

    /**
     * #118: the grid is told its window a frame or two before the geometry
     * for it arrives; drawn meanwhile with the old display's geometry, the
     * dock showed mid-screen on unfold. The geometry knows its window.
     */
    @Test
    fun `a geometry is for the window it was derived from, not another`() {
        val cover = HomeGridGeometry.derive(FormFactor.Fold, columns = 4, widthDp = 396f, heightDp = 800f)

        assertTrue(cover.isFor(396f, 800f))
        assertFalse(cover.isFor(790f, 780f))
        assertFalse(cover.isFor(396f, 700f))
    }

    /** Sub-pixel noise in the measured size is the same window. */
    @Test
    fun `a size within half a dp is the same window`() {
        val g = HomeGridGeometry.derive(FormFactor.Phone, columns = 4, widthDp = 396f, heightDp = 800f)

        assertTrue(g.isFor(396.3f, 799.8f))
    }
}

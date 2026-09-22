package de.mm20.launcher2.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class CellMathTest {

    /** Android's documented 5x4 handset table: an n x m widget gets (73n - 16) x (118m - 16) dp. */
    private val docsPortrait = CellMetrics(cellWidthDp = 57f, cellHeightDp = 102f, gapDp = 16f)

    @Test
    fun `cell math matches the documented 5x4 table`() {
        for (n in 1..4) {
            assertEquals("width of $n cells", n, CellMath.spanFor(73f * n - 16, docsPortrait.cellWidthDp, docsPortrait.gapDp))
            assertEquals("height of $n cells", n, CellMath.spanFor(118f * n - 16, docsPortrait.cellHeightDp, docsPortrait.gapDp))
        }
        // one dp more than n cells provide needs n + 1 cells
        assertEquals(2, CellMath.spanFor(73f - 16 + 1, docsPortrait.cellWidthDp, docsPortrait.gapDp))
    }

    @Test
    fun `span for 40 110 180 250 dp on a 100 dp cell is 1 2 2 3`() {
        val cell = 100f
        val gap = 8f
        assertEquals(1, CellMath.spanFor(40f, cell, gap))
        assertEquals(2, CellMath.spanFor(110f, cell, gap))
        assertEquals(2, CellMath.spanFor(180f, cell, gap))
        assertEquals(3, CellMath.spanFor(250f, cell, gap))
        assertEquals("never below one cell", 1, CellMath.spanFor(0f, cell, gap))
    }

    @Test
    fun `cellDp divides the width after the gaps are taken out`() {
        assertEquals(97f, CellMath.cellDp(412f, 4, 8f), 0.001f)
        assertEquals(412f, 4 * 97f + 3 * 8f, 0.001f)
        assertEquals(100f, CellMath.cellDp(100f, 1, 8f), 0.001f)
    }

    private val phone = CellMetrics(cellWidthDp = 97f, cellHeightDp = 97f, gapDp = 8f)
    private val landscape = CellMetrics(cellWidthDp = 130f, cellHeightDp = 60f, gapDp = 8f)

    @Test
    fun `default span comes from the minimum size`() {
        // AOSP digital clock: 202 x 80 dp, resizable down to 24 dp, target 3x1
        val clock = ProviderSizes(202f, 80f, minResizeWidthDp = 202f, minResizeHeightDp = 24f, targetCellWidth = 3, targetCellHeight = 1)
        assertEquals(CellSize(3, 1), CellMath.defaultSpanFor(clock, phone))
        // without a target cell, the minimum size decides: 250 x 40 -> 3 x 1
        val media = ProviderSizes(250f, 40f)
        assertEquals(CellSize(3, 1), CellMath.defaultSpanFor(media, phone))
    }

    @Test
    fun `target cell is used only inside the limits`() {
        // target 1x1 but the minimum resize needs 2 columns -> target ignored
        val p = ProviderSizes(150f, 40f, minResizeWidthDp = 150f, targetCellWidth = 1, targetCellHeight = 1)
        assertEquals(CellSize(2, 1), CellMath.defaultSpanFor(p, phone))
        // target 4x2 inside [min, max] -> used even though the minimum is smaller
        val q = ProviderSizes(40f, 40f, targetCellWidth = 4, targetCellHeight = 2)
        assertEquals(CellSize(4, 2), CellMath.defaultSpanFor(q, phone))
    }

    @Test
    fun `limits combine both profiles the safe way`() {
        // 40 dp tall: one row in portrait (97), also one in landscape (60)
        val bar = ProviderSizes(250f, 40f)
        assertEquals(SizeLimits(3, 1, Int.MAX_VALUE, Int.MAX_VALUE), CellMath.limitsFor(bar, phone, landscape))
        // 80 dp tall: one row in portrait, two in landscape -> minimum height 2
        val tall = ProviderSizes(250f, 80f)
        assertEquals(2, CellMath.limitsFor(tall, phone, landscape).minH)
        assertEquals(1, CellMath.limitsFor(tall, phone).minH)
    }

    @Test
    fun `minResize below min lowers the minimum, maxResize caps it`() {
        // Home Assistant todo: 150 x 200, resizable down to 150 x 80, no maximum
        val todo = ProviderSizes(150f, 200f, minResizeWidthDp = 150f, minResizeHeightDp = 80f)
        assertEquals(SizeLimits(2, 1, Int.MAX_VALUE, Int.MAX_VALUE), CellMath.limitsFor(todo, phone))
        assertEquals(CellSize(2, 2), CellMath.defaultSpanFor(todo, phone))
        // a maximum of 300 x 100 dp: 3 x 1 cells
        val capped = ProviderSizes(100f, 40f, maxResizeWidthDp = 300f, maxResizeHeightDp = 100f)
        assertEquals(SizeLimits(1, 1, 3, 1), CellMath.limitsFor(capped, phone))
    }

    @Test
    fun `minResize larger than min is ignored as the platform does`() {
        val p = ProviderSizes(100f, 40f, minResizeWidthDp = 300f, minResizeHeightDp = 300f)
        assertEquals(SizeLimits(1, 1, Int.MAX_VALUE, Int.MAX_VALUE), CellMath.limitsFor(p, phone))
    }

    @Test
    fun `an axis without resizing is pinned to the default span`() {
        val fixed = ProviderSizes(250f, 40f, minResizeWidthDp = 40f, maxResizeWidthDp = 400f, resizeHorizontal = false, resizeVertical = true)
        assertEquals(SizeLimits(3, 1, 3, Int.MAX_VALUE), CellMath.limitsFor(fixed, phone))
        val none = ProviderSizes(250f, 40f, resizeHorizontal = false, resizeVertical = false)
        assertEquals(SizeLimits(3, 1, 3, 1), CellMath.limitsFor(none, phone))
    }

    @Test
    fun `the default span always lies inside the limits`() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val p = ProviderSizes(
                minWidthDp = 20f + rnd.nextInt(400),
                minHeightDp = 20f + rnd.nextInt(400),
                minResizeWidthDp = rnd.nextInt(400).toFloat(),
                minResizeHeightDp = rnd.nextInt(400).toFloat(),
                maxResizeWidthDp = if (rnd.nextBoolean()) 0f else 100f + rnd.nextInt(600),
                maxResizeHeightDp = if (rnd.nextBoolean()) 0f else 100f + rnd.nextInt(600),
                targetCellWidth = rnd.nextInt(6),
                targetCellHeight = rnd.nextInt(6),
                resizeHorizontal = rnd.nextBoolean(),
                resizeVertical = rnd.nextBoolean(),
            )
            val limits = CellMath.limitsFor(p, phone, landscape)
            val def = CellMath.defaultSpanFor(p, phone, landscape)
            val label = "provider=$p limits=$limits default=$def"
            org.junit.Assert.assertTrue(label, def.w in limits.minW..limits.maxW)
            org.junit.Assert.assertTrue(label, def.h in limits.minH..limits.maxH)
        }
    }
}

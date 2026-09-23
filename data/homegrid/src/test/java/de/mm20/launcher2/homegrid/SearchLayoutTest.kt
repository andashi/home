package de.mm20.launcher2.homegrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search follows the home grid (#91): its columns, its pitch, and the fold's seam. */
class SearchLayoutTest {

    private fun geometry(formFactor: FormFactor, widthDp: Float, columns: Int = 4) =
        HomeGridGeometry.derive(formFactor, columns, widthDp, heightDp = 800f)

    @Test
    fun `a phone shows the home grid's columns in one column of results`() {
        assertEquals(SearchLayout.Single(4), SearchLayout.from(geometry(FormFactor.Phone, 396f)))
        assertEquals(SearchLayout.Single(5), SearchLayout.from(geometry(FormFactor.Phone, 396f, columns = 5)))
    }

    @Test
    fun `the fold's cover shows the configured columns, not the fold layout's eight`() {
        assertEquals(SearchLayout.Single(4), SearchLayout.from(geometry(FormFactor.Fold, 396f)))
    }

    @Test
    fun `the fold's inner display has two panes that meet at the seam`() {
        val g = geometry(FormFactor.Fold, 774f)
        val layout = SearchLayout.from(g) as SearchLayout.TwoPane
        val half = g.cellDp * 4 + g.gapDp * 3

        assertEquals(4, layout.columns)
        // The apps pane is the cover's half, exactly four home cells wide.
        assertEquals(SearchLayout.Pane(0f, half), layout.apps)
        // The results pane starts one gap after the seam and ends at the edge.
        assertEquals(half + g.gapDp, layout.results.startDp, 0.001f)
        assertEquals(774f, layout.results.endDp, 0.001f)
    }

    @Test
    fun `nothing crosses the seam`() {
        val g = geometry(FormFactor.Fold, 774f)
        val layout = SearchLayout.from(g) as SearchLayout.TwoPane
        val seam = g.cellDp * 4 + g.gapDp * 3 + g.gapDp / 2

        assertTrue(layout.apps.endDp <= seam)
        assertTrue(layout.results.startDp >= seam)
    }

    @Test
    fun `the apps pane follows the cover to the right half`() {
        // #93: the cover shows columns 4-7; the apps go with it.
        val g = geometry(FormFactor.Fold, 774f).copy(coverFirstColumn = 4)
        val layout = SearchLayout.from(g) as SearchLayout.TwoPane
        val half = g.cellDp * 4 + g.gapDp * 3

        assertEquals(SearchLayout.Pane(0f, half), layout.results)
        assertEquals(half + g.gapDp, layout.apps.startDp, 0.001f)
        assertEquals(774f, layout.apps.endDp, 0.001f)
    }

    @Test
    fun `panes follow the configured columns`() {
        val g = geometry(FormFactor.Fold, 774f, columns = 5)
        val layout = SearchLayout.from(g) as SearchLayout.TwoPane

        assertEquals(5, layout.columns)
        assertEquals(g.cellDp * 5 + g.gapDp * 4, layout.apps.widthDp, 0.001f)
    }
}

package de.mm20.launcher2.ui.launcher.grid

import org.junit.Assert.assertEquals
import org.junit.Test

/** #111: a dock with fewer favorites than cells centres them. */
class DockPlacementsTest {

    private fun cells(count: Int, columns: Int, rows: Int) =
        dockPlacements(count, columns, rows).map { it.row to it.column }

    @Test
    fun `a partial row is centred`() {
        assertEquals(listOf(0f to 0.5f, 0f to 1.5f, 0f to 2.5f), cells(3, columns = 4, rows = 1))
    }

    @Test
    fun `a column dock centres its favorites vertically`() {
        assertEquals(listOf(2f to 0f, 3f to 0f, 4f to 0f), cells(3, columns = 1, rows = 7))
    }

    @Test
    fun `rows fill in order and the last one is centred`() {
        assertEquals(
            listOf(0f to 0f, 0f to 1f, 0f to 2f, 0f to 3f, 1f to 1f, 1f to 2f),
            cells(6, columns = 4, rows = 2),
        )
    }

    @Test
    fun `used rows are centred in the dock's height`() {
        assertEquals(listOf(0.5f to 0.5f, 0.5f to 1.5f, 0.5f to 2.5f), cells(3, columns = 4, rows = 2))
    }

    /** Control: a full dock is today's layout, favorite k in cell k. */
    @Test
    fun `a full dock is unchanged`() {
        assertEquals(
            (0 until 8).map { (it / 4).toFloat() to (it % 4).toFloat() },
            cells(8, columns = 4, rows = 2),
        )
    }

    @Test
    fun `more favorites than cells show the first ones, an empty dock none`() {
        assertEquals(4, dockPlacements(9, columns = 4, rows = 1).size)
        assertEquals((0 until 4).toList(), dockPlacements(9, columns = 4, rows = 1).map { it.index })
        assertEquals(emptyList<DockPlacement>(), dockPlacements(0, columns = 4, rows = 1))
    }
}

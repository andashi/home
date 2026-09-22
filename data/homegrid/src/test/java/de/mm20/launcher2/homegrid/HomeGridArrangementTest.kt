package de.mm20.launcher2.homegrid

import de.mm20.launcher2.grid.LayoutIssue
import de.mm20.launcher2.grid.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridArrangementTest {

    private val phone = HomeGridGeometry.derive(FormFactor.Phone, 4, 396f, 620f) // 6 rows of 93 dp
    private val inner = HomeGridGeometry.derive(FormFactor.Fold, 4, 790f, 600f)
    private val cover = HomeGridGeometry.derive(FormFactor.Fold, 4, 396f, 620f)

    private fun item(
        id: String,
        x: Int,
        y: Int,
        w: Int = 1,
        h: Int = 1,
        layout: String = HomeGridLayouts.Phone,
        widget: String = "com.example/.Widget",
        position: Int = 0,
    ) = HomeGridItem(layout = layout, id = id, widget = widget, x = x, y = y, w = w, h = h, position = position)

    private fun favorites(x: Int, y: Int, w: Int, h: Int, layout: String = HomeGridLayouts.Phone) =
        item("dock", x, y, w, h, layout, HomeGridWidgets.Favorites, position = 99)

    private fun List<HomeGridCell>.spanOf(id: String) = first { it.item.id == id }.span

    @Test
    fun `items that fit are drawn where they are`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 2, 0, 2, 2), favorites(0, 5, 4, 1))

        val result = HomeGridArrangement.arrange(phone, items)

        assertEquals(listOf("a", "b", "dock"), result.cells.map { it.item.id })
        assertEquals(Span(0, 0, 2, 2), result.cells.spanOf("a"))
        assertEquals(Span(0, 5, 4, 1), result.cells.spanOf("dock"))
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `an overlapping later item is re-placed and reported`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 1, 1, 2, 2))

        val result = HomeGridArrangement.arrange(phone, items)

        assertEquals(Span(0, 0, 2, 2), result.cells.spanOf("a"))
        assertEquals(Span(2, 0, 2, 2), result.cells.spanOf("b"))
        assertTrue(result.issues.any { it is LayoutIssue.Overlap })
    }

    @Test
    fun `an item sticking out is slid back in`() {
        val result = HomeGridArrangement.arrange(phone, listOf(item("a", 3, 5, 2, 2)))

        assertEquals(Span(2, 4, 2, 2), result.cells.spanOf("a"))
    }

    @Test
    fun `the cover draws only the left half and clips the favorites strip`() {
        val items = listOf(
            item("a", 0, 0, 2, 2, HomeGridLayouts.Fold),
            item("right", 5, 0, 2, 2, HomeGridLayouts.Fold),
            favorites(0, 5, 8, 1, HomeGridLayouts.Fold),
        )

        val result = HomeGridArrangement.arrange(cover, items)

        assertEquals(listOf("a", "dock"), result.cells.map { it.item.id })
        assertEquals(Span(0, 5, 4, 1), result.cells.spanOf("dock"))
        assertTrue(result.cells.all { it.span.x + it.span.w <= cover.visibleColumns })
    }

    @Test
    fun `the inner display draws both halves and keeps an AppWidget off the fold line`() {
        val items = listOf(
            item("a", 0, 0, 2, 2, HomeGridLayouts.Fold),
            item("right", 5, 0, 2, 2, HomeGridLayouts.Fold),
            item("crossing", 3, 3, 2, 1, HomeGridLayouts.Fold),
            favorites(0, 5, 8, 1, HomeGridLayouts.Fold),
        )

        val result = HomeGridArrangement.arrange(inner, items)

        assertEquals(listOf("a", "right", "crossing", "dock"), result.cells.map { it.item.id })
        assertEquals(Span(5, 0, 2, 2), result.cells.spanOf("right"))
        val crossing = result.cells.spanOf("crossing")
        assertTrue(crossing.x >= 4 || crossing.x + crossing.w <= 4)
        assertEquals(Span(0, 5, 8, 1), result.cells.spanOf("dock"))
        assertTrue(result.issues.any { it is LayoutIssue.CrossesFold })
    }

    @Test
    fun `an empty layout is an empty window`() {
        val result = HomeGridArrangement.arrange(phone, emptyList())
        assertTrue(result.cells.isEmpty())
        assertTrue(result.issues.isEmpty())
    }
}

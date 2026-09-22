package de.mm20.launcher2.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridLayoutTest {

    // --- validate -------------------------------------------------------

    @Test
    fun `validate accepts a clean layout`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 2, 0, 2, 2), favorites(0, 5, 4, 1))
        assertEquals(emptyList<LayoutIssue>(), GridLayout.validate(Phone, items))
    }

    @Test
    fun `validate reports overlap, out of bounds and fold crossing`() {
        val items = listOf(
            item("a", 0, 0, 2, 2),
            item("b", 1, 1, 2, 2),        // overlaps a
            item("c", 7, 5, 2, 1),        // right edge at 9 > 8 columns
            item("d", 3, 3, 2, 1),        // crosses the fold at column 4
            favorites(0, 4, 8, 1),        // crosses the fold, allowed
        )
        val issues = GridLayout.validate(Fold, items)
        assertEquals(
            listOf(
                LayoutIssue.CrossesFold("d"),
                LayoutIssue.OutOfBounds("c", Span(7, 5, 2, 1)),
                LayoutIssue.Overlap("a", "b"),
            ),
            issues.sortedBy { it::class.simpleName },
        )
    }

    // --- place ----------------------------------------------------------

    @Test
    fun `place fills reading order`() {
        var items = emptyList<GridItem>()
        val placed = mutableListOf<Span>()
        repeat(6) { n ->
            val p = GridLayout.place(Phone, items, item("w$n", 0, 0, 2, 2))!!
            placed += p.span
            items = items + p
        }
        assertEquals(
            listOf(Span(0, 0, 2, 2), Span(2, 0, 2, 2), Span(0, 2, 2, 2), Span(2, 2, 2, 2), Span(0, 4, 2, 2), Span(2, 4, 2, 2)),
            placed,
        )
        assertNull("the grid is full", GridLayout.place(Phone, items, item("w6", 0, 0, 1, 1)))
    }

    @Test
    fun `place skips gaps that are too small and respects the fold`() {
        val items = listOf(item("a", 0, 0, 3, 1))
        // one free cell at (3,0) is too small for a 2x1; the next row is free
        assertEquals(Span(0, 1, 2, 1), GridLayout.place(Phone, items, item("b", 0, 0, 2, 1))!!.span)
        // on the fold: (3,0) would cross column 4, so the 2x1 lands at (4,0)
        assertEquals(Span(4, 0, 2, 1), GridLayout.place(Fold, items, item("b", 0, 0, 2, 1))!!.span)
        // favorites may cross
        assertEquals(Span(3, 0, 2, 1), GridLayout.place(Fold, items, favorites(0, 0, 2, 1))!!.span)
    }

    @Test
    fun `place clamps the size to the limits and the grid before searching`() {
        val tooBig = item("a", 0, 0, 9, 9)
        assertEquals(Span(0, 0, 4, 6), GridLayout.place(Phone, emptyList(), tooBig)!!.span)
        val tooSmall = item("b", 0, 0, 1, 1, limits = SizeLimits(2, 2, 4, 4))
        assertEquals(Span(0, 0, 2, 2), GridLayout.place(Phone, emptyList(), tooSmall)!!.span)
    }

    // --- move -----------------------------------------------------------

    @Test
    fun `move onto a free cell changes only that item`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 2, 0, 2, 2))
        val result = GridLayout.move(Phone, items, "a", Span(0, 2, 2, 2))
        assertTrue(result.isClean)
        assertEquals(Span(0, 2, 2, 2), result.items.spanOf("a"))
        assertEquals(Span(2, 0, 2, 2), result.items.spanOf("b"))
        assertEquals(listOf("a", "b"), result.items.map { it.id })
    }

    @Test
    fun `move onto an occupied cell pushes the occupant down`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 0, 2, 2, 2))
        val result = GridLayout.move(Phone, items, "a", Span(0, 2, 2, 2))
        assertTrue(result.isClean)
        assertEquals(Span(0, 2, 2, 2), result.items.spanOf("a"))
        assertEquals(Span(0, 4, 2, 2), result.items.spanOf("b"))
        assertNoOverlap(result.items)
    }

    @Test
    fun `push-down cascades to what the displaced item lands on`() {
        val items = listOf(item("a", 0, 0, 4, 1), item("b", 0, 1, 4, 2), item("c", 0, 3, 4, 1))
        // a goes onto b's rows; b must move below a, landing on c, which moves below b
        val result = GridLayout.move(Phone, items, "a", Span(0, 1, 4, 1))
        assertTrue(result.issues.toString(), result.isClean)
        assertEquals(Span(0, 1, 4, 1), result.items.spanOf("a"))
        assertEquals(Span(0, 2, 4, 2), result.items.spanOf("b"))
        assertEquals(Span(0, 4, 4, 1), result.items.spanOf("c"))
        assertNoOverlap(result.items)
    }

    @Test
    fun `push-down never touches items above the drop row`() {
        val items = listOf(item("top", 0, 0, 4, 1), item("mid", 0, 1, 2, 1), item("low", 0, 2, 2, 2))
        val result = GridLayout.move(Phone, items, "mid", Span(0, 2, 2, 1))
        assertTrue(result.isClean)
        assertEquals(Span(0, 0, 4, 1), result.items.spanOf("top"))
        assertEquals(Span(0, 2, 2, 1), result.items.spanOf("mid"))
        assertEquals(Span(0, 3, 2, 2), result.items.spanOf("low"))
    }

    @Test
    fun `move is deterministic for the same input`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("c", 2, 2, 2, 2), item("b", 0, 2, 2, 2), item("d", 2, 0, 2, 2))
        val first = GridLayout.move(Phone, items, "a", Span(1, 1, 2, 2))
        repeat(20) {
            assertEquals(first, GridLayout.move(Phone, items, "a", Span(1, 1, 2, 2)))
        }
        assertNoOverlap(first.items)
    }

    @Test
    fun `move rejects the input unchanged when a displaced item runs out of rows`() {
        val items = listOf(item("a", 0, 0, 4, 2), item("b", 0, 2, 4, 2), item("c", 0, 4, 4, 2))
        val result = GridLayout.move(Phone, items, "a", Span(0, 2, 4, 2))
        assertEquals(items, result.items)
        assertEquals(1, result.issues.size)
        assertTrue(result.issues.single() is LayoutIssue.Overflow)
    }

    @Test
    fun `move clamps size to limits and position to the grid`() {
        val items = listOf(item("a", 0, 0, 2, 2, limits = SizeLimits(2, 2, 3, 3)))
        val result = GridLayout.move(Phone, items, "a", Span(3, 5, 9, 1))
        assertTrue(result.isClean)
        // w clamped 9 -> 3, h clamped 1 -> 2, then x 3 -> 1 and y 5 -> 4 so it stays inside
        assertEquals(Span(1, 4, 3, 2), result.items.spanOf("a"))
    }

    @Test
    fun `move nudges an item clear of the fold line`() {
        val items = listOf(item("a", 0, 0, 2, 1))
        // more of it would sit on the left: 3..4 -> x = 2..3
        assertEquals(Span(2, 0, 2, 1), GridLayout.move(Fold, items, "a", Span(3, 0, 2, 1)).items.spanOf("a"))
        // a 3-wide at x = 2 covers 2..4: two cells left, one right -> left side
        val wide = listOf(item("a", 0, 0, 3, 1))
        assertEquals(Span(1, 0, 3, 1), GridLayout.move(Fold, wide, "a", Span(2, 0, 3, 1)).items.spanOf("a"))
        // a 3-wide at x = 3 covers 3..5: one cell left, two right -> right side
        assertEquals(Span(4, 0, 3, 1), GridLayout.move(Fold, wide, "a", Span(3, 0, 3, 1)).items.spanOf("a"))
    }

    @Test
    fun `move rejects an item wider than half the fold that may not cross`() {
        val items = listOf(item("a", 0, 0, 5, 1))
        val result = GridLayout.move(Fold, items, "a", Span(2, 0, 5, 1))
        assertEquals(items, result.items)
        assertEquals(listOf(LayoutIssue.CrossesFold("a")), result.issues)
    }

    @Test
    fun `favorites may be moved across the fold`() {
        val items = listOf(favorites(0, 5, 4, 1))
        val result = GridLayout.move(Fold, items, "favorites", Span(0, 5, 8, 1))
        assertTrue(result.isClean)
        assertEquals(Span(0, 5, 8, 1), result.items.spanOf("favorites"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `move of an unknown id is a programming error`() {
        GridLayout.move(Phone, emptyList(), "ghost", Span(0, 0, 1, 1))
    }

    // --- resize ---------------------------------------------------------

    @Test
    fun `resize clamps to limits and grid`() {
        val items = listOf(item("a", 3, 5, 1, 1, limits = SizeLimits(1, 1, 2, 2)))
        val grown = GridLayout.resize(Phone, items, "a", 5, 5)
        assertTrue(grown.isClean)
        // max 2x2, slid left and up to stay inside
        assertEquals(Span(2, 4, 2, 2), grown.items.spanOf("a"))
        val shrunk = GridLayout.resize(Phone, grown.items, "a", 0, 0)
        assertEquals(Span(2, 4, 1, 1), shrunk.items.spanOf("a"))
    }

    @Test
    fun `resize pushes down what it grows over`() {
        val items = listOf(item("a", 0, 0, 2, 1), item("b", 0, 1, 2, 1))
        val result = GridLayout.resize(Phone, items, "a", 2, 2)
        assertTrue(result.isClean)
        assertEquals(Span(0, 0, 2, 2), result.items.spanOf("a"))
        assertEquals(Span(0, 2, 2, 1), result.items.spanOf("b"))
    }

    // --- normalize ------------------------------------------------------

    @Test
    fun `normalize reports below-minimum and clamps`() {
        val items = listOf(item("cal", 0, 0, 4, 1, limits = SizeLimits(2, 2, 4, 4)))
        val result = GridLayout.normalize(Phone, items)
        assertEquals(Span(0, 0, 4, 2), result.items.spanOf("cal"))
        assertEquals(listOf(LayoutIssue.BelowMinimum("cal", Span(0, 0, 4, 1), Span(0, 0, 4, 2))), result.issues)
    }

    @Test
    fun `normalize slides an item back into the grid and drops what cannot fit`() {
        val items = listOf(
            item("a", 3, 5, 2, 2),                                   // sticks out right and bottom -> slides to (2,4)
            item("b", 0, 0, 9, 1, limits = SizeLimits(9, 1, 9, 1)),  // 9 wide can never fit in 4 columns
            item("c", -1, 0, 1, 1),                                  // negative x -> slides to (0,0)
        )
        val result = GridLayout.normalize(Phone, items)
        assertEquals(Span(2, 4, 2, 2), result.items.spanOf("a"))
        assertEquals(Span(0, 0, 1, 1), result.items.spanOf("c"))
        assertEquals(listOf("a", "c"), result.items.map { it.id })
        assertEquals(listOf(LayoutIssue.OutOfBounds("b", Span(0, 0, 9, 1))), result.issues)
    }

    @Test
    fun `normalize re-places a later item that overlaps an earlier one`() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 1, 1, 2, 2))
        val result = GridLayout.normalize(Phone, items)
        assertEquals(Span(0, 0, 2, 2), result.items.spanOf("a"))
        assertEquals(Span(2, 0, 2, 2), result.items.spanOf("b"))
        assertEquals(listOf(LayoutIssue.Overlap("a", "b")), result.issues)
        assertEquals(emptyList<LayoutIssue>(), GridLayout.validate(Phone, result.items))
    }

    @Test
    fun `normalize drops an overlapping item when the grid is full`() {
        val items = listOf(item("a", 0, 0, 4, 6), item("b", 0, 0, 1, 1))
        val result = GridLayout.normalize(Phone, items)
        assertEquals(listOf("a"), result.items.map { it.id })
        assertEquals(listOf(LayoutIssue.Overlap("a", "b"), LayoutIssue.Overflow("b")), result.issues)
    }

    @Test
    fun `normalize handles the fold like move does`() {
        val items = listOf(item("a", 3, 0, 2, 1), item("b", 1, 1, 6, 1), favorites(0, 5, 8, 1))
        val result = GridLayout.normalize(Fold, items)
        assertEquals(Span(2, 0, 2, 1), result.items.spanOf("a"))
        assertEquals(Span(0, 5, 8, 1), result.items.spanOf("favorites"))
        assertEquals(listOf("a", "favorites"), result.items.map { it.id })
        assertEquals(listOf(LayoutIssue.CrossesFold("b")), result.issues)
    }

    @Test
    fun `normalize keeps the item order`() {
        val items = listOf(item("z", 2, 2), item("a", 0, 0), item("m", 1, 1))
        assertEquals(listOf("z", "a", "m"), GridLayout.normalize(Phone, items).items.map { it.id })
    }

    // --- clampToCover ---------------------------------------------------

    @Test
    fun `clampToCover keeps the left half and clips the favorites strip`() {
        val items = listOf(
            item("a", 0, 0, 2, 2),
            item("b", 4, 0, 2, 2),
            item("c", 3, 2, 1, 1),
            favorites(0, 5, 8, 1),
            item("d", 2, 3, 3, 1, mayCrossFold = true),
        )
        val cover = GridLayout.clampToCover(items, 4)
        assertEquals(listOf("a", "c", "favorites", "d"), cover.map { it.id })
        assertEquals(Span(0, 5, 4, 1), cover.spanOf("favorites"))
        assertEquals(Span(2, 3, 2, 1), cover.spanOf("d"))
        assertInside(GridSpec(4, 6), cover)
    }
}

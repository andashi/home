package de.mm20.launcher2.grid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun `spans overlap when they share a cell and not when they touch`() {
        val a = Span(0, 0, 2, 2)
        assertTrue(a.overlaps(Span(1, 1, 2, 2)))
        assertTrue(a.overlaps(a))
        assertFalse("touching on the right", a.overlaps(Span(2, 0, 1, 1)))
        assertFalse("touching below", a.overlaps(Span(0, 2, 1, 1)))
        assertFalse("far away", a.overlaps(Span(3, 3, 1, 1)))
        assertTrue("symmetric", Span(1, 1, 2, 2).overlaps(a))
    }

    @Test
    fun `fitsIn is exclusive at the far edges`() {
        assertTrue(Span(3, 5, 1, 1).fitsIn(4, 6))
        assertFalse(Span(3, 5, 2, 1).fitsIn(4, 6))
        assertFalse(Span(3, 5, 1, 2).fitsIn(4, 6))
        assertFalse(Span(-1, 0, 1, 1).fitsIn(4, 6))
        assertFalse(Span(0, -1, 1, 1).fitsIn(4, 6))
        assertFalse("empty spans never fit", Span(0, 0, 0, 1).fitsIn(4, 6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a grid needs at least one column`() {
        GridSpec(0, 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a grid needs at least one row`() {
        GridSpec(4, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the fold column must lie inside the grid`() {
        GridSpec(8, 6, foldColumn = 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `limits need a minimum of one cell`() {
        SizeLimits(0, 1, 1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `limits must not have a maximum below the minimum`() {
        SizeLimits(2, 2, 1, 2)
    }

    @Test
    fun `a clean result has no issues`() {
        assertTrue(LayoutResult(emptyList()).isClean)
        assertFalse(LayoutResult(emptyList(), listOf(LayoutIssue.Overflow("x"))).isClean)
    }
}

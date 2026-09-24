package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segments of one card (#91): the lens bends at the card's edges, never at a seam. */
class LensFrameTest {

    private val w = 300f
    private val h = 80f
    private val r = 28f
    private val strength = 10f
    private val band = 18f

    /** The vertical pull at (x, y) of the surface, through its frame. */
    private fun pullY(x: Float, y: Float, frame: LensFrame): Float {
        val (_, sy) = EdgeLens.sample(x, y + frame.offsetY, w, frame.height, r, strength, band)
        return sy - (y + frame.offsetY)
    }

    @Test
    fun `a closed surface is its own frame`() {
        assertEquals(LensFrame(0f, h), EdgeLens.frame(h, band, openTop = false, openBottom = false))
    }

    @Test
    fun `an open bottom edge is not bent, a closed one is`() {
        val open = EdgeLens.frame(h, band, openTop = false, openBottom = true)
        val closed = EdgeLens.frame(h, band, openTop = false, openBottom = false)

        assertEquals(0f, pullY(w / 2, h - 1f, open), 1e-3f)
        assertTrue("a closed edge pulls inward", pullY(w / 2, h - 1f, closed) < -1f)
    }

    @Test
    fun `an open top edge is not bent, and the closed bottom still is`() {
        val frame = EdgeLens.frame(h, band, openTop = true, openBottom = false)

        assertEquals(0f, pullY(w / 2, 1f, frame), 1e-3f)
        assertTrue(pullY(w / 2, h - 1f, frame) < -1f)
    }

    @Test
    fun `a middle segment is bent at neither end`() {
        val frame = EdgeLens.frame(h, band, openTop = true, openBottom = true)

        assertEquals(0f, pullY(w / 2, 1f, frame), 1e-3f)
        assertEquals(0f, pullY(w / 2, h - 1f, frame), 1e-3f)
        assertEquals(h + 2 * band, frame.height, 1e-3f)
    }
}

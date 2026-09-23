package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {

    private fun solid(w: Int, h: Int, color: Int) = Pixels(w, h, IntArray(w * h) { color })

    @Test
    fun `radius 0 returns the pixels unchanged`() {
        val input = Pixels(3, 1, intArrayOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), BoxBlur.blur(input, 0).argb.toList())
    }

    @Test
    fun `a uniform image stays uniform`() {
        val color = 0xFF336699.toInt()
        assertTrue(BoxBlur.blur(solid(9, 7, color), 3).argb.all { it == color })
    }

    @Test
    fun `a single bright pixel spreads symmetrically and keeps opaque alpha`() {
        val input = solid(9, 9, 0xFF000000.toInt())
        input.argb[4 * 9 + 4] = 0xFFFFFFFF.toInt()

        val out = BoxBlur.blur(input, 1)
        fun red(x: Int, y: Int) = out.argb[y * 9 + x] shr 16 and 0xFF

        assertTrue(red(4, 4) > red(3, 4))
        assertEquals(red(3, 4), red(5, 4))
        assertEquals(red(4, 3), red(4, 5))
        assertEquals(red(3, 4), red(4, 3))
        assertTrue(out.argb.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `the input is not modified`() {
        val input = Pixels(3, 1, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        BoxBlur.blur(input, 1)
        assertEquals(0xFFFFFFFF.toInt(), input.argb[1])
    }

    @Test
    fun `a radius larger than the image clamps at the edges`() {
        val out = BoxBlur.blur(Pixels(2, 1, intArrayOf(0xFF000000.toInt(), 0xFFFEFEFE.toInt())), 10)
        assertEquals(2, out.argb.size)
        assertTrue(out.argb.all { it ushr 24 == 0xFF })
    }
}

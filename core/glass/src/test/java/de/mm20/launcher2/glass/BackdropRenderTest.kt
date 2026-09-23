package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A synthetic wallpaper with a known pattern stands in for the photo: the
 * assertion is where each pixel of the backdrop comes from, which a photo
 * cannot answer. The Mauritius wallpaper is for the goldens (#77).
 */
class BackdropRenderTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

    /**
     * 400 x 200. Columns 0..149 green, 150..199 red, 200..249 blue,
     * 250..399 green. A 160 x 320 window covers the middle 100 columns
     * (150..249), so the green must never show.
     */
    private val image = Pixels(400, 200, IntArray(400 * 200) { i ->
        when (i % 400) {
            in 150 until 200 -> red
            in 200 until 250 -> blue
            else -> green
        }
    })

    private fun Pixels.at(x: Int, y: Int) = argb[y * width + x]

    @Test
    fun `the backdrop is the window's crop at an eighth`() {
        val backdrop = BackdropGeometry.render(image, 160, 320, blurPx = 0)

        assertEquals(20, backdrop.width)
        assertEquals(40, backdrop.height)
        assertTrue(backdrop.argb.none { it == green })
    }

    @Test
    fun `a surface's rectangle gets the matching sub-region`() {
        val backdrop = BackdropGeometry.render(image, 160, 320, blurPx = 0)

        val left = BackdropGeometry.region(20, 40, 160, 320, 0f, 0f, 80f, 320f)
        val right = BackdropGeometry.region(20, 40, 160, 320, 80f, 0f, 160f, 320f)
        for (y in left.top until left.bottom) for (x in left.left until left.right) {
            assertEquals("($x,$y)", red, backdrop.at(x, y))
        }
        for (y in right.top until right.bottom) for (x in right.left until right.right) {
            assertEquals("($x,$y)", blue, backdrop.at(x, y))
        }
    }

    @Test
    fun `blur mixes across the edge and leaves the far side alone`() {
        val backdrop = BackdropGeometry.render(image, 160, 320, blurPx = 2)

        val edgeLeft = backdrop.at(9, 20)
        assertTrue("red and blue mix at the edge", (edgeLeft and 0xFF) > 0 && (edgeLeft shr 16 and 0xFF) > 0)
        assertEquals(red, backdrop.at(0, 20))
        assertEquals(blue, backdrop.at(19, 20))
    }

    @Test
    fun `a subsampled decode gives the same backdrop`() {
        val half = Pixels(200, 100, IntArray(200 * 100) { i -> image.argb[(i / 200) * 2 * 400 + (i % 200) * 2] })

        val full = BackdropGeometry.render(image, 160, 320, blurPx = 0)
        val sub = BackdropGeometry.render(half, 160, 320, blurPx = 0)

        assertEquals(full.argb.toList(), sub.argb.toList())
    }
}

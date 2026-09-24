package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The lens changes nothing deeper than its band (#91), so only the ring
 * along the outline needs the shader. These tests hold the area to that:
 * inside it the lens is the identity, just outside it the lens pulls.
 */
class LensIdentityAreaTest {

    private val strength = 10f
    private val band = 18f

    private fun pull(x: Float, y: Float, w: Float, h: Float, r: Float): Float {
        val (sx, sy) = EdgeLens.sample(x, y, w, h, r, strength, band)
        return hypot(sx - x, sy - y)
    }

    private fun inside(a: LensIdentityArea, x: Float, y: Float): Boolean {
        if (x < a.left || x > a.right || y < a.top || y > a.bottom) return false
        val cx = x.coerceIn(a.left + a.radius, a.right - a.radius)
        val cy = y.coerceIn(a.top + a.radius, a.bottom - a.radius)
        return hypot(x - cx, y - cy) <= a.radius
    }

    @Test
    fun `a card's area is inset by the band, its corners by what is left of the radius`() {
        assertEquals(LensIdentityArea(18f, 18f, 382f, 82f, 10f), EdgeLens.identityArea(400f, 100f, 28f, band))
    }

    @Test
    fun `a radius smaller than the band leaves square corners`() {
        assertEquals(0f, EdgeLens.identityArea(400f, 100f, 8f, band)!!.radius)
    }

    @Test
    fun `a surface thinner than twice the band has no area`() {
        assertNull(EdgeLens.identityArea(400f, 30f, 15f, band))
    }

    @Test
    fun `inside the area the lens is the identity, everywhere`() {
        for ((w, h, r) in listOf(Triple(400f, 100f, 28f), Triple(300f, 60f, 30f), Triple(200f, 200f, 8f))) {
            val a = assertNotNull(EdgeLens.identityArea(w, h, r, band)).let { EdgeLens.identityArea(w, h, r, band)!! }
            var y = 0f
            while (y <= h) {
                var x = 0f
                while (x <= w) {
                    if (inside(a, x, y)) assertEquals("pull at ($x, $y) of ${w}x$h r$r", 0f, pull(x, y, w, h, r), 1e-4f)
                    x += 1f
                }
                y += 1f
            }
        }
    }

    @Test
    fun `just outside the area the lens pulls, so the area is not too small either`() {
        val (w, h, r) = Triple(400f, 100f, 28f)
        val a = EdgeLens.identityArea(w, h, r, band)!!
        // Mid-edges and the corner diagonal, a pixel outside the area.
        assertTrue(pull(w / 2, a.top - 1f, w, h, r) > 0f)
        assertTrue(pull(a.left - 1f, h / 2, w, h, r) > 0f)
        val corner = a.radius / kotlin.math.sqrt(2f)
        val cx = a.left + a.radius - corner - 1f
        val cy = a.top + a.radius - corner - 1f
        assertTrue(pull(cx, cy, w, h, r) > 0f)
    }
}

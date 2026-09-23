package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class GlassLookTest {

    private val w = 400f
    private val h = 200f
    private val r = 56f
    private val strength = 26f
    private val band = 47f

    private fun sample(x: Float, y: Float) = EdgeLens.sample(x, y, w, h, r, strength, band)

    private fun displacement(x: Float, y: Float): Float {
        val (sx, sy) = sample(x, y)
        return hypot(sx - x, sy - y)
    }

    @Test
    fun `the interior is not bent`() {
        assertEquals(200f to 100f, sample(200f, 100f))
        assertEquals(120f to 60f, sample(120f, 60f)) // 60 from the top, deeper than the band
    }

    @Test
    fun `at the edge the backdrop is pulled inwards along the normal`() {
        val (sx, sy) = sample(200f, 1f) // top edge, middle
        assertEquals(200f, sx, 1e-3f)
        assertTrue("pulled down, towards the inside: $sy", sy > 1f)
        val (lx, ly) = sample(1f, 100f) // left edge, middle
        assertTrue("pulled right: $lx", lx > 1f)
        assertEquals(100f, ly, 1e-3f)
    }

    @Test
    fun `the pull grows towards the edge and never exceeds the strength`() {
        val along = (0..60).map { displacement(200f, it.toFloat()) }
        assertTrue(along.zipWithNext().all { (outer, inner) -> outer >= inner - 1e-4f })
        assertTrue(along.all { it <= strength + 1e-3f })
        assertTrue(along.first() > strength * 0.8f)
    }

    @Test
    fun `the lens is symmetric`() {
        val (ax, ay) = sample(10f, 20f)
        val (bx, by) = sample(w - 10f, h - 20f)
        assertEquals(ax, w - bx, 1e-3f)
        assertEquals(ay, h - by, 1e-3f)
    }

    @Test
    fun `it never samples outside the surface`() {
        for (x in 0..400 step 8) for (y in 0..200 step 8) {
            val (sx, sy) = sample(x.toFloat(), y.toFloat())
            assertTrue("($x,$y) -> ($sx,$sy)", sx in 0f..w && sy in 0f..h)
        }
    }

    @Test
    fun `the signed distance is negative inside, zero on the outline, positive outside`() {
        assertTrue(EdgeLens.signedDistance(200f, 100f, w, h, r) < 0f)
        assertEquals(0f, EdgeLens.signedDistance(200f, 0f, w, h, r), 1e-3f)
        assertTrue(EdgeLens.signedDistance(-5f, 100f, w, h, r) > 0f)
        // The corner is rounded: the rectangle's own corner point lies outside.
        assertTrue(EdgeLens.signedDistance(0f, 0f, w, h, r) > 0f)
    }

    @Test
    fun `the rim is brightest at the top-left, weaker at the bottom-right, faint in between`() {
        val stops = GlassLook.RimStops
        assertEquals(0f, stops.first().first)
        assertEquals(1f, stops.last().first)
        val topLeft = stops.first().second
        val bottomRight = stops.last().second
        val middle = stops.filter { it.first in 0.3f..0.7f }.maxOf { it.second }
        assertTrue(topLeft > bottomRight)
        assertTrue(bottomRight > middle)
        assertTrue(stops.zipWithNext().all { (a, b) -> a.first < b.first })
        assertTrue(stops.all { it.second in 0f..1f })
    }

    @Test
    fun `a zero-strength lens bends nothing`() {
        val (sx, sy) = EdgeLens.sample(1f, 1f, w, h, r, 0f, band)
        assertTrue(abs(sx - 1f) < 1e-4f && abs(sy - 1f) < 1e-4f)
    }
}

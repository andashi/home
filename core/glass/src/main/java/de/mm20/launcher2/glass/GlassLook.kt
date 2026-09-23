package de.mm20.launcher2.glass

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What makes the glass liquid rather than frosted (#82): the directional rim
 * and the edge lens. No config key for either - one look (ADR 0004) - so the
 * constants live here, next to the contrast factors, and the goldens pin them.
 */
object GlassLook {
    /** Width of the rim, dp. */
    const val RimWidthDp = 1.25f

    /**
     * The rim's alpha by angle around the surface's centre, as a sweep
     * gradient runs: 0 is 3 o'clock, clockwise, 1 closes the circle. Light
     * falls from the top-left (225 degrees), the bottom-right (45 degrees)
     * catches a weaker reflection, every side stays faint. Angles, not a
     * diagonal, so a wide pill is lit like a square card (review on #83).
     */
    val RimStops: List<Pair<Float, Float>> = listOf(
        0f to 0.10f, // right
        0.125f to 0.32f, // bottom-right
        0.25f to 0.08f, // bottom
        0.5f to 0.10f, // left
        0.625f to 0.55f, // top-left
        0.75f to 0.12f, // top
        1f to 0.10f, // right again
    )

    /** How far the lens pulls the backdrop at the very edge, dp. */
    const val LensStrengthDp = 10f

    /** How wide the band along the edge is in which the lens acts, dp. */
    const val LensBandDp = 18f
}

/**
 * The edge lens as a reference function (#82): the AGSL shader in the UI
 * computes the same thing per pixel. For a point in a rounded rectangle of
 * [width] x [height] and corner [radius] it returns the point the backdrop is
 * sampled at: unchanged in the interior, pulled inwards along the edge's
 * normal within [band] of the edge, by at most [strength], and never outside
 * the rectangle.
 */
object EdgeLens {
    fun sample(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        strength: Float,
        band: Float,
    ): Pair<Float, Float> {
        if (strength <= 0f || band <= 0f) return x to y
        val distance = signedDistance(x, y, width, height, radius)
        // 0 deeper than the band, 1 on the outline; squared, so the pull
        // starts gently and is strongest right at the edge.
        val t = (1f + distance / band).coerceIn(0f, 1f)
        val pull = t * t * strength
        if (pull == 0f) return x to y
        // The outward normal: the distance field's gradient, by central
        // differences - the same the shader computes.
        val gx = signedDistance(x + Epsilon, y, width, height, radius) -
                signedDistance(x - Epsilon, y, width, height, radius)
        val gy = signedDistance(x, y + Epsilon, width, height, radius) -
                signedDistance(x, y - Epsilon, width, height, radius)
        val length = hypot(gx, gy)
        if (length == 0f) return x to y
        return (x - gx / length * pull).coerceIn(0f, width) to (y - gy / length * pull).coerceIn(0f, height)
    }

    /** Signed distance to the rounded rectangle's outline, negative inside. */
    fun signedDistance(x: Float, y: Float, width: Float, height: Float, radius: Float): Float {
        val r = min(radius, min(width, height) / 2f)
        val qx = abs(x - width / 2f) - width / 2f + r
        val qy = abs(y - height / 2f) - height / 2f + r
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    private const val Epsilon = 0.5f
}

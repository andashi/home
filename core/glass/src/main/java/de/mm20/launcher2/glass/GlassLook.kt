package de.mm20.launcher2.glass

/**
 * What makes the glass liquid rather than frosted (#82): the directional rim
 * and the edge lens. No config key for either - one look (ADR 0004) - so the
 * constants live here, next to the contrast factors, and the goldens pin them.
 */
object GlassLook {
    /** Width of the rim, dp. */
    const val RimWidthDp = 1.25f

    /**
     * The rim's alpha along the diagonal from the top-left corner (0) to the
     * bottom-right one (1): light falls from the top-left, so that corner is
     * brightest, the far corner catches a weaker reflection, the sides little.
     */
    val RimStops: List<Pair<Float, Float>> = TODO()

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
    ): Pair<Float, Float> = TODO()

    /** Signed distance to the rounded rectangle's outline, negative inside. */
    fun signedDistance(x: Float, y: Float, width: Float, height: Float, radius: Float): Float = TODO()
}

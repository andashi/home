package de.mm20.launcher2.glass

/** `appearance.glass.contrast`, mirrored here because this module has no Android dependency. */
enum class Contrast { Low, Medium, High }

/** The glass values as configured (`appearance.glass`), before contrast is applied. */
data class GlassInputs(
    val blurDp: Float,
    val tint: Float,
    val radiusDp: Float,
    val contrast: Contrast,
)

/** What a surface draws with: [GlassInputs] with the contrast scale applied. */
data class ResolvedGlass(
    val blurDp: Float,
    val tint: Float,
    val radiusDp: Float,
    /** Alpha of the black scrim behind text and glyphs; 0 means none. */
    val scrimAlpha: Float,
)

/**
 * The one place the contrast factors live (#73, ADR 0004): `contrast` is a
 * scale, not a theme. The goldens pin these numbers.
 */
object GlassStyle {
    private class Scale(val blur: Float, val tint: Float, val scrim: Float)

    private val scales = mapOf(
        Contrast.Low to Scale(blur = 0.75f, tint = -0.10f, scrim = 0f),
        Contrast.Medium to Scale(blur = 1f, tint = 0f, scrim = 0f),
        Contrast.High to Scale(blur = 1.25f, tint = 0.15f, scrim = 0.12f),
    )

    fun resolve(inputs: GlassInputs): ResolvedGlass {
        val scale = scales.getValue(inputs.contrast)
        return ResolvedGlass(
            blurDp = inputs.blurDp * scale.blur,
            tint = (inputs.tint + scale.tint).coerceIn(0f, 1f),
            radiusDp = inputs.radiusDp,
            scrimAlpha = scale.scrim,
        )
    }
}

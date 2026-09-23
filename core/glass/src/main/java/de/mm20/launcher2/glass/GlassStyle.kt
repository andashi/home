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
    fun resolve(inputs: GlassInputs): ResolvedGlass = TODO()
}

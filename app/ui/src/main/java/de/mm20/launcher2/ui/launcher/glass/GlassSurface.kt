package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.ResolvedGlass

/** The glass values surfaces draw with, contrast applied; the contract's defaults until provided. */
val LocalGlassStyle = staticCompositionLocalOf {
    GlassStyle.resolve(GlassInputs(24f, 0.35f, 28f, Contrast.Medium))
}

/** What a glass surface drew, for tests: the tint alpha, the corner radius, the scrim, pill or card. */
data class GlassSurfaceInfo(val tint: Float, val radiusDp: Float, val scrimAlpha: Float, val pill: Boolean)

val GlassSurfaceKey = SemanticsPropertyKey<GlassSurfaceInfo>("GlassSurface")

/**
 * A glass surface (ADR 0004, #75): the backdrop region under it, clipped to
 * the glass radius (or a pill), a tint of the zone's surface color, the
 * contrast scrim, a 1 dp inner highlight and a top-edge specular. Nothing
 * else - no refraction, no motion.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    pill: Boolean = false,
    content: @Composable () -> Unit,
) {
    TODO()
}

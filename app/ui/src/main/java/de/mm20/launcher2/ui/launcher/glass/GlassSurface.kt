package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.ResolvedGlass

/** The glass values surfaces draw with, contrast applied; the contract's defaults until provided. */
val LocalGlassStyle = staticCompositionLocalOf { DefaultStyle }

/** `appearance.glass` at its defaults (ADR 0002, "Glass"). */
internal val DefaultStyle = GlassStyle.resolve(GlassInputs(24f, 0.35f, 28f, Contrast.Medium))

/** What a glass surface drew, for tests: the tint alpha, the corner radius, the scrim, pill or card. */
data class GlassSurfaceInfo(
    val tint: Float,
    val radiusDp: Float,
    val scrimAlpha: Float,
    val pill: Boolean,
    /** The edge lens bends the backdrop (#82). */
    val lens: Boolean = false,
    /** The directional rim is drawn (#82). */
    val rim: Boolean = false,
)

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
    val style = LocalGlassStyle.current
    val shape = if (pill) RoundedCornerShape(percent = 50) else RoundedCornerShape(style.radiusDp.dp)
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = style.tint)
    val info = GlassSurfaceInfo(style.tint, style.radiusDp, style.scrimAlpha, pill)
    Box(
        modifier = modifier
            .semantics { this[GlassSurfaceKey] = info }
            .clip(shape)
            // Drawn first: the blurred wallpaper under this surface.
            .glassBackdrop()
            .drawBehind {
                drawRect(tint)
                if (style.scrimAlpha > 0f) drawRect(Color.Black.copy(alpha = style.scrimAlpha))
                // The top-edge specular: a short fade from white to nothing.
                val height = minOf(size.height, SpecularHeight.toPx())
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = SpecularAlpha),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = height,
                    ),
                    size = Size(size.width, height),
                )
            }
            // The 1 dp inner highlight, following the shape.
            .border(1.dp, Color.White.copy(alpha = HighlightAlpha), shape),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

private val SpecularHeight = 24.dp
private const val SpecularAlpha = 0.18f
private const val HighlightAlpha = 0.16f

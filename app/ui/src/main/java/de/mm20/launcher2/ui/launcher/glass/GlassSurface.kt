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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import de.mm20.launcher2.config.GlassDefaults
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassLook
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.ResolvedGlass

/** The glass values surfaces draw with, contrast applied; the contract's defaults until provided. */
val LocalGlassStyle = staticCompositionLocalOf { DefaultStyle }

/** `appearance.glass` at its defaults (ADR 0002, "Glass"). */
internal val DefaultStyle = GlassStyle.resolve(
    GlassInputs(GlassDefaults.Blur, GlassDefaults.Tint, GlassDefaults.Radius, Contrast.Medium)
)

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
    /** The edges where this segment meets the next one of the same card (#91). */
    val openEdges: Set<GlassEdge> = emptySet(),
)

/**
 * An edge where a surface continues into the next segment of the same card
 * (#91): a lazily laid out result list is one card made of row slices, and
 * the slices must not show corners, rim or specular where they meet.
 */
enum class GlassEdge { Top, Bottom }

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
    /** A shape of its own (the icon chip's squircle); null is the glass radius or the pill. */
    shape: Shape? = null,
    /** Added to the tint: an icon chip is a little stronger than a card (#76). */
    tintBoost: Float = 0f,
    /** Edges that continue into the next segment of the same card (#91). */
    openEdges: Set<GlassEdge> = emptySet(),
    content: @Composable () -> Unit,
) {
    val style = LocalGlassStyle.current
    val outline = shape ?: if (pill) RoundedCornerShape(percent = 50) else RoundedCornerShape(style.radiusDp.dp)
    val tintAlpha = (style.tint + tintBoost).coerceIn(0f, 1f)
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = tintAlpha)
    val info = GlassSurfaceInfo(tintAlpha, style.radiusDp, style.scrimAlpha, pill, lens = true, rim = true)
    Box(
        modifier = modifier
            .semantics { this[GlassSurfaceKey] = info }
            .clip(outline)
            // Drawn first: the blurred wallpaper under this surface.
            // The lens follows a rounded rectangle; a custom shape (the icon
            // chip's squircle) is lensed as a pill - at icon size the two
            // outlines are a pixel or two apart.
            .glassBackdrop(lens = true, cornerRadius = style.radiusDp.dp, pill = pill || shape != null)
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
            // The rim: light from the top-left, a weaker reflection at the
            // bottom-right, faint along the sides (#82).
            .glassRim(outline),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

private val SpecularHeight = 24.dp
private const val SpecularAlpha = 0.18f
internal val RimBrush = Brush.sweepGradient(
    colorStops = GlassLook.RimStops.map { (at, alpha) -> at to Color.White.copy(alpha = alpha) }.toTypedArray(),
)

/** The directional rim on its own, for tests: the same stroke every surface draws. */
internal fun Modifier.glassRim(shape: androidx.compose.ui.graphics.Shape): Modifier =
    border(GlassLook.RimWidthDp.dp, RimBrush, shape)

/** The outline of a surface: the pill, the given [shape] or the glass radius, square on [openEdges]. */
internal fun glassOutline(radiusDp: Float, pill: Boolean, shape: Shape?, openEdges: Set<GlassEdge>): Shape =
    shape ?: if (pill) RoundedCornerShape(percent = 50) else RoundedCornerShape(radiusDp.dp)

/** The rim stroke into [this] scope, left out along [openEdges] (tests draw it into a bitmap). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassRim(
    shape: Shape,
    openEdges: Set<GlassEdge>,
) {
    drawOutline(
        shape.createOutline(size, layoutDirection, this),
        RimBrush,
        style = androidx.compose.ui.graphics.drawscope.Stroke(GlassLook.RimWidthDp.dp.toPx()),
    )
}

package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.config.GlassDefaults
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassLook
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
    /** The lens's corner radius in dp; null lenses the surface as a pill (#91). */
    val lensRadiusDp: Float? = null,
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
    /**
     * The lens's corner radius for a custom [shape] that is not the icon
     * chip's squircle: 0 for a rectangle, the shape's radius otherwise.
     * Null lenses [shape] as a pill, which is right only for the squircle.
     */
    lensRadius: Dp? = null,
    content: @Composable () -> Unit,
) {
    val style = LocalGlassStyle.current
    val outline = glassOutline(style.radiusDp, pill, shape, openEdges)
    val tintAlpha = (style.tint + tintBoost).coerceIn(0f, 1f)
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = tintAlpha)
    // The lens follows the real outline: the glass radius, the pill, or a
    // custom shape's own radius; only the icon chip's squircle - and a
    // custom shape that names none - is lensed as a pill, which it is within
    // a pixel or two at icon size.
    val lensAsPill = pill || (shape != null && lensRadius == null)
    val lensCorner = when {
        lensAsPill -> null
        shape != null -> lensRadius
        else -> style.radiusDp.dp
    }
    val info = GlassSurfaceInfo(
        tintAlpha, style.radiusDp, style.scrimAlpha, pill, lens = true, rim = true, openEdges = openEdges,
        lensRadiusDp = lensCorner?.value,
    )
    Box(
        modifier = modifier
            .semantics { this[GlassSurfaceKey] = info }
            .clip(outline)
            // Drawn first: the blurred wallpaper under this surface.
            .glassBackdrop(
                lens = true,
                cornerRadius = lensCorner ?: 0.dp,
                pill = lensAsPill,
                openEdges = openEdges,
            )
            .drawBehind {
                drawRect(tint)
                if (style.scrimAlpha > 0f) drawRect(Color.Black.copy(alpha = style.scrimAlpha))
                // The top-edge specular: a short fade from white to nothing,
                // only on the card's real top edge.
                if (GlassEdge.Top in openEdges) return@drawBehind
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
            // bottom-right, faint along the sides (#82); not along a seam.
            // A closed card or pill keeps Modifier.border (#82); a segment
            // strokes around its seams (#91), a squircle chip is stroked (#122).
            .then(
                when (glassRimKind(outline, openEdges)) {
                    GlassRimKind.Border -> Modifier.glassRim(outline)
                    // Built once per size: a generic outline is a new Path
                    // per call, and a new Path per frame is a new mask.
                    // Remembered, so a segment's freshly built edge set does
                    // not throw the cache away on every recomposition.
                    GlassRimKind.Stroke -> remember(outline, openEdges) {
                        Modifier.drawWithCache {
                            val rim = glassRimStroke(outline, openEdges, size, layoutDirection, this)
                            onDrawWithContent {
                                drawContent()
                                drawGlassRim(rim)
                            }
                        }
                    }
                }
            ),
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

/** How a surface draws its rim (#122). */
internal enum class GlassRimKind {
    /** Modifier.border: a rounded rectangle or a pill, drawn directly. */
    Border,

    /** A stroke of the outline, clipped to the surface ([drawGlassRim]). */
    Stroke,
}

/**
 * The rim for [shape]. A segment strokes around its seams (#91). A closed
 * card or pill keeps Modifier.border, which draws a corner-based shape
 * directly. Any other shape - the icon chip's squircle - is stroked too:
 * Modifier.border builds a generic shape's rim with Path.op and rasterizes
 * it on the CPU, again on every size change, and on unfold that was most of
 * the launcher's first frame (#122).
 */
internal fun glassRimKind(shape: Shape, openEdges: Set<GlassEdge>): GlassRimKind = when {
    openEdges.isNotEmpty() -> GlassRimKind.Stroke
    shape is CornerBasedShape -> GlassRimKind.Border
    else -> GlassRimKind.Stroke
}

/** The directional rim on its own, for tests: the same stroke every surface draws. */
internal fun Modifier.glassRim(shape: androidx.compose.ui.graphics.Shape): Modifier =
    border(GlassLook.RimWidthDp.dp, RimBrush, shape)

/** The outline of a surface: the pill, the given [shape] or the glass radius, square on [openEdges]. */
internal fun glassOutline(radiusDp: Float, pill: Boolean, shape: Shape?, openEdges: Set<GlassEdge>): Shape {
    if (shape != null) return shape
    if (pill) return RoundedCornerShape(percent = 50)
    val top = if (GlassEdge.Top in openEdges) 0.dp else radiusDp.dp
    val bottom = if (GlassEdge.Bottom in openEdges) 0.dp else radiusDp.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** A rim stroke laid out for one size: [glassRimStroke] builds it, [drawGlassRim] draws it. */
internal class GlassRimStroke(
    val outline: Outline,
    val stroke: Stroke,
    val above: Float,
)

/**
 * The rim of [shape] at [size]. On an open edge the outline is stroked as if
 * it went on past the edge and then clipped to the surface: the sides run to
 * the seam without a gap, and the stroke along the seam falls outside.
 */
internal fun glassRimStroke(
    shape: Shape,
    openEdges: Set<GlassEdge>,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): GlassRimStroke {
    val stroke = with(density) { GlassLook.RimWidthDp.dp.toPx() }
    val above = if (GlassEdge.Top in openEdges) stroke * 2 else 0f
    val below = if (GlassEdge.Bottom in openEdges) stroke * 2 else 0f
    // Inset by half the stroke, as Modifier.border does, so the whole rim
    // lies inside the surface's clip.
    val extended = Size(size.width - stroke, size.height + above + below - stroke)
    return GlassRimStroke(shape.createOutline(extended, layoutDirection, density), Stroke(stroke), above)
}

/** Draws [rim] into this scope, clipped to it (tests draw it into a bitmap). */
internal fun DrawScope.drawGlassRim(rim: GlassRimStroke) {
    val inset = rim.stroke.width / 2f
    clipRect {
        translate(left = inset, top = inset - rim.above) {
            drawOutline(rim.outline, RimBrush, style = rim.stroke)
        }
    }
}

/**
 * The open edges of segment [index] of [count] in one card (#91). With
 * [reverse] the list is laid out bottom up (search bar at the bottom), so the
 * first segment is the card's bottom.
 */
fun segmentEdges(index: Int, count: Int, reverse: Boolean = false): Set<GlassEdge> {
    val first = index == 0
    val last = index == count - 1
    val top = if (reverse) last else first
    val bottom = if (reverse) first else last
    return buildSet {
        if (!top) add(GlassEdge.Top)
        if (!bottom) add(GlassEdge.Bottom)
    }
}

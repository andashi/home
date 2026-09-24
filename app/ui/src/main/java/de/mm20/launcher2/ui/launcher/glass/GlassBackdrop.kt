package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.runtime.getValue
import de.mm20.launcher2.glass.LensFrame
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import de.mm20.launcher2.glass.LensIdentityArea
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import de.mm20.launcher2.glass.EdgeLens
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.mm20.launcher2.glass.BackdropCache
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.BackdropPipeline
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.GlassLook
import de.mm20.launcher2.glass.GlassStyle
import de.mm20.launcher2.glass.ResolvedGlass
import de.mm20.launcher2.glass.PixelRect
import de.mm20.launcher2.glass.RenderedBackdrop
import de.mm20.launcher2.glass.WindowInputs
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The blurred wallpaper under the home screen's glass surfaces (#74, ADR
 * 0004): made once per wallpaper, glass setting and window, cached, and read
 * by every surface. Lives for the process (a Koin single), so an activity
 * recreation does not re-blur.
 */
class GlassBackdropController(
    private val source: GlassBackdropSource,
    glass: Flow<GlassInputs>,
    scope: CoroutineScope,
    wallpaperBlur: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    searchWallpaperBlur: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    render: suspend (BackdropImage, BackdropKey) -> ImageBitmap?,
) {
    /** `appearance.glass.wallpaperBlur` (#82). */
    val wallpaperBlur: StateFlow<Boolean> = wallpaperBlur.stateIn(scope, SharingStarted.Eagerly, true)

    /** `appearance.glass.searchWallpaperBlur` (#91). */
    val searchWallpaperBlur: StateFlow<Boolean> = searchWallpaperBlur.stateIn(scope, SharingStarted.Eagerly, true)

    private val window = MutableStateFlow<WindowInputs?>(null)

    /** The glass values with contrast applied, for every surface (#75). */
    val style: StateFlow<ResolvedGlass> = glass
        .map { GlassStyle.resolve(it) }
        .stateIn(scope, SharingStarted.Eagerly, DefaultStyle)

    val backdrop: StateFlow<RenderedBackdrop<ImageBitmap>?> =
        BackdropPipeline(source.image, glass, window, BackdropCache(), render)
            .backdrop
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun setWindow(widthPx: Int, heightPx: Int, density: Float) {
        window.value = WindowInputs(widthPx, heightPx, density)
    }

    /**
     * Asks the source to re-check the wallpaper. Runs from the resume hook,
     * where an exception would take the launcher down; a failed check keeps
     * the backdrop as it was and is logged.
     */
    suspend fun refresh() {
        try {
            source.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GlassBackdrop", "refresh failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * True where content sits over the wallpaper or other glass - the search
 * screen, its popup and its glass sheet (#91). Components shared with the
 * settings screens and the opaque Material sheets (a banner, a tag chip) draw
 * glass only here; inside an opaque sheet a glass surface would be a hole
 * down to the wallpaper.
 */
val LocalOnGlass = staticCompositionLocalOf { false }

/** The backdrop surfaces draw from; null without a managed home wallpaper. */
val LocalGlassBackdrop = staticCompositionLocalOf<RenderedBackdrop<ImageBitmap>?> { null }

/** The backdrop region a surface drew, in backdrop pixels (tests read it). */
val GlassBackdropRegion = SemanticsPropertyKey<PixelRect>("GlassBackdropRegion")

/** The blur, in backdrop pixels, of the backdrop a surface drew (tests read it, #77). */
val GlassBackdropBlurPx = SemanticsPropertyKey<Int>("GlassBackdropBlurPx")

/**
 * Provides [LocalGlassBackdrop] for [content]: tells the controller the
 * window size and asks the source to re-check the wallpaper whenever the
 * launcher comes to the front, which is when a wallpaper changed elsewhere
 * would first be seen.
 */
@Composable
fun ProvideGlassBackdrop(controller: GlassBackdropController, content: @Composable () -> Unit) {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    LaunchedEffect(controller, size, density) {
        if (size.width > 0 && size.height > 0) controller.setWindow(size.width, size.height, density)
    }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(controller) {
        scope.launch { controller.refresh() }
        onPauseOrDispose { }
    }
    val backdrop by controller.backdrop.collectAsState()
    val style by controller.style.collectAsState()
    val wallpaperBlur by controller.wallpaperBlur.collectAsState()
    val searchWallpaperBlur by controller.searchWallpaperBlur.collectAsState()
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalGlassStyle provides style,
        LocalGlassWallpaperBlur provides wallpaperBlur,
        LocalGlassSearchWallpaperBlur provides searchWallpaperBlur,
        content = content,
    )
}

/**
 * Draws the part of the backdrop that lies under this node, behind its
 * content, stretched from backdrop pixels to the node's size. With [lens],
 * the region is drawn through the edge lens (#82) for a rounded rectangle of
 * [cornerRadius], or a pill; where AGSL is not available (the JVM tests) the
 * plain region is drawn. Without a backdrop it is a no-op, so a surface
 * degrades to tint only.
 */
fun Modifier.glassBackdrop(
    lens: Boolean = false,
    cornerRadius: Dp = 0.dp,
    pill: Boolean = false,
    /** Edges that continue into the next segment (#91): the lens does not bend there. */
    openEdges: Set<GlassEdge> = emptySet(),
): Modifier = composed {
    // Only what changes with the wallpaper or the window is read here, so
    // this recomposes once per backdrop - not on every frame a surface moves
    // (#91: during the search transition every surface moves every frame).
    val backdrop = LocalGlassBackdrop.current
    val view = LocalView.current
    this then GlassBackdropElement(backdrop, view, lens, cornerRadius, pill, openEdges)
}

private data class GlassBackdropElement(
    val backdrop: RenderedBackdrop<ImageBitmap>?,
    val view: android.view.View,
    val lens: Boolean,
    val cornerRadius: Dp,
    val pill: Boolean,
    val openEdges: Set<GlassEdge>,
) : ModifierNodeElement<GlassBackdropNode>() {
    override fun create() = GlassBackdropNode(backdrop, view, lens, cornerRadius, pill, openEdges)

    override fun update(node: GlassBackdropNode) =
        node.update(backdrop, view, lens, cornerRadius, pill, openEdges)

    override fun InspectorInfo.inspectableProperties() {
        name = "glassBackdrop"
    }
}

/**
 * The backdrop region under a surface. A position change - every frame while
 * the surface animates - recomputes the region and invalidates the draw and
 * the semantics, nothing else: no recomposition, and the ring path is kept
 * until the surface's size changes (#91).
 */
private class GlassBackdropNode(
    private var backdrop: RenderedBackdrop<ImageBitmap>?,
    private var view: android.view.View,
    private var lens: Boolean,
    private var cornerRadius: Dp,
    private var pill: Boolean,
    private var openEdges: Set<GlassEdge>,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, SemanticsModifierNode {

    private var bounds: Rect? = null
    private var region: PixelRect? = null
    private var lensShader: RuntimeShader? = null
    private var bitmapShader: BitmapShader? = null
    private var shaderBitmap: ImageBitmap? = null
    private var ringKey: List<Float>? = null
    private var ring: Path? = null
    private var plain: Path? = null

    override val shouldAutoInvalidate = false

    override fun onAttach() {
        if (lens) lensShader = acquireLens()
    }

    override fun onDetach() {
        releaseLens()
    }

    fun update(
        backdrop: RenderedBackdrop<ImageBitmap>?,
        view: android.view.View,
        lens: Boolean,
        cornerRadius: Dp,
        pill: Boolean,
        openEdges: Set<GlassEdge>,
    ) {
        if (lens != this.lens) {
            this.lens = lens
            if (lens) lensShader = acquireLens() else releaseLens()
        }
        if (cornerRadius != this.cornerRadius || pill != this.pill || openEdges != this.openEdges) {
            this.cornerRadius = cornerRadius
            this.pill = pill
            this.openEdges = openEdges
            ringKey = null
        }
        this.view = view
        if (backdrop !== this.backdrop) {
            this.backdrop = backdrop
            updateRegion()
        }
        invalidateDraw()
        invalidateSemantics()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val box = coordinates.boundsInLauncherWindow(view)
        if (box == bounds) return
        bounds = box
        if (updateRegion()) {
            invalidateDraw()
            invalidateSemantics()
        }
    }

    /** Maps the bounds into the backdrop; true when the region changed. */
    private fun updateRegion(): Boolean {
        val b = backdrop
        val box = bounds
        val next = if (b == null || box == null) null else BackdropGeometry.region(
            b.bitmap.width, b.bitmap.height,
            b.key.windowWidthPx, b.key.windowHeightPx,
            box.left, box.top, box.right, box.bottom,
        )
        if (next == region) return false
        region = next
        return true
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        val b = backdrop ?: return
        val r = region ?: return
        this[GlassBackdropRegion] = r
        this[GlassBackdropBlurPx] = b.key.blurPx
    }

    override fun ContentDrawScope.draw() {
        val b = backdrop
        val r = region
        if (b != null && r != null) drawBackdrop(b.bitmap, r)
        drawContent()
    }

    private fun ContentDrawScope.drawBackdrop(bitmap: ImageBitmap, region: PixelRect) {
        fun drawPlain() = drawImage(
            image = bitmap,
            srcOffset = IntOffset(region.left, region.top),
            srcSize = IntSize(region.width, region.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        // RuntimeShader exists only on the hardware renderer; a software
        // canvas (drawToBitmap, some screenshot paths) throws on it, so there
        // the plain region is the whole draw.
        val shader = lensShader
        val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
        if (!hardware || shader == null) {
            drawPlain()
            return
        }
        val input = if (shaderBitmap === bitmap) bitmapShader else null
        val backdropShader = input ?: BitmapShader(bitmap.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
            .also { bitmapShader = it; shaderBitmap = bitmap }

        val radius = if (pill) size.minDimension / 2f else cornerRadius.toPx()
        val band = GlassLook.LensBandDp.dp.toPx()
        // A segment's lens reaches past its open edges, so a seam is not bent
        // (#91); the region grows by the same amount.
        val frame = EdgeLens.frame(
            size.height, band, GlassEdge.Top in openEdges, GlassEdge.Bottom in openEdges,
        )
        updateRing(radius, band, frame)
        val scale = region.height / size.height
        GlassLens.configure(
            shader, backdropShader,
            width = size.width, height = frame.height, radius = radius,
            regionLeft = region.left.toFloat(), regionTop = region.top - frame.offsetY * scale,
            regionWidth = region.width.toFloat(), regionHeight = frame.height * scale,
            strength = GlassLook.LensStrengthDp.dp.toPx(),
            band = band,
        )
        val ring = ring
        val plain = plain
        translate(top = -frame.offsetY) {
            if (ring == null || plain == null) {
                drawRect(ShaderBrush(shader), size = Size(size.width, frame.height))
            } else {
                clipPath(plain) { translate(top = frame.offsetY) { drawPlain() } }
                drawPath(ring, ShaderBrush(shader))
            }
        }
    }

    /**
     * The lens is the identity deeper than its band, so it is drawn only in
     * the ring along the outline; the plain region covers the rest with the
     * same pixels (#91). The two parts must not overlap at the outline, or
     * its anti-aliased pixels are blended twice: the ring is the lens
     * rectangle minus the area, the plain part the area widened by a pixel,
     * so their shared edge - where both give the same pixels - leaves no
     * seam. Rebuilt only when the size or the shape changes.
     */
    private fun ContentDrawScope.updateRing(radius: Float, band: Float, frame: LensFrame) {
        val key = listOf(size.width, size.height, radius, band, frame.offsetY, frame.height)
        if (key == ringKey) return
        ringKey = key
        val area = EdgeLens.identityArea(size.width, frame.height, radius, band)
        ring = area?.let {
            Path().apply {
                addRect(Rect(0f, 0f, size.width, frame.height))
                op(this, it.path(0f), PathOperation.Difference)
            }
        }
        plain = area?.path(1f)
    }

    private fun acquireLens(): RuntimeShader? = try {
        GlassLens.acquire()
    } catch (e: Throwable) {
        Log.w("GlassBackdrop", "edge lens unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun releaseLens() {
        lensShader?.let(GlassLens::release)
        lensShader = null
        bitmapShader = null
        shaderBitmap = null
    }
}

/**
 * The node's bounds in the launcher window's frame. In the launcher's own
 * window that is [boundsInWindow]; inside a popup, whose [view] belongs to
 * another window, the node's position on screen minus where the launcher
 * window starts.
 */
internal fun androidx.compose.ui.layout.LayoutCoordinates.boundsInLauncherWindow(view: android.view.View): Rect {
    val host = view.context.findActivity()?.window?.decorView
    if (host == null || host.rootView === view.rootView) return boundsInWindow()
    val origin = IntArray(2).also(host::getLocationOnScreen)
    val onScreen = positionOnScreen()
    return Rect(
        offset = Offset(onScreen.x - origin[0], onScreen.y - origin[1]),
        size = Size(size.width.toFloat(), size.height.toFloat()),
    )
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The area as a path, widened by [outset] px on every side. */
private fun LensIdentityArea.path(outset: Float): Path = Path().apply {
    addRoundRect(
        RoundRect(left - outset, top - outset, right + outset, bottom + outset, CornerRadius(radius + outset)),
    )
}

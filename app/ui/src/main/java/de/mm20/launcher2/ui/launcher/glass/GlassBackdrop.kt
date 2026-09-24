package de.mm20.launcher2.ui.launcher.glass

import de.mm20.launcher2.glass.LensIdentityArea
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
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
        // Every icon inside the launcher's scaffold is Clear (#76).
        LocalClearIcons provides true,
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
    val backdrop = LocalGlassBackdrop.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    // The backdrop is mapped to the launcher's window. A popup (a menu) is a
    // window of its own, so bounds are taken on screen and moved into the
    // launcher window's frame (#91); for the launcher's own nodes the two
    // are the same.
    val view = LocalView.current
    val positioned = Modifier.onGloballyPositioned { bounds = it.boundsInLauncherWindow(view) }
    val box = bounds
    if (backdrop == null || box == null) return@composed positioned
    val bitmap = backdrop.bitmap
    val region = BackdropGeometry.region(
        bitmap.width, bitmap.height,
        backdrop.key.windowWidthPx, backdrop.key.windowHeightPx,
        box.left, box.top, box.right, box.bottom,
    )
    val lensShader = if (lens) rememberLens() else null
    val bitmapShader = remember(bitmap, lensShader) {
        lensShader?.let {
            BitmapShader(bitmap.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        }
    }
    positioned
        .semantics {
            this[GlassBackdropRegion] = region
            this[GlassBackdropBlurPx] = backdrop.key.blurPx
        }
        .drawWithCache {
            val radius = if (pill) size.minDimension / 2f else cornerRadius.toPx()
            val band = GlassLook.LensBandDp.dp.toPx()
            // A segment's lens reaches past its open edges, so a seam is not
            // bent (#91); the region grows by the same amount.
            val frame = EdgeLens.frame(
                size.height, band, GlassEdge.Top in openEdges, GlassEdge.Bottom in openEdges,
            )
            // The lens is the identity deeper than its band, so it is drawn
            // only in the ring along the outline; the plain region below
            // covers the rest with the same pixels (#91). Null: the band
            // covers the whole surface.
            val area = EdgeLens.identityArea(size.width, frame.height, radius, band)
            // The two parts must not overlap at the outline, or its
            // anti-aliased pixels are blended twice: the ring is the lens
            // rectangle minus the area, the plain part the area widened by a
            // pixel, so their shared edge - where both give the same pixels -
            // leaves no seam.
            val ring = area?.let {
                Path().apply {
                    addRect(Rect(0f, 0f, size.width, frame.height))
                    op(this, it.path(0f), PathOperation.Difference)
                }
            }
            val plain = area?.path(1f)
            fun DrawScope.drawPlain() = drawImage(
                image = bitmap,
                srcOffset = IntOffset(region.left, region.top),
                srcSize = IntSize(region.width, region.height),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            onDrawBehind {
                // RuntimeShader exists only on the hardware renderer; a
                // software canvas (drawToBitmap, some screenshot paths)
                // throws on it, so there the plain region is the whole draw.
                val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
                if (!hardware || lensShader == null || bitmapShader == null) {
                    drawPlain()
                    return@onDrawBehind
                }
                val scale = region.height / size.height
                GlassLens.configure(
                    lensShader, bitmapShader,
                    width = size.width, height = frame.height, radius = radius,
                    regionLeft = region.left.toFloat(), regionTop = region.top - frame.offsetY * scale,
                    regionWidth = region.width.toFloat(), regionHeight = frame.height * scale,
                    strength = GlassLook.LensStrengthDp.dp.toPx(),
                    band = band,
                )
                translate(top = -frame.offsetY) {
                    if (ring == null || plain == null) {
                        drawRect(ShaderBrush(lensShader), size = Size(size.width, frame.height))
                    } else {
                        clipPath(plain) { translate(top = frame.offsetY) { drawPlain() } }
                        drawPath(ring, ShaderBrush(lensShader))
                    }
                }
            }
        }
}

/** One compiled lens per surface; null where the platform cannot compile AGSL. */
@Composable
private fun rememberLens(): RuntimeShader? = remember {
    try {
        GlassLens.compile()
    } catch (e: Throwable) {
        Log.w("GlassBackdrop", "edge lens unavailable: ${e.javaClass.simpleName}")
        null
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

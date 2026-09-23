package de.mm20.launcher2.ui.launcher.glass

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
import androidx.compose.ui.geometry.Rect
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
    render: suspend (BackdropImage, BackdropKey) -> ImageBitmap?,
) {
    /** `appearance.glass.wallpaperBlur` (#82). */
    val wallpaperBlur: StateFlow<Boolean> = wallpaperBlur.stateIn(scope, SharingStarted.Eagerly, true)

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

/** The backdrop surfaces draw from; null without a managed home wallpaper. */
val LocalGlassBackdrop = staticCompositionLocalOf<RenderedBackdrop<ImageBitmap>?> { null }

/** The backdrop region a surface drew, in backdrop pixels (tests read it). */
val GlassBackdropRegion = SemanticsPropertyKey<PixelRect>("GlassBackdropRegion")

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
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalGlassStyle provides style,
        LocalGlassWallpaperBlur provides wallpaperBlur,
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
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val positioned = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
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
        .semantics { this[GlassBackdropRegion] = region }
        .drawBehind {
            // RuntimeShader exists only on the hardware renderer; a software
            // canvas (drawToBitmap, some screenshot paths) throws on it, so
            // there the plain region is drawn.
            val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
            if (hardware && lensShader != null && bitmapShader != null) {
                val radius = if (pill) size.minDimension / 2f else cornerRadius.toPx()
                GlassLens.configure(
                    lensShader, bitmapShader,
                    width = size.width, height = size.height, radius = radius,
                    regionLeft = region.left.toFloat(), regionTop = region.top.toFloat(),
                    regionWidth = region.width.toFloat(), regionHeight = region.height.toFloat(),
                    strength = GlassLook.LensStrengthDp.dp.toPx(),
                    band = GlassLook.LensBandDp.dp.toPx(),
                )
                drawRect(ShaderBrush(lensShader))
            } else {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset(region.left, region.top),
                    srcSize = IntSize(region.width, region.height),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
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

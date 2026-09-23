package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsPropertyKey
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import de.mm20.launcher2.glass.PixelRect
import de.mm20.launcher2.glass.RenderedBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
    render: suspend (BackdropImage, BackdropKey) -> ImageBitmap?,
) {
    val backdrop: StateFlow<RenderedBackdrop<ImageBitmap>?> = TODO()

    fun setWindow(widthPx: Int, heightPx: Int, density: Float): Unit = TODO()

    suspend fun refresh(): Unit = TODO()
}

/** The backdrop surfaces draw from; null without a managed home wallpaper. */
val LocalGlassBackdrop = staticCompositionLocalOf<RenderedBackdrop<ImageBitmap>?> { null }

/** The backdrop region a surface drew, in backdrop pixels (tests read it). */
val GlassBackdropRegion = SemanticsPropertyKey<PixelRect>("GlassBackdropRegion")

/**
 * Provides [LocalGlassBackdrop] for [content]: tells the controller the
 * window size and asks the source to re-check the wallpaper whenever the
 * launcher comes to the front.
 */
@Composable
fun ProvideGlassBackdrop(controller: GlassBackdropController, content: @Composable () -> Unit) {
    TODO()
}

/** Draws the part of the backdrop that lies under this node, behind its content. */
fun Modifier.glassBackdrop(): Modifier = TODO()

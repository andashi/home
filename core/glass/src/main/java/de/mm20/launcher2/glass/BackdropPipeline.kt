package de.mm20.launcher2.glass

import kotlinx.coroutines.flow.Flow

/** A blurred backdrop and what it was made for. */
data class RenderedBackdrop<B : Any>(val key: BackdropKey, val bitmap: B)

/**
 * The backdrop as a flow: the managed wallpaper, the glass settings and the
 * window become a [BackdropKey]; a key already rendered comes from [cache],
 * a new one is rendered once. Nothing recomposes into it - surfaces read the
 * latest value, so the blur runs per key, never per frame.
 */
class BackdropPipeline<B : Any>(
    image: Flow<BackdropImage?>,
    glass: Flow<GlassInputs>,
    window: Flow<WindowInputs?>,
    private val cache: BackdropCache<B> = BackdropCache(),
    private val render: suspend (BackdropImage, BackdropKey) -> B?,
) {
    val backdrop: Flow<RenderedBackdrop<B>?> = TODO()
}

package de.mm20.launcher2.glass

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

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
    @OptIn(ExperimentalCoroutinesApi::class)
    val backdrop: Flow<RenderedBackdrop<B>?> =
        combine(image, glass, window) { image, glass, window ->
            if (image == null || window == null) null
            else image to BackdropGeometry.key(image, window, glass)
        }
            .distinctUntilChanged()
            .mapLatest { target ->
                target?.let { (image, key) ->
                    cache.get(key) { render(image, key) }?.let { RenderedBackdrop(key, it) }
                }
            }
}

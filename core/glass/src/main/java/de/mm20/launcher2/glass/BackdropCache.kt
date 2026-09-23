package de.mm20.launcher2.glass

/**
 * The blurred backdrops made so far, at most [capacity], least recently used
 * evicted first. Three by default: the phone, and the Fold's cover and inner
 * display, so folding and unfolding does not re-blur.
 */
class BackdropCache<B : Any>(private val capacity: Int = 3) {
    suspend fun get(key: BackdropKey, produce: suspend () -> B?): B? = TODO()
}

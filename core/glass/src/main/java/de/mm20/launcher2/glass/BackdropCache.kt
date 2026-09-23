package de.mm20.launcher2.glass

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The blurred backdrops made so far, at most [capacity], least recently used
 * evicted first. Three by default: the phone, and the Fold's cover and inner
 * display, so folding and unfolding does not re-blur.
 */
class BackdropCache<B : Any>(private val capacity: Int = 3) {
    private val entries = LinkedHashMap<BackdropKey, B>(capacity + 1, 0.75f, true)
    private val mutex = Mutex()

    /**
     * The backdrop for [key], produced at most once while it stays cached.
     * Produced under the lock, so two surfaces asking at once do not blur
     * twice. A failed production (null) is not remembered.
     */
    suspend fun get(key: BackdropKey, produce: suspend () -> B?): B? = mutex.withLock {
        entries[key]?.let { return@withLock it }
        val made = produce() ?: return@withLock null
        entries[key] = made
        while (entries.size > capacity) entries.remove(entries.keys.first())
        made
    }
}

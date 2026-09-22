package de.mm20.launcher2.homegrid

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock around "read the grid, decide, write the grid, set the flag": the
 * default row ([HomeGridDefaults]) and a config reload applying `home.grid`
 * both run that sequence, and on a first start they can run at the same
 * moment (the L4 scenario's step 1 is exactly that). Without the lock the
 * default row can read an empty grid, suspend, and overwrite a layout the
 * reload has just provisioned. A Koin `single`, shared by both writers; it
 * lives here because `services/config` depends on this module.
 */
class HomeGridInitLock {
    private val mutex = Mutex()

    /** True while some coroutine holds the lock; for tests that pin the ordering. */
    val isLocked: Boolean get() = mutex.isLocked

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

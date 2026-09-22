package de.mm20.launcher2.config.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one lock around `launcher.json`. A reload (parse, diff, apply) and a
 * write-back (render, splice, rename) must never interleave: a write-back
 * that lands between a reload's read and its apply would be overwritten by
 * state the reload derived from the old file. Bound as a Koin `single` and
 * shared by [ConfigReloader] and [GridWriteBack].
 */
class ConfigFileLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

package de.mm20.launcher2.homegrid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridInitLockTest {

    @Test
    fun `isLocked reports the lock while a block holds it and not after`() = runBlocking {
        val lock = HomeGridInitLock()
        assertFalse(lock.isLocked)

        val gate = CompletableDeferred<Unit>()
        val holder = launch { lock.withLock { gate.await() } }
        // Let the holder take the lock.
        withTimeoutOrNull(200) { holder.join() }
        assertTrue(lock.isLocked)

        // A second writer waits rather than interleaving.
        val second = launch { lock.withLock { } }
        assertNull(withTimeoutOrNull(200) { second.join() })

        gate.complete(Unit)
        holder.join()
        second.join()
        assertFalse(lock.isLocked)
    }

    @Test
    fun `withLock returns the block's value`() = runBlocking {
        assertEquals(42, HomeGridInitLock().withLock { 42 })
    }
}

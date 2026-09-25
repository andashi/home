package de.mm20.launcher2.glass

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackdropCacheTest {

    private fun key(n: Int) = BackdropKey("sha$n", 1080, 2400, 8)

    @Test
    fun `a key is produced once however often it is asked for`() = runTest {
        val cache = BackdropCache<String>()
        var produced = 0

        repeat(50) { assertEquals("b", cache.get(key(1)) { produced++; "b" }) }

        assertEquals(1, produced)
    }

    @Test
    fun `the least recently used entry goes when a fourth arrives`() = runTest {
        val cache = BackdropCache<String>(capacity = 3)
        val produced = mutableListOf<Int>()
        suspend fun get(n: Int) = cache.get(key(n)) { produced += n; "b$n" }

        get(1); get(2); get(3)
        get(1) // 1 is now the most recent; 2 is the oldest
        get(4)
        get(1); get(3); get(4)
        get(2)

        assertEquals(listOf(1, 2, 3, 4, 2), produced)
    }

    @Test
    fun `a failed render is not cached`() = runTest {
        val cache = BackdropCache<String>()
        var calls = 0

        assertNull(cache.get(key(1)) { calls++; null })
        assertEquals("b", cache.get(key(1)) { calls++; "b" })

        assertEquals(2, calls)
    }

    /**
     * #130: the composition that sees a new window looks its backdrop up
     * without waiting. A lookup neither makes a backdrop nor counts as one.
     */
    @Test
    fun `peek finds a made backdrop without producing, and nothing for one not made`() = runTest {
        val cache = BackdropCache<String>()
        cache.get(key(1)) { "b1" }

        assertEquals("b1", cache.peek(key(1)))
        assertNull(cache.peek(key(2)))
        var produced = 0
        cache.get(key(2)) { produced++; "b2" }
        assertEquals("peek made nothing", 1, produced)
    }

    /** The lookup runs on the main thread; a render in progress must not block it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `peek does not wait for a render in progress`() = runTest {
        val cache = BackdropCache<String>()
        cache.get(key(1)) { "b1" }
        val release = CompletableDeferred<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.get(key(2)) { release.await(); "b2" }
        }

        // The lock is held by the render of key 2 right now.
        assertEquals("b1", cache.peek(key(1)))
        assertNull(cache.peek(key(2)))

        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("b2", cache.peek(key(2)))
    }
}

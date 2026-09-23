package de.mm20.launcher2.glass

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
}

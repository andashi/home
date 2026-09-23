package de.mm20.launcher2.icons

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultIconPackTest {

    private val lawniconsInstalled: suspend (String) -> Boolean = { it == DefaultIconPack.Lawnicons }
    private val nothingInstalled: suspend (String) -> Boolean = { false }

    @Test
    fun `a configured pack wins`() = runTest {
        assertEquals("com.example.pack", DefaultIconPack.effective("com.example.pack", lawniconsInstalled))
    }

    @Test
    fun `no pack configured and Lawnicons installed means Lawnicons`() = runTest {
        assertEquals(DefaultIconPack.Lawnicons, DefaultIconPack.effective(null, lawniconsInstalled))
    }

    @Test
    fun `no pack configured and Lawnicons missing means no pack`() = runTest {
        assertNull(DefaultIconPack.effective(null, nothingInstalled))
    }

    @Test
    fun `a blank pack counts as not configured`() = runTest {
        assertEquals(DefaultIconPack.Lawnicons, DefaultIconPack.effective("  ", lawniconsInstalled))
    }

    @Test
    fun `a configured pack is not second-guessed even when it is not installed`() = runTest {
        // IconService then logs the missing pack and falls back to the apps' own icons,
        // as it always has; the default is for the unconfigured case only.
        assertEquals("com.example.gone", DefaultIconPack.effective("com.example.gone", lawniconsInstalled))
    }
}

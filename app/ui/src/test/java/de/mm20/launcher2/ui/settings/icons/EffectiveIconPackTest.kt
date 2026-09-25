package de.mm20.launcher2.ui.settings.icons

import de.mm20.launcher2.icons.DefaultIconPack
import de.mm20.launcher2.icons.IconPack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #139: the icon settings name the pack that is in effect, by the rule the
 * icon service uses, not the value that happens to be stored. Nothing chosen
 * with Lawnicons installed is Lawnicons, not "System".
 */
class EffectiveIconPackTest {

    private val lawnicons = IconPack(name = "Lawnicons", packageName = DefaultIconPack.Lawnicons, version = "2.18.0", themed = true)
    private val other = IconPack(name = "Other", packageName = "com.example.pack", version = "1", themed = false)

    @Test
    fun `nothing chosen with Lawnicons installed is Lawnicons`() = runTest {
        assertEquals(lawnicons, effectiveIconPack(stored = null, installed = listOf(other, lawnicons)))
    }

    @Test
    fun `nothing chosen without Lawnicons is no pack`() = runTest {
        assertNull(effectiveIconPack(stored = null, installed = listOf(other)))
    }

    @Test
    fun `none is no pack even with Lawnicons installed`() = runTest {
        assertNull(effectiveIconPack(stored = DefaultIconPack.None, installed = listOf(lawnicons)))
    }

    @Test
    fun `a chosen pack is that pack`() = runTest {
        assertEquals(other, effectiveIconPack(stored = other.packageName, installed = listOf(other, lawnicons)))
    }

    /** The icon service then logs the missing pack and uses the apps' own icons. */
    @Test
    fun `a chosen pack that is not installed is no pack`() = runTest {
        assertNull(effectiveIconPack(stored = "com.example.gone", installed = listOf(lawnicons)))
    }
}

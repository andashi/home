package de.mm20.launcher2.ui.launcher.glass

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Every glass surface asks for a lens (#91). Compiling the AGSL source for
 * each one put a shader compilation on the UI thread for every card segment,
 * chip and banner entering search - again on every search open: part 1 drew
 * the search transition on the fold's cover at p50 57 ms instead of 36 ms,
 * and without the lens at 37.7 ms. A surface that leaves hands its lens back
 * and the next one takes it; each live surface still has its own shader, so
 * its uniforms stay its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassLensCompileTest {

    @Test
    fun `surfaces that come and go reuse compiled lenses`() {
        val before = GlassLens.compilations
        val first = List(20) { GlassLens.acquire() }
        first.forEach(GlassLens::release)
        val second = List(20) { GlassLens.acquire() }

        // Search opened twice: the second time compiles nothing.
        assertTrue("compiled ${GlassLens.compilations - before} times", GlassLens.compilations - before <= 20)
        second.forEach(GlassLens::release)
    }

    @Test
    fun `surfaces alive together never share a lens`() {
        val live = List(20) { GlassLens.acquire() }
        val distinct = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(live) }

        assertEquals(20, distinct.size)
        live.forEach(GlassLens::release)
    }
}

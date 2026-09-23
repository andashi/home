package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassWallpaperAlphaTest {

    private fun at(home: Boolean, search: Boolean, progress: Float) =
        GlassWallpaperAlpha.at(homeBlur = home, searchBlur = search, progress = progress)

    @Test
    fun `sharp home, blurred search fades the backdrop in with the search`() {
        assertEquals(0f, at(home = false, search = true, progress = 0f), 1e-6f)
        assertEquals(0.5f, at(home = false, search = true, progress = 0.5f), 1e-6f)
        assertEquals(1f, at(home = false, search = true, progress = 1f), 1e-6f)
    }

    @Test
    fun `blurred home, sharp search fades the backdrop out`() {
        assertEquals(1f, at(home = true, search = false, progress = 0f), 1e-6f)
        assertEquals(0.5f, at(home = true, search = false, progress = 0.5f), 1e-6f)
        assertEquals(0f, at(home = true, search = false, progress = 1f), 1e-6f)
    }

    @Test
    fun `the same setting on both keeps the backdrop constant`() {
        for (p in listOf(0f, 0.5f, 1f)) {
            assertEquals(1f, at(home = true, search = true, progress = p), 1e-6f)
            assertEquals(0f, at(home = false, search = false, progress = p), 1e-6f)
        }
    }

    @Test
    fun `progress outside 0 to 1, as an overshooting animation gives, is clamped`() {
        assertEquals(1f, at(home = false, search = true, progress = 1.3f), 1e-6f)
        assertEquals(0f, at(home = false, search = true, progress = -0.2f), 1e-6f)
    }
}

package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassStyleTest {

    private fun resolve(contrast: Contrast, tint: Float = 0.35f, blur: Float = 24f) =
        GlassStyle.resolve(GlassInputs(blurDp = blur, tint = tint, radiusDp = 28f, contrast = contrast))

    @Test
    fun `medium is the configured values and no scrim`() {
        assertEquals(ResolvedGlass(24f, 0.35f, 28f, 0f), resolve(Contrast.Medium))
    }

    @Test
    fun `low lowers tint by 0_10 and blur by a quarter`() {
        val low = resolve(Contrast.Low)
        assertEquals(18f, low.blurDp, 1e-4f)
        assertEquals(0.25f, low.tint, 1e-4f)
        assertEquals(0f, low.scrimAlpha)
    }

    @Test
    fun `high raises tint by 0_15, blur by a quarter and adds a 12 percent scrim`() {
        val high = resolve(Contrast.High)
        assertEquals(30f, high.blurDp, 1e-4f)
        assertEquals(0.50f, high.tint, 1e-4f)
        assertEquals(0.12f, high.scrimAlpha, 1e-4f)
    }

    @Test
    fun `the scaled tint stays within 0 and 1`() {
        assertEquals(1f, resolve(Contrast.High, tint = 0.95f).tint)
        assertEquals(0f, resolve(Contrast.Low, tint = 0.05f).tint)
    }

    @Test
    fun `blur 0 stays 0 at every contrast, tint only`() {
        for (c in Contrast.entries) assertEquals(0f, resolve(c, blur = 0f).blurDp)
    }

    @Test
    fun `the radius is not scaled`() {
        for (c in Contrast.entries) assertEquals(28f, resolve(c).radiusDp)
    }
}

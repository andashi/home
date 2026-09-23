package de.mm20.launcher2.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackdropGeometryTest {

    private val image = BackdropImage("/data/wallpapers/zone.jpg", "abc")
    private val window = WindowInputs(1080, 2400, 2.625f)
    private val glass = GlassInputs(24f, 0.35f, 28f, Contrast.Medium)

    @Test
    fun `the key carries the blur in backdrop pixels`() {
        // 24 dp at 2.625 is 63 px on screen, 7.875 px at 1/8: rounded to 8.
        assertEquals(BackdropKey("abc", 1080, 2400, 8), BackdropGeometry.key(image, window, glass))
    }

    @Test
    fun `contrast changes the key through the blur`() {
        val medium = BackdropGeometry.key(image, window, glass)
        val high = BackdropGeometry.key(image, window, glass.copy(contrast = Contrast.High))
        assertEquals(10, high.blurPx) // 30 dp * 2.625 / 8 = 9.84
        assertNotEquals(medium, high)
    }

    @Test
    fun `tint and radius do not change the key`() {
        val base = BackdropGeometry.key(image, window, glass)
        assertEquals(base, BackdropGeometry.key(image, window, glass.copy(tint = 0.9f)))
        assertEquals(base, BackdropGeometry.key(image, window, glass.copy(radiusDp = 4f)))
    }

    @Test
    fun `the backdrop is an eighth of the window, rounded up`() {
        assertEquals(135 to 300, BackdropGeometry.backdropSize(1080, 2400))
        assertEquals(136 to 296, BackdropGeometry.backdropSize(1081, 2364))
    }

    @Test
    fun `a wide image in a tall window is cropped at the sides, centered`() {
        assertEquals(PixelRect(300, 0, 500, 400), BackdropGeometry.coverCrop(800, 400, 100, 200))
    }

    @Test
    fun `a tall image in a wide window is cropped at top and bottom, centered`() {
        assertEquals(PixelRect(0, 300, 400, 500), BackdropGeometry.coverCrop(400, 800, 200, 100))
    }

    @Test
    fun `an image with the window's aspect is used whole`() {
        assertEquals(PixelRect(0, 0, 1536, 2752), BackdropGeometry.coverCrop(1536, 2752, 768, 1376))
    }

    @Test
    fun `a window rectangle maps to the backdrop rectangle under it`() {
        // 160 x 320 window, 20 x 40 backdrop: exactly an eighth.
        assertEquals(PixelRect(0, 0, 10, 40), BackdropGeometry.region(20, 40, 160, 320, 0f, 0f, 80f, 320f))
        assertEquals(PixelRect(10, 5, 20, 10), BackdropGeometry.region(20, 40, 160, 320, 80f, 40f, 160f, 80f))
    }

    @Test
    fun `a region is clamped to the backdrop and never empty`() {
        assertEquals(PixelRect(0, 0, 20, 40), BackdropGeometry.region(20, 40, 160, 320, -50f, -10f, 999f, 999f))
        val tiny = BackdropGeometry.region(20, 40, 160, 320, 10f, 10f, 11f, 11f)
        assertEquals(1, tiny.width)
        assertEquals(1, tiny.height)
    }
}

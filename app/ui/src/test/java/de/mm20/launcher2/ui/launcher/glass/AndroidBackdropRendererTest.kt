package de.mm20.launcher2.ui.launcher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Android half of the backdrop: a real file is decoded, and the pixels
 * land where BackdropGeometry says (a synthetic pattern, not a photo).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidBackdropRendererTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

    /** 800 x 400: green, red, blue, green in columns; a 160 x 320 window shows red | blue. */
    private fun pattern(): File {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        for (y in 0 until 400) for (x in 0 until 800) {
            bitmap.setPixel(x, y, when (x) { in 300 until 400 -> red; in 400 until 500 -> blue; else -> green })
        }
        return tmp.newFile("zone.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun `the decoded backdrop is the window's crop at an eighth`() = runTest {
        val file = pattern()

        val backdrop = AndroidBackdropRenderer.render(BackdropImage(file.path, "sha"), BackdropKey("sha", 160, 320, 0))!!

        assertEquals(20, backdrop.width)
        assertEquals(40, backdrop.height)
        val pixels = backdrop.toPixelMap()
        assertEquals(red, pixels[2, 20].toArgbInt())
        assertEquals(blue, pixels[17, 20].toArgbInt())
    }

    @Test
    fun `an unreadable file is no backdrop, not a crash`() = runTest {
        val missing = File(tmp.root, "gone.jpg")

        assertNull(AndroidBackdropRenderer.render(BackdropImage(missing.path, "sha"), BackdropKey("sha", 160, 320, 0)))
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()
}

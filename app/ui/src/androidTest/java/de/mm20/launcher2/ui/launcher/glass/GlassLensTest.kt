package de.mm20.launcher2.ui.launcher.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The edge lens on a device (#82, L2): the AGSL source compiles and draws the
 * backdrop, i.e. the pixels it produces are the backdrop's, not transparent.
 * The JVM cannot run AGSL, so this is the only place that proves it.
 */
@RunWith(AndroidJUnit4::class)
class GlassLensTest {

    @Test
    fun theLensCompilesAndDrawsTheBackdrop() {
        val backdrop = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 200)) }
        val shader = GlassLens.compile()
        GlassLens.configure(
            shader,
            backdrop = BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            width = 160f, height = 320f, radius = 40f,
            regionLeft = 0f, regionTop = 0f, regionWidth = 20f, regionHeight = 40f,
            strength = 20f, band = 30f,
        )

        // RuntimeShader needs HWUI; a Bitmap-backed Canvas is software and
        // refuses it. A Picture rendered into a HARDWARE bitmap goes through
        // the same renderer as the home screen, then is copied to read pixels.
        val picture = Picture()
        picture.beginRecording(160, 320).drawRect(0f, 0f, 160f, 320f, Paint().apply { this.shader = shader })
        picture.endRecording()
        val out = Bitmap.createBitmap(picture, 160, 320, Bitmap.Config.HARDWARE)
            .copy(Bitmap.Config.ARGB_8888, false)

        val centre = out.getPixel(80, 160)
        val nearEdge = out.getPixel(2, 160)
        assertTrue("centre is the backdrop: ${Integer.toHexString(centre)}", Color.blue(centre) > 150 && Color.alpha(centre) == 255)
        assertTrue("the edge samples the backdrop too: ${Integer.toHexString(nearEdge)}", Color.alpha(nearEdge) == 255)
    }
}

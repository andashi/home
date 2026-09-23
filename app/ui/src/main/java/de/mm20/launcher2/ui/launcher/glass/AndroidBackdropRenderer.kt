package de.mm20.launcher2.ui.launcher.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.Pixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes the managed wallpaper just large enough for the backdrop
 * (power-of-two subsampling), then crops, downscales and blurs it through
 * `BackdropGeometry`. Off the main thread; null when the file cannot be read.
 */
object AndroidBackdropRenderer {
    private const val Tag = "GlassBackdrop"

    suspend fun render(image: BackdropImage, key: BackdropKey): ImageBitmap? = withContext(Dispatchers.Default) {
        try {
            decode(image, key)
        } catch (e: Exception) {
            Log.w(Tag, "backdrop not made: ${e.javaClass.simpleName}")
            null
        } catch (e: OutOfMemoryError) {
            Log.w(Tag, "backdrop not made: out of memory")
            null
        }
    }

    private fun decode(image: BackdropImage, key: BackdropKey): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val crop = BackdropGeometry.coverCrop(bounds.outWidth, bounds.outHeight, key.windowWidthPx, key.windowHeightPx)
        val (width, height) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
        // The largest power of two that still leaves at least one source
        // pixel per backdrop pixel inside the crop.
        var sample = 1
        while (crop.width / (sample * 2) >= width && crop.height / (sample * 2) >= height) sample *= 2

        val started = System.nanoTime()
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(image.path, options) ?: return null
        val pixels = IntArray(decoded.width * decoded.height)
        decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        val source = Pixels(decoded.width, decoded.height, pixels)
        decoded.recycle()
        val decodedAt = System.nanoTime()

        val out = BackdropGeometry.render(source, key.windowWidthPx, key.windowHeightPx, key.blurPx)
        val bitmap = Bitmap.createBitmap(out.argb, out.width, out.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        val doneAt = System.nanoTime()
        // Sizes and timing only, no path: what e2e/measure-glass-frametime.sh reads.
        Log.i(
            Tag,
            "backdrop ${out.width}x${out.height} blur ${key.blurPx}px for " +
                    "${key.windowWidthPx}x${key.windowHeightPx} in ${(doneAt - started) / 1_000_000} ms " +
                    "(decode ${source.width}x${source.height} at 1/$sample: ${(decodedAt - started) / 1_000_000} ms, " +
                    "crop+scale+blur: ${(doneAt - decodedAt) / 1_000_000} ms)",
        )
        return bitmap
    }
}

package de.mm20.launcher2.glass

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The config-managed home wallpaper the backdrop is made from: the uploaded
 * file and the hash it was applied with. Null when no managed wallpaper is on
 * the home screen, in which case surfaces draw without a backdrop.
 */
data class BackdropImage(val path: String, val sha256: String)

/** Provided by the config service, consumed by the UI (the pattern of `HomeGridWriteBack`). */
interface GlassBackdropSource {
    val image: StateFlow<BackdropImage?>

    /**
     * Re-reads whether the managed wallpaper is still what the home screen
     * shows. The UI calls it when the launcher comes to the front, which is
     * when a wallpaper changed elsewhere would first be seen.
     */
    suspend fun refresh()
}

/** The window the backdrop covers, in pixels, and its density. */
data class WindowInputs(val widthPx: Int, val heightPx: Int, val density: Float)

/**
 * Everything the blurred bitmap depends on, and nothing else: a change of
 * tint or radius does not re-blur.
 */
data class BackdropKey(
    val sha256: String,
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val blurPx: Int,
)

/** A crop rectangle in source pixels, right and bottom exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** ARGB pixels, row-major. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

object BackdropGeometry {
    /** The backdrop is [Downscale] times smaller than the window it covers. */
    const val Downscale = 8

    fun key(image: BackdropImage, window: WindowInputs, glass: GlassInputs): BackdropKey {
        val blurDp = GlassStyle.resolve(glass).blurDp
        return BackdropKey(
            sha256 = image.sha256,
            windowWidthPx = window.widthPx,
            windowHeightPx = window.heightPx,
            blurPx = (blurDp * window.density / Downscale).roundToInt(),
        )
    }

    /** The backdrop's size for a window: every window pixel has a backdrop pixel. */
    fun backdropSize(windowWidthPx: Int, windowHeightPx: Int): Pair<Int, Int> =
        ceilDiv(windowWidthPx, Downscale) to ceilDiv(windowHeightPx, Downscale)

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /**
     * What the system shows of an image set without a crop hint: the centered
     * part with the window's aspect ratio, as large as the image allows.
     */
    fun coverCrop(imageWidth: Int, imageHeight: Int, windowWidthPx: Int, windowHeightPx: Int): PixelRect {
        // Compare aspect ratios without division: image w/h against window w/h.
        val imageWider = imageWidth.toLong() * windowHeightPx > imageHeight.toLong() * windowWidthPx
        return if (imageWider) {
            val width = (imageHeight.toLong() * windowWidthPx / windowHeightPx).toInt().coerceIn(1, imageWidth)
            val left = (imageWidth - width) / 2
            PixelRect(left, 0, left + width, imageHeight)
        } else {
            val height = (imageWidth.toLong() * windowHeightPx / windowWidthPx).toInt().coerceIn(1, imageHeight)
            val top = (imageHeight - height) / 2
            PixelRect(0, top, imageWidth, top + height)
        }
    }

    /** The part of the backdrop that lies under a rectangle in window pixels. */
    fun region(
        backdropWidth: Int,
        backdropHeight: Int,
        windowWidthPx: Int,
        windowHeightPx: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): PixelRect {
        val sx = backdropWidth.toFloat() / windowWidthPx
        val sy = backdropHeight.toFloat() / windowHeightPx
        val l = floor(left * sx).toInt().coerceIn(0, backdropWidth - 1)
        val t = floor(top * sy).toInt().coerceIn(0, backdropHeight - 1)
        val r = ceil(right * sx).toInt().coerceIn(l + 1, backdropWidth)
        val b = ceil(bottom * sy).toInt().coerceIn(t + 1, backdropHeight)
        return PixelRect(l, t, r, b)
    }

    /**
     * The backdrop for [window] from decoded [image] pixels (possibly
     * subsampled; only the aspect ratio matters): cover crop, area-average
     * downscale to [backdropSize], box blur of [blurPx].
     */
    fun render(image: Pixels, windowWidthPx: Int, windowHeightPx: Int, blurPx: Int): Pixels {
        val crop = coverCrop(image.width, image.height, windowWidthPx, windowHeightPx)
        val (width, height) = backdropSize(windowWidthPx, windowHeightPx)
        return BoxBlur.blur(downscale(image, crop, width, height), blurPx)
    }

    /** Area average: every target pixel is the mean of the source block it covers. */
    private fun downscale(image: Pixels, crop: PixelRect, width: Int, height: Int): Pixels {
        val out = IntArray(width * height)
        for (ty in 0 until height) {
            val y0 = crop.top + ty * crop.height / height
            val y1 = maxOf(y0 + 1, crop.top + (ty + 1) * crop.height / height)
            for (tx in 0 until width) {
                val x0 = crop.left + tx * crop.width / width
                val x1 = maxOf(x0 + 1, crop.left + (tx + 1) * crop.width / width)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (y in y0 until y1) {
                    val row = y * image.width
                    for (x in x0 until x1) {
                        val p = image.argb[row + x]
                        a += p ushr 24; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
                    }
                }
                val n = (y1 - y0).toLong() * (x1 - x0)
                out[ty * width + tx] = pack(a, r, g, b, n)
            }
        }
        return Pixels(width, height, out)
    }
}

/** Mean of summed channels over [n] samples, rounded, as one ARGB int. */
internal fun pack(a: Long, r: Long, g: Long, b: Long, n: Long): Int {
    val half = n / 2
    return (((a + half) / n).toInt() shl 24) or (((r + half) / n).toInt() shl 16) or
            (((g + half) / n).toInt() shl 8) or ((b + half) / n).toInt()
}

/** A separable box blur, three passes, which approximates a Gaussian. */
object BoxBlur {
    private const val Passes = 3

    fun blur(pixels: Pixels, radius: Int): Pixels {
        if (radius <= 0) return pixels
        var current = pixels.argb.copyOf()
        var scratch = IntArray(current.size)
        repeat(Passes) {
            pass(current, scratch, pixels.width, pixels.height, radius, horizontal = true)
            pass(scratch, current, pixels.width, pixels.height, radius, horizontal = false)
        }
        return Pixels(pixels.width, pixels.height, current)
    }

    /**
     * One box pass along rows or columns, as a sliding sum over 2r+1
     * samples; indices outside the image clamp to the edge, so a uniform
     * image stays uniform and a radius larger than the image is harmless.
     */
    private fun pass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val n = (2L * radius + 1)
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * width + c] else src[c * width + line]
            }
            var a = 0L; var r = 0L; var g = 0L; var b = 0L
            for (i in -radius..radius) {
                val p = at(i)
                a += p ushr 24; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                val index = if (horizontal) line * width + i else i * width + line
                dst[index] = pack(a, r, g, b, n)
                val add = at(i + radius + 1)
                val sub = at(i - radius)
                a += (add ushr 24) - (sub ushr 24)
                r += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                g += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}

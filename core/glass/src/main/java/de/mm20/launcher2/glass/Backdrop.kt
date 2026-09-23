package de.mm20.launcher2.glass

import kotlinx.coroutines.flow.StateFlow

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

    fun key(image: BackdropImage, window: WindowInputs, glass: GlassInputs): BackdropKey = TODO()

    /** The backdrop's size for a window: every window pixel has a backdrop pixel. */
    fun backdropSize(windowWidthPx: Int, windowHeightPx: Int): Pair<Int, Int> = TODO()

    /**
     * What the system shows of an image set without a crop hint: the centered
     * part with the window's aspect ratio, as large as the image allows.
     */
    fun coverCrop(imageWidth: Int, imageHeight: Int, windowWidthPx: Int, windowHeightPx: Int): PixelRect = TODO()

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
    ): PixelRect = TODO()

    /**
     * The backdrop for [window] from decoded [image] pixels (possibly
     * subsampled; only the aspect ratio matters): cover crop, area-average
     * downscale to [backdropSize], box blur of [blurPx].
     */
    fun render(image: Pixels, windowWidthPx: Int, windowHeightPx: Int, blurPx: Int): Pixels = TODO()
}

/** A separable box blur, three passes, which approximates a Gaussian. */
object BoxBlur {
    fun blur(pixels: Pixels, radius: Int): Pixels = TODO()
}

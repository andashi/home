package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.ui.graphics.ImageBitmap
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey

/**
 * Decodes the managed wallpaper just large enough for the backdrop
 * (power-of-two subsampling), then crops, downscales and blurs it through
 * `BackdropGeometry`. Off the main thread; null when the file cannot be read.
 */
object AndroidBackdropRenderer {
    suspend fun render(image: BackdropImage, key: BackdropKey): ImageBitmap? = TODO()
}

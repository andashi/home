package de.mm20.launcher2.ui.launcher.glass

import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The node the full-window backdrop is drawn in (tests find it by this tag). */
const val GlassWallpaperTag = "glass-wallpaper"

/** `appearance.glass.wallpaperBlur`: whether the home background is the blurred backdrop. */
val LocalGlassWallpaperBlur = staticCompositionLocalOf { false }

/**
 * The home background as the blurred backdrop, full window (#82), drawn
 * behind the scaffold. Nothing without a backdrop or with `wallpaperBlur`
 * off: the sharp system wallpaper shows through as before.
 */
@Composable
fun GlassWallpaper(modifier: Modifier = Modifier) {
    TODO()
}

/**
 * The edge lens (#82) as an AGSL shader: the backdrop region, sampled with
 * `EdgeLens`' displacement. Null where the platform cannot compile it (the
 * JVM tests), in which case the surface draws the plain region.
 */
object GlassLens {
    /** Compiles the shader; throws when the source does not compile. */
    fun compile(): RuntimeShader = TODO()

    /** Sets the backdrop and the uniforms, all in pixels. */
    fun configure(
        shader: RuntimeShader,
        backdrop: android.graphics.Shader,
        width: Float,
        height: Float,
        radius: Float,
        regionLeft: Float,
        regionTop: Float,
        regionWidth: Float,
        regionHeight: Float,
        strength: Float,
        band: Float,
    ): Unit = TODO()
}

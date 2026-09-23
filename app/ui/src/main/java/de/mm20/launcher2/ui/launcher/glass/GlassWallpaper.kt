package de.mm20.launcher2.ui.launcher.glass

import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** The node the full-window backdrop is drawn in (tests find it by this tag). */
const val GlassWallpaperTag = "glass-wallpaper"

/** `appearance.glass.wallpaperBlur`: whether the home background is the blurred backdrop. */
val LocalGlassWallpaperBlur = staticCompositionLocalOf { false }

/** `appearance.glass.searchWallpaperBlur`: whether the background behind search is (#91). */
val LocalGlassSearchWallpaperBlur = staticCompositionLocalOf { true }

/** The alpha the full-window backdrop was drawn with (tests read it, #91). */
val GlassWallpaperAlphaKey = androidx.compose.ui.semantics.SemanticsPropertyKey<Float>("GlassWallpaperAlpha")

/**
 * The home background as the blurred backdrop, full window (#82), drawn
 * behind the scaffold. Nothing without a backdrop or with `wallpaperBlur`
 * off: the sharp system wallpaper shows through as before.
 */
@Composable
fun GlassWallpaper(
    modifier: Modifier = Modifier,
    /** The search page's progress, 0 = home, 1 = search open (#91). */
    searchProgress: () -> Float = { 0f },
) {
    val backdrop = LocalGlassBackdrop.current
    if (!LocalGlassWallpaperBlur.current || backdrop == null) return
    val bitmap = backdrop.bitmap
    Box(
        modifier
            .fillMaxSize()
            .testTag(GlassWallpaperTag)
            .drawBehind {
                drawImage(bitmap, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
    )
}

/**
 * The edge lens (#82) as an AGSL shader: the backdrop region, sampled with
 * `EdgeLens`' displacement - the same signed distance, the same squared
 * falloff over the band, the same pull along the outward normal. The JVM
 * tests pin the function; `GlassLensTest` on a device pins that this source
 * compiles and draws.
 */
object GlassLens {
    private const val Source = """
        uniform shader backdrop;
        uniform float2 size;
        uniform float radius;
        uniform float4 region;
        uniform float strength;
        uniform float band;

        float sd(float2 c) {
            float r = min(radius, min(size.x, size.y) * 0.5);
            float2 q = abs(c - size * 0.5) - size * 0.5 + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 c) {
            float t = clamp(1.0 + sd(c) / band, 0.0, 1.0);
            float pull = t * t * strength;
            float2 g = float2(sd(c + float2(0.5, 0.0)) - sd(c - float2(0.5, 0.0)),
                              sd(c + float2(0.0, 0.5)) - sd(c - float2(0.0, 0.5)));
            float len = length(g);
            float2 s = c;
            if (pull > 0.0 && len > 0.0) {
                s = clamp(c - g / len * pull, float2(0.0), size);
            }
            return backdrop.eval(region.xy + s / size * region.zw);
        }
    """

    /** Compiles the shader; throws when the source does not compile. */
    fun compile(): RuntimeShader = RuntimeShader(Source)

    /** Sets the backdrop and the uniforms, all in pixels. */
    fun configure(
        shader: RuntimeShader,
        backdrop: Shader,
        width: Float,
        height: Float,
        radius: Float,
        regionLeft: Float,
        regionTop: Float,
        regionWidth: Float,
        regionHeight: Float,
        strength: Float,
        band: Float,
    ) {
        shader.setInputShader("backdrop", backdrop)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("region", regionLeft, regionTop, regionWidth, regionHeight)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("band", band)
    }
}

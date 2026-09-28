package de.mm20.launcher2.glass

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * `mute` on a grid item (#78): an external widget's own colours turned grey.
 * Each pixel becomes the grey of **its own relative luminance**, so every
 * contrast ratio in the widget is kept up to the grey's 8-bit rounding - at
 * most 1.6% for a pair, 1.3% measured (WidgetMuteTest).
 *
 * Not `ColorMatrix.setSaturation(0)`: that weighs gamma-encoded sRGB, while
 * contrast is defined on linear luminance, so saturated colours collapse to
 * the wrong grey. Measured over 331,636 colour pairs that met WCAG's 4.5:1
 * for text: 6.1% fell below it, red on near-black going from 4.9:1 to 1.56:1.
 * A matrix cannot keep a quantity it does not operate on, so the effect is a
 * shader that linearizes first.
 *
 * [grey] is the reference, testable here; [Shader] is what the device runs,
 * the same computation in AGSL. The device step compares the two.
 */
object WidgetMute {

    /** [argb] as the grey of its own relative luminance; alpha unchanged. */
    fun grey(argb: Int): Int {
        val a = argb ushr 24
        val y = 0.2126 * linear(argb shr 16 and 0xff) + 0.7152 * linear(argb shr 8 and 0xff) + 0.0722 * linear(argb and 0xff)
        val g = encode(y)
        return (a shl 24) or (g shl 16) or (g shl 8) or g
    }

    /** Relative luminance of an sRGB colour, as WCAG defines it. */
    fun luminance(argb: Int): Double =
        0.2126 * linear(argb shr 16 and 0xff) + 0.7152 * linear(argb shr 8 and 0xff) + 0.0722 * linear(argb and 0xff)

    /** WCAG contrast ratio of two opaque colours, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun encode(y: Double): Int {
        val c = if (y <= 0.0031308) y * 12.92 else 1.055 * y.pow(1 / 2.4) - 0.055
        return (c * 255).roundToInt().coerceIn(0, 255)
    }

    /**
     * The effect as the device runs it: a RuntimeShader for
     * `RenderEffect.createRuntimeShaderEffect(shader, "content")`. Colours
     * reach it premultiplied and in the working colour space, so it
     * unpremultiplies and converts to linear sRGB with AGSL's own
     * `toLinearSrgb` rather than assuming the working space is sRGB.
     */
    const val Shader = """
uniform shader content;

half4 main(float2 coord) {
    half4 c = content.eval(coord);
    if (c.a <= 0.0) return c;
    half3 lin = toLinearSrgb(c.rgb / c.a);
    half y = dot(lin, half3(0.2126, 0.7152, 0.0722));
    half3 grey = fromLinearSrgb(half3(y));
    return half4(grey * c.a, c.a);
}
"""
}

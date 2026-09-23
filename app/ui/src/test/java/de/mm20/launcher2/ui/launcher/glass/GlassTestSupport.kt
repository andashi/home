package de.mm20.launcher2.ui.launcher.glass

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.ContextCompat
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.BackdropKey
import de.mm20.launcher2.glass.RenderedBackdrop
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import dynamiccolor.DynamicScheme
import dynamiccolor.MaterialDynamicColors
import hct.Hct
import kotlinx.coroutines.runBlocking
import scheme.SchemeMonochrome
import scheme.SchemeTonalSpot
import java.io.File

/** A glyph icon from a platform drawable: the white-glyph path of the Clear look. */
fun glyphIcon(context: Context, res: Int) = StaticLauncherIcon(
    TintedIconLayer(ContextCompat.getDrawable(context, res)!!, scale = 0.6f),
    ColorLayer(0),
)

/** A loud original, red to yellow: the icon that must never show in color (the fallback). */
fun fallbackIcon() = StaticLauncherIcon(
    StaticIconLayer(
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt()))
    ),
    ColorLayer(0xFF1E88E5.toInt()),
)

/** Three glyphs and one fallback, the dock of the goldens (#76, #77). */
fun dockIcons(context: Context) = listOf(
    glyphIcon(context, android.R.drawable.ic_menu_call),
    glyphIcon(context, android.R.drawable.ic_dialog_email),
    fallbackIcon(),
    glyphIcon(context, android.R.drawable.ic_menu_search),
)

/** A zone color as the launcher derives it: a Monet scheme from the zone's seed. */
enum class ZoneSeed(val argb: Int, val monochrome: Boolean) {
    /** provisioning's `cloud` zone, 4285F4, tonal spot: the cold one. */
    Cold(0xFF4285F4.toInt(), monochrome = false),

    /** provisioning's `gadgets` zone, F9AB00, tonal spot: the warm one. */
    Warm(0xFFF9AB00.toInt(), monochrome = false),

    /** provisioning's colorless `home` zone, D8DEE9, monochromatic. */
    Home(0xFFD8DEE9.toInt(), monochrome = true);

    fun colorScheme(): ColorScheme {
        val hct = Hct.fromInt(argb)
        val scheme: DynamicScheme = if (monochrome) SchemeMonochrome(hct, false, 0.0) else SchemeTonalSpot(hct, false, 0.0)
        val colors = MaterialDynamicColors()
        fun c(color: dynamiccolor.DynamicColor) = Color(color.getArgb(scheme))
        return lightColorScheme(
            primary = c(colors.primary),
            onPrimary = c(colors.onPrimary),
            primaryContainer = c(colors.primaryContainer),
            onPrimaryContainer = c(colors.onPrimaryContainer),
            secondaryContainer = c(colors.secondaryContainer),
            surface = c(colors.surface),
            onSurface = c(colors.onSurface),
            surfaceVariant = c(colors.surfaceVariant),
            onSurfaceVariant = c(colors.onSurfaceVariant),
        )
    }
}

/**
 * The backdrop of provisioning's Mauritius wallpaper for a window, rendered
 * by the production renderer from the downscaled copy in the test resources.
 */
fun mauritiusBackdrop(square: Boolean, widthPx: Int, heightPx: Int, blurPx: Int): RenderedBackdrop<ImageBitmap> {
    val name = if (square) "glass/mauritius-square.jpg" else "glass/mauritius-tall.jpg"
    val file = File.createTempFile("mauritius", ".jpg").apply { deleteOnExit() }
    file.outputStream().use { out ->
        checkNotNull(GlassTestSupport::class.java.classLoader!!.getResourceAsStream(name)) { "$name missing" }.use { it.copyTo(out) }
    }
    val key = BackdropKey("mauritius-$name", widthPx, heightPx, blurPx)
    val bitmap = runBlocking { AndroidBackdropRenderer.render(BackdropImage(file.path, key.sha256), key) }
    return RenderedBackdrop(key, checkNotNull(bitmap) { "the renderer could not read $name" })
}

private object GlassTestSupport

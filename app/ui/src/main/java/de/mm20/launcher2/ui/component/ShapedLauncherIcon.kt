package de.mm20.launcher2.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.icu.number.NumberFormatter
import android.icu.text.NumberFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.semantics.semantics
import de.mm20.launcher2.glass.GlassLook
import de.mm20.launcher2.ui.launcher.glass.ClearIcon
import de.mm20.launcher2.ui.launcher.glass.ClearIconKey
import de.mm20.launcher2.ui.launcher.glass.ClearIconKind
import de.mm20.launcher2.ui.launcher.glass.GlassSurface
import de.mm20.launcher2.ui.launcher.glass.LocalClearIcons
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mm20.launcher2.badges.Badge
import de.mm20.launcher2.badges.BadgeIcon
import de.mm20.launcher2.icons.ClockLayer
import de.mm20.launcher2.icons.ClockSublayer
import de.mm20.launcher2.icons.ClockSublayerRole
import de.mm20.launcher2.icons.DynamicLauncherIcon
import de.mm20.launcher2.icons.LauncherIcon
import de.mm20.launcher2.icons.LauncherIconRenderSettings
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TextLayer
import de.mm20.launcher2.icons.TintedClockLayer
import de.mm20.launcher2.icons.TransparentLayer
import de.mm20.launcher2.icons.VectorLayer
import de.mm20.launcher2.ktx.drawWithColorFilter
import de.mm20.launcher2.ui.base.LocalTime
import de.mm20.launcher2.ui.ktx.toPixels
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.ui.modifier.scale
import palettes.TonalPalette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import android.graphics.Shader as PlatformShader

@Composable
fun ShapedLauncherIcon(
    modifier: Modifier = Modifier,
    size: Dp,
    icon: () -> LauncherIcon? = { null },
    badge: () -> Badge? = { null },
    shape: Shape = SquircleShape,
) {
    if (LocalClearIcons.current) {
        ClearLauncherIcon(modifier, size, icon, badge)
        return
    }

    val _icon = icon()

    var currentIcon by remember(_icon) {
        mutableStateOf(
            when (_icon) {
                is DynamicLauncherIcon -> null
                is StaticLauncherIcon -> _icon
                else -> null
            }
        )
    }

    val defaultIconSize = LocalGridSettings.current.iconSize.dp

    val renderSettings = LauncherIconRenderSettings(
        size = defaultIconSize.toPixels().toInt(),
        fgThemeColor = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
        bgThemeColor = MaterialTheme.colorScheme.primaryContainer.toArgb(),
        fgTone = if (LocalDarkTheme.current) 90 else 10,
        bgTone = if (LocalDarkTheme.current) 30 else 90,
    )

    var currentBitmap by remember {
        mutableStateOf(currentIcon?.getCachedBitmap(renderSettings))
    }

    LaunchedEffect(currentIcon, renderSettings) {
        currentBitmap = currentIcon?.render(renderSettings)
    }

    if (_icon is DynamicLauncherIcon) {
        val date = Instant.ofEpochMilli(LocalTime.current).atZone(ZoneId.systemDefault())
        LaunchedEffect(date.dayOfYear, _icon) {
            currentIcon = _icon.getIcon(date.toEpochSecond() * 1000L)
        }
    }

    Box(
        modifier = modifier
            .size(size)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val bmp = currentBitmap
            val ic = currentIcon
            if (bmp != null && ic != null) {
                Canvas(
                    modifier = Modifier
                        .requiredSize(defaultIconSize)
                        .scale(size / defaultIconSize, TransformOrigin.Center)
                ) {
                    val brush = BitmapShaderBrush(bmp)
                    if (ic.backgroundLayer is TransparentLayer) {
                        drawRect(brush)
                    } else {
                        val outline =
                            shape.createOutline(
                                this.size,
                                layoutDirection,
                                Density(density, fontScale)
                            )
                        drawOutline(outline, brush)
                    }
                }
                // Background layer is always static layer, color layer, or transparent layer
                val fg = ic.foregroundLayer
                when (fg) {
                    is ClockLayer -> {
                        ClockLayer(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(shape),
                            sublayers = fg.sublayers,
                            defaultMinute = fg.defaultMinute,
                            defaultHour = fg.defaultHour,
                            defaultSecond = fg.defaultSecond,
                            scale = fg.scale,
                            tintColor = null,
                        )
                    }

                    is TintedClockLayer -> {
                        ClockLayer(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(shape),
                            sublayers = fg.sublayers,
                            defaultMinute = fg.defaultMinute,
                            defaultHour = fg.defaultHour,
                            defaultSecond = fg.defaultSecond,
                            scale = fg.scale,
                            tintColor = if (fg.color == 0) {
                                Color(renderSettings.fgThemeColor)
                            } else {
                                Color(getTone(fg.color, renderSettings.fgTone))
                            },
                        )
                    }

                    is TextLayer -> {
                        Text(
                            text = fg.text,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 20.sp * (size / 48.dp)
                            ),
                            color = if (fg.color == 0) {
                                Color(renderSettings.fgThemeColor)
                            } else {
                                Color(getTone(fg.color, renderSettings.fgTone))
                            },
                        )
                    }

                    is VectorLayer -> {
                        Icon(
                            painter = painterResource(fg.icon), contentDescription = null,
                            tint = if (fg.color == 0) {
                                Color(renderSettings.fgThemeColor)
                            } else {
                                Color(getTone(fg.color, renderSettings.fgTone))
                            },
                            modifier = Modifier.size(size / 2f),
                        )
                    }
                    else -> {}
                }
            } else {
                val color = MaterialTheme.colorScheme.secondaryContainer
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    val outline =
                        shape.createOutline(this.size, layoutDirection, Density(density, fontScale))
                    drawOutline(outline, color)
                }
            }
        }
        val _badge = badge()
        if (_badge != null) {
            Badge(
                badge = _badge,
                modifier = Modifier
                    .align(Alignment.BottomEnd)     ,
                size = size * 0.33f
            )
        }
    }
}

/**
 * The Clear look (ADR 0004, #76): a glass chip on the squircle, the icon's
 * glyph in white on it, or - when the app has no glyph - its original
 * drawn without saturation. Never the colored icon. The squircle, as every
 * icon is (#229).
 */
@Composable
private fun ClearLauncherIcon(
    modifier: Modifier,
    size: Dp,
    icon: () -> LauncherIcon?,
    badge: () -> Badge?,
) {
    val _icon = icon()
    var currentIcon by remember(_icon) { mutableStateOf(_icon as? StaticLauncherIcon) }
    if (_icon is DynamicLauncherIcon) {
        val date = Instant.ofEpochMilli(LocalTime.current).atZone(ZoneId.systemDefault())
        LaunchedEffect(date.dayOfYear, _icon) {
            currentIcon = _icon.getIcon(date.toEpochSecond() * 1000L)
        }
    }
    val clear = remember(currentIcon) { currentIcon?.let { ClearIcon.of(it) } }

    val defaultIconSize = LocalGridSettings.current.iconSize.dp
    val renderSettings = LauncherIconRenderSettings(
        size = defaultIconSize.toPixels().toInt(),
        fgThemeColor = Color.White.toArgb(),
        bgThemeColor = Color.Transparent.toArgb(),
        fgTone = 100,
        bgTone = 0,
    )
    var bitmap by remember(clear) { mutableStateOf(clear?.icon?.getCachedBitmap(renderSettings)) }
    LaunchedEffect(clear, renderSettings) {
        bitmap = clear?.icon?.render(renderSettings)
    }

    Box(modifier = modifier.size(size)) {
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = SquircleShape,
            tintBoost = GlassLook.ChipTintBoost,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (clear != null) {
                            Modifier.semantics {
                                this[ClearIconKey] =
                                    if (clear is ClearIcon.Glyph) ClearIconKind.Glyph else ClearIconKind.Desaturated
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null && clear != null) {
                    Canvas(
                        modifier = Modifier
                            .requiredSize(defaultIconSize)
                            .scale(size / defaultIconSize, TransformOrigin.Center)
                    ) {
                        val outline = SquircleShape.createOutline(this.size, layoutDirection, Density(density, fontScale))
                        drawOutline(
                            outline,
                            BitmapShaderBrush(bmp),
                            colorFilter = if (clear is ClearIcon.Desaturated) Desaturate else null,
                        )
                    }
                    when (val fg = clear.icon.foregroundLayer) {
                        is TintedClockLayer -> ClockLayer(
                            modifier = Modifier.fillMaxSize().clip(SquircleShape),
                            sublayers = fg.sublayers,
                            defaultMinute = fg.defaultMinute,
                            defaultHour = fg.defaultHour,
                            defaultSecond = fg.defaultSecond,
                            scale = fg.scale,
                            tintColor = Color.White,
                        )

                        is TextLayer -> Text(
                            text = fg.text,
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp * (size / 48.dp)),
                            color = Color.White,
                        )

                        is VectorLayer -> Icon(
                            painter = painterResource(fg.icon),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(size / 2f),
                        )

                        else -> {}
                    }
                }
            }
        }
        val _badge = badge()
        if (_badge != null) {
            Badge(badge = _badge, modifier = Modifier.align(Alignment.BottomEnd), size = size * 0.33f)
        }
    }
}

/** No saturation: the fallback reads as a grey glyph, one step below a real one. */
private val Desaturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private fun getTone(argb: Int, tone: Int): Int {
    return TonalPalette
        .fromInt(argb)
        .tone(tone)
}

@Composable
private fun ClockLayer(
    sublayers: List<ClockSublayer>,
    defaultMinute: Int,
    defaultHour: Int,
    defaultSecond: Int,
    scale: Float,
    tintColor: Color?,
    modifier: Modifier = Modifier,
) {
    val time = Instant.ofEpochMilli(LocalTime.current).atZone(ZoneId.systemDefault())

    val second = time.second
    val minute = time.minute
    val hour = time.hour

    Canvas(modifier = modifier) {
        val colorFilter = tintColor?.let {
            PorterDuffColorFilter(tintColor.toArgb(), PorterDuff.Mode.SRC_IN)
        }
        withTransform({
            this.scale(scale)
        }) {
            for (sublayer in sublayers) {
                when (sublayer.role) {
                    ClockSublayerRole.Hour -> {
                        sublayer.drawable.level = (((hour - defaultHour + 12) % 12) * 60
                                + ((minute) % 60))
                    }

                    ClockSublayerRole.Minute -> sublayer.drawable.level =
                        ((minute - defaultMinute + 60) % 60)

                    ClockSublayerRole.Second -> sublayer.drawable.level =
                        (((second - defaultSecond + 60) % 60) * 10)

                    else -> {}
                }
                drawIntoCanvas {
                    sublayer.drawable.bounds = this.size.toRect().toAndroidRect()
                    sublayer.drawable.drawWithColorFilter(it.nativeCanvas, colorFilter)
                }
            }
        }
    }
}

class BitmapShaderBrush(
    val bitmap: Bitmap,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        return BitmapShader(bitmap, PlatformShader.TileMode.CLAMP, PlatformShader.TileMode.CLAMP)
    }

}

/**
 * The Clear icon chip's outline (#76); the best-match highlight uses it too
 * (#91). One instance (#122): a new shape per access made every clip and rim
 * cache keyed on it see a different shape on each recomposition.
 */
internal val SquircleShape: Shape = GenericShape { size, _ ->
    val radius = size.width / 2f
    val radiusToPow = radius.pow(3f).toDouble()
    moveTo(-radius, 0f)
    for (x in -radius.roundToInt()..radius.roundToInt())
        lineTo(
            x.toFloat(),
            Math.cbrt(radiusToPow - abs(x * x * x)).toFloat()
        )
    for (x in radius.roundToInt() downTo -radius.roundToInt())
        lineTo(
            x.toFloat(),
            (-Math.cbrt(radiusToPow - abs(x * x * x))).toFloat()
        )
    translate(Offset(size.width / 2f, size.height / 2f))
}

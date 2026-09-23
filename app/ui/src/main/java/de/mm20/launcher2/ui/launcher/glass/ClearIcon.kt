package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.SemanticsPropertyKey
import de.mm20.launcher2.icons.ClockLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TextLayer
import de.mm20.launcher2.icons.TintedClockLayer
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.icons.TransparentLayer
import de.mm20.launcher2.icons.VectorLayer

/**
 * The Clear look (ADR 0004, #76): an icon is either a white glyph or the
 * desaturated original, both on a glass chip. Never the colored icon.
 */
sealed interface ClearIcon {
    /** The icon to render: glyph layers with their colors dropped, or the original. */
    val icon: StaticLauncherIcon

    /** A monochrome glyph, drawn white. */
    data class Glyph(override val icon: StaticLauncherIcon) : ClearIcon

    /** No glyph exists: the original, drawn without saturation. */
    data class Desaturated(override val icon: StaticLauncherIcon) : ClearIcon

    companion object {
        fun of(icon: StaticLauncherIcon): ClearIcon {
            return when (val fg = icon.foregroundLayer) {
                is TintedIconLayer -> if (fg.forced) {
                    // ForceThemedIconTransformation shrank it by 1.2 for the
                    // silhouette; the original goes back to its own scale.
                    Desaturated(StaticLauncherIcon(StaticIconLayer(fg.icon, fg.scale * 1.2f), TransparentLayer))
                } else {
                    Glyph(StaticLauncherIcon(fg.copy(color = 0), TransparentLayer))
                }
                is TintedClockLayer -> Glyph(StaticLauncherIcon(fg.copy(color = 0), TransparentLayer))
                is ClockLayer -> Glyph(
                    StaticLauncherIcon(
                        TintedClockLayer(
                            sublayers = fg.sublayers,
                            defaultHour = fg.defaultHour,
                            defaultMinute = fg.defaultMinute,
                            defaultSecond = fg.defaultSecond,
                            scale = fg.scale,
                        ),
                        TransparentLayer,
                    )
                )
                is VectorLayer -> Glyph(StaticLauncherIcon(fg.copy(color = 0), TransparentLayer))
                is TextLayer -> Glyph(StaticLauncherIcon(fg.copy(color = 0), TransparentLayer))
                else -> Desaturated(icon)
            }
        }
    }
}

/** Whether icons render in the Clear look; true inside the launcher's scaffold. */
val LocalClearIcons = staticCompositionLocalOf { false }

/** What a Clear icon drew, for tests. */
enum class ClearIconKind { Glyph, Desaturated }

val ClearIconKey = SemanticsPropertyKey<ClearIconKind>("ClearIcon")

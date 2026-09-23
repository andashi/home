package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.SemanticsPropertyKey
import de.mm20.launcher2.icons.StaticLauncherIcon

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
        fun of(icon: StaticLauncherIcon): ClearIcon = TODO()
    }
}

/** Whether icons render in the Clear look; true inside the launcher's scaffold. */
val LocalClearIcons = staticCompositionLocalOf { false }

/** What a Clear icon drew, for tests. */
enum class ClearIconKind { Glyph, Desaturated }

val ClearIconKey = SemanticsPropertyKey<ClearIconKind>("ClearIcon")

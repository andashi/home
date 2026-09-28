package de.mm20.launcher2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.themes.ThemeRepository
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import de.mm20.launcher2.ui.theme.colorscheme.darkColorSchemeOf
import de.mm20.launcher2.ui.theme.colorscheme.lightColorSchemeOf
import de.mm20.launcher2.ui.theme.shapes.shapesOf
import de.mm20.launcher2.ui.theme.typography.typographyOf
import kotlinx.coroutines.flow.flatMapLatest
import org.koin.compose.koinInject
import de.mm20.launcher2.preferences.ColorScheme as ColorSchemePref


@Composable
fun LauncherTheme(
    content: @Composable () -> Unit
) {
    val uiSettings: UiSettings = koinInject()
    val themeRepository: ThemeRepository = koinInject()

    val themeColors by remember {
        uiSettings.colorsId.flatMapLatest {
            themeRepository.colors.getOrDefault(it)
        }
    }.collectAsState(null)

    val themeShapes by remember {
        uiSettings.shapesId.flatMapLatest {
            themeRepository.shapes.getOrDefault(it)
        }
    }.collectAsState(null)

    val themeTypography by remember {
        uiSettings.typographyId.flatMapLatest {
            themeRepository.typographies.getOrDefault(it)
        }
    }.collectAsState(null)

    val colorSchemePref by remember { uiSettings.colorScheme }.collectAsState(
        ColorSchemePref.System
    )
    val darkTheme =
        colorSchemePref == ColorSchemePref.Dark || colorSchemePref == ColorSchemePref.System && isSystemInDarkTheme()

    if (themeColors == null || themeShapes == null || themeTypography == null) {
        return
    }

    val schemes = LauncherColorSchemes(
        light = lightColorSchemeOf(themeColors!!),
        dark = darkColorSchemeOf(themeColors!!),
        darkTheme = darkTheme,
    )
    val colorScheme = schemes.theme

    val shapes = shapesOf(themeShapes!!)
    val typography = typographyOf(themeTypography!!)

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalLauncherColorSchemes provides schemes,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}


/**
 * Both schemes of the zone's colours, not only the one the theme picked: glass
 * shows the wallpaper through it, so text on glass needs the scheme that
 * matches the wallpaper, which is the other one whenever theme and wallpaper
 * disagree in brightness (#242).
 */
@Immutable
data class LauncherColorSchemes(
    val light: ColorScheme,
    val dark: ColorScheme,
    val darkTheme: Boolean,
) {
    /** The scheme the theme picked. */
    val theme: ColorScheme get() = if (darkTheme) dark else light
}

/** Null outside [LauncherTheme] (previews, tests): glass then keeps the ambient scheme. */
val LocalLauncherColorSchemes = staticCompositionLocalOf<LauncherColorSchemes?> { null }

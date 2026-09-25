package de.mm20.launcher2.ui.locals

import android.icu.util.Calendar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.preferences.TimeFormat
import de.mm20.launcher2.preferences.ui.CardStyle
import de.mm20.launcher2.preferences.ui.GridSettings
import de.mm20.launcher2.ui.theme.WallpaperColors

val LocalWindowSize = compositionLocalOf { Size(0f, 0f) }

val LocalBackStack = staticCompositionLocalOf< NavBackStack<NavKey>> { throw IllegalStateException("No backstack provided") }

val LocalCardStyle = compositionLocalOf { CardStyle() }

/**
 * Whether search results offer "Pin to favorites". Always on in the launcher:
 * pins feed the dock as well as search's favorites row, so hiding that row
 * (`search.favorites`, #3 D5) must not take the action away. Kept as the
 * seam a preview or a test can switch off.
 */
val LocalFavoritesEnabled = compositionLocalOf { true }

val LocalShowAppDetails = compositionLocalOf { false }

val LocalGridSettings = compositionLocalOf { GridSettings() }

val LocalTimeFormat = staticCompositionLocalOf { TimeFormat.TwentyFourHour }
val LocalCalendarSystems = staticCompositionLocalOf<List<Calendar?>> { listOf(null, null) }
val LocalCalendarSystemIds = staticCompositionLocalOf<List<String?>> { listOf(null, null) }

val LocalSnackbarHostState = compositionLocalOf { SnackbarHostState() }

val LocalDarkTheme = compositionLocalOf { false }

/**
 * Workaround a bug in Jetpack Compose which incorrectly places popups
 * that are nested inside other popups.
 */
val LocalWindowPosition = compositionLocalOf { 0f }

val LocalWallpaperColors = compositionLocalOf { WallpaperColors() }

val LocalPreferDarkContentOverWallpaper = compositionLocalOf { false }
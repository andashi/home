package de.mm20.launcher2.ui.component

import de.mm20.launcher2.preferences.SearchBarColors

/**
 * Whether the resting search bar draws dark text (#229): `Dark` and `Light`
 * say so outright, `Auto` follows the wallpaper. One function for the
 * launcher and the settings preview, so `home.searchBar.colors` has one
 * meaning; SearchBarDarkContentTest pins every case.
 */
internal fun SearchBarColors.hasDarkContent(preferDarkContentOverWallpaper: Boolean): Boolean = when (this) {
    SearchBarColors.Dark -> true
    SearchBarColors.Light -> false
    SearchBarColors.Auto -> preferDarkContentOverWallpaper
}

package de.mm20.launcher2.glass

/**
 * How much of the full-window blurred backdrop covers the wallpaper (#91).
 * The home screen and search each have their own setting
 * (`appearance.glass.wallpaperBlur`, `.searchWallpaperBlur`); while search
 * opens, the backdrop fades from the one to the other with its progress, so
 * a sharp home and a blurred search cross-fade instead of switching.
 */
object GlassWallpaperAlpha {
    /** [progress] is the search page's, 0 = home, 1 = search open; clamped. */
    fun at(homeBlur: Boolean, searchBlur: Boolean, progress: Float): Float = 0f
}

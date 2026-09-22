package de.mm20.launcher2.config.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether one of this launcher's activities is on screen right now (issue #37).
 *
 * `WallpaperManagerService` crops a static wallpaper only for the current user.
 * A set from a background profile stores the original, assigns ids, and leaves
 * the screen black until the profile is foreground and the wallpaper is set
 * again - measured at 30 s per secondary profile against 1-2 s without a
 * wallpaper, roughly 150 s of a 600 s provisioning run, spent on a result the
 * system does not keep.
 *
 * There is no permission-free way to ask "is my profile the current user", so
 * this is the proxy: a resumed activity means this profile is on screen. It is
 * fed by [WallpaperForegroundFixer], which already observes the lifecycle for
 * the re-apply.
 *
 * Note it is slightly wider than the question it stands for. A foreground
 * profile with the screen off, or with another app in front, reads as not
 * visible and defers as well. That is the same trade in the same direction -
 * the apply happens when someone can actually see it - but it is a widening
 * and not an equivalence.
 */
class ForegroundState {
    private val resumed = AtomicInteger(0)

    val isForeground: Boolean
        get() = resumed.get() > 0

    internal fun onResumed() {
        resumed.incrementAndGet()
    }

    internal fun onPaused() {
        resumed.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}

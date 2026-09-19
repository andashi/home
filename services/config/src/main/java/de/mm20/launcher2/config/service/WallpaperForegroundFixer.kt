package de.mm20.launcher2.config.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fork addition (issue #22): a wallpaper set while the profile was in the
 * background is stored by the system but never cropped or drawn until the
 * profile is current. Provisioning cannot foreground-switch by design, so the
 * launcher does the second set itself: whenever one of its activities
 * resumes, the profile is current, and [WallpaperStore.ensureRendered]
 * re-applies the recorded wallpaper if the system has no rendered file for
 * it. Cheap when nothing is pending (one file-descriptor probe), a no-op
 * without a config-managed wallpaper.
 */
class WallpaperForegroundFixer(
    context: Context,
    private val wallpapers: WallpaperStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Application.ActivityLifecycleCallbacks {

    private val app = context.applicationContext as? Application
    private var job: Job? = null

    fun start() {
        app?.registerActivityLifecycleCallbacks(this)
            ?: Log.w(TAG, "No Application context; wallpaper foreground fix disabled")
    }

    fun stop() {
        app?.unregisterActivityLifecycleCallbacks(this)
        job?.cancel()
    }

    override fun onActivityResumed(activity: Activity) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (wallpapers.ensureRendered()) {
                    Log.i(TAG, "Re-applied the config wallpaper now that the profile is in the foreground")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wallpaper foreground check failed", e)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TAG = "WallpaperForegroundFix"
    }
}

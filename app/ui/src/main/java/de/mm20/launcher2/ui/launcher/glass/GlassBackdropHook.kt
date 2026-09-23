package de.mm20.launcher2.ui.launcher.glass

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * A measurement aid for #74, not a feature: in a debuggable build, when
 * `files/glass-backdrop-hook` exists, grid cards draw the backdrop region
 * behind their content, so the frame time of the backdrop alone can be
 * recorded before #75 draws the full glass stack.
 *
 *     adb shell run-as <applicationId> touch files/glass-backdrop-hook
 *
 * Release builds are not debuggable, so neither `run-as` nor this check can
 * switch anything there. Read once per process.
 */
object GlassBackdropHook {
    const val FileName = "glass-backdrop-hook"

    @Volatile
    private var cached: Boolean? = null

    fun enabled(context: Context): Boolean = cached ?: run {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        (debuggable && File(context.filesDir, FileName).exists()).also { cached = it }
    }
}

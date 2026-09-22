package de.mm20.launcher2.homegrid

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import androidx.core.content.getSystemService

/**
 * The real [FormFactorDetector]: asks the package manager for the hinge
 * angle feature and the display manager for the built-in displays, and
 * hands both to [FormFactorRule].
 *
 * The built-in displays are `getDisplays(DISPLAY_CATEGORY_BUILT_IN_DISPLAYS)`,
 * the public category, and it matters which call: measured on the foldable
 * GrapheneOS instance (2026-09-22, opened), plain `getDisplays()` lists one
 * display because the cover is disabled while the inner panel is on, while
 * the built-in category lists both. `Display.getType()` would say the same
 * but is not public API. Robolectric's display shadow answers nothing for
 * the category, so tests inject [builtInDisplays].
 */
class AndroidFormFactorDetector(
    private val context: Context,
    private val builtInDisplays: (DisplayManager) -> Int = { manager ->
        manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS).size
    },
) : FormFactorDetector {
    override fun detect(): FormFactor {
        val hasHinge = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        val builtIn = context.getSystemService<DisplayManager>()?.let(builtInDisplays) ?: 1
        val formFactor = FormFactorRule.classify(hasHinge, builtIn)
        // Once per detection, so a wrong answer on a device is diagnosable
        // from logcat; the fold instance of the L4 harness showed none of
        // the expected signals at first.
        Log.i(Tag, "$formFactor: hinge=$hasHinge builtInDisplays=$builtIn")
        return formFactor
    }

    companion object {
        private const val Tag = "FormFactorDetector"
    }
}

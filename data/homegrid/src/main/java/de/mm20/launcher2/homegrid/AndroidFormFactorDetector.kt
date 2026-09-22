package de.mm20.launcher2.homegrid

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.content.getSystemService

/**
 * The real [FormFactorDetector]: asks the package manager for the hinge
 * angle feature and the display manager for the built-in displays, and
 * hands both to [FormFactorRule].
 *
 * "Built-in" is every display that is not a presentation display
 * ([Display.FLAG_PRESENTATION]: external screens, wireless displays and
 * the virtual displays made for them). `Display.getType()` would say it
 * directly but is not public API, and the public
 * `DISPLAY_CATEGORY_BUILT_IN_DISPLAYS` category is not honoured by
 * Robolectric's display shadow, which the detector test relies on.
 */
class AndroidFormFactorDetector(
    private val context: Context,
) : FormFactorDetector {
    override fun detect(): FormFactor {
        val hasHinge = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        val builtIn = context.getSystemService<DisplayManager>()?.displays
            ?.count { it.flags and Display.FLAG_PRESENTATION == 0 } ?: 1
        return FormFactorRule.classify(hasHinge, builtIn)
    }
}

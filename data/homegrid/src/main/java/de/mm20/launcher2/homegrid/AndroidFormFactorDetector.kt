package de.mm20.launcher2.homegrid

import android.content.Context
import android.content.pm.PackageManager

/**
 * The real [FormFactorDetector]: asks the package manager whether the device
 * declares a hinge angle sensor and hands the answer to [FormFactorRule].
 */
class AndroidFormFactorDetector(
    private val context: Context,
) : FormFactorDetector {
    override fun detect(): FormFactor {
        val hasHinge = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        return FormFactorRule.classify(hasHinge)
    }
}

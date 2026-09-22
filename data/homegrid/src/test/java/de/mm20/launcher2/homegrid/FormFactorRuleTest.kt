package de.mm20.launcher2.homegrid

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FormFactorRuleTest {

    @Test
    fun `a hinge makes a fold`() {
        assertEquals(FormFactor.Fold, FormFactorRule.classify(hasHingeAngleSensor = true))
    }

    @Test
    fun `no hinge makes a phone`() {
        assertEquals(FormFactor.Phone, FormFactorRule.classify(hasHingeAngleSensor = false))
    }

    @Test
    fun `each form factor names its layout`() {
        assertEquals(HomeGridLayouts.Phone, FormFactor.Phone.layout)
        assertEquals(HomeGridLayouts.Fold, FormFactor.Fold.layout)
    }

    @Test
    fun `the Android detector reads the hinge angle system feature`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = AndroidFormFactorDetector(context)

        assertEquals(FormFactor.Phone, detector.detect())

        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE, true)
        assertEquals(FormFactor.Fold, detector.detect())
    }
}

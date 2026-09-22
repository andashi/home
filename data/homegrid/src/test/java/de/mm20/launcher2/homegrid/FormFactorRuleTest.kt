package de.mm20.launcher2.homegrid

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
class FormFactorRuleTest {

    @Test
    fun `a hinge makes a fold`() {
        assertEquals(FormFactor.Fold, FormFactorRule.classify(hasHingeAngleSensor = true, builtInDisplays = 1))
    }

    @Test
    fun `two built-in displays make a fold even without a hinge feature`() {
        // The foldable GrapheneOS emulator declares no hinge sensor but has
        // both panels as built-in displays (one of them off while closed).
        assertEquals(FormFactor.Fold, FormFactorRule.classify(hasHingeAngleSensor = false, builtInDisplays = 2))
    }

    @Test
    fun `no hinge and one built-in display make a phone`() {
        assertEquals(FormFactor.Phone, FormFactorRule.classify(hasHingeAngleSensor = false, builtInDisplays = 1))
        assertEquals(FormFactor.Phone, FormFactorRule.classify(hasHingeAngleSensor = false, builtInDisplays = 0))
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

    @Test
    fun `the Android detector counts built-in displays`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = AndroidFormFactorDetector(context)
        assertEquals(FormFactor.Phone, detector.detect())

        // A second panel, the cover of a foldable; the test device has no
        // hinge feature, like the emulator.
        val cover = ShadowDisplayManager.addDisplay("w412dp-h923dp")
        try {
            assertEquals(FormFactor.Fold, detector.detect())
        } finally {
            ShadowDisplayManager.removeDisplay(cover)
        }
        assertEquals(FormFactor.Phone, detector.detect())
    }
}

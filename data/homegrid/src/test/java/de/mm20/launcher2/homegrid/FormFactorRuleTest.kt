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
        val detector = AndroidFormFactorDetector(context) { 1 }

        assertEquals(FormFactor.Phone, detector.detect())

        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE, true)
        assertEquals(FormFactor.Fold, detector.detect())
    }

    @Test
    fun `the Android detector counts the built-in displays it is given`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The production source is the public built-in category, which
        // Robolectric's shadow does not serve; the count plumbing is what is
        // pinned here, the category was measured on the foldable instance.
        var displays = 1
        val detector = AndroidFormFactorDetector(context) { displays }

        assertEquals(FormFactor.Phone, detector.detect())

        displays = 2
        assertEquals(FormFactor.Fold, detector.detect())
    }

    @Test
    fun `the Android detector's default source is the built-in display category`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric answers the category with nothing, plain getDisplays()
        // with every added display: a detector that used the latter would
        // say Fold here, and on a device whose cover is disabled while open
        // it would never see two.
        val cover = ShadowDisplayManager.addDisplay("w412dp-h923dp")
        try {
            assertEquals(FormFactor.Phone, AndroidFormFactorDetector(context).detect())
        } finally {
            ShadowDisplayManager.removeDisplay(cover)
        }
    }
}

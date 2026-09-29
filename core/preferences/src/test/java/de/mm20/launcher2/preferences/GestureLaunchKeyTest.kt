package de.mm20.launcher2.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.preferences.ui.GestureSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A gesture follows the item it launches to a new key (#237). A contact's
 * stored key moves when its lookup key changes; a gesture naming the old key
 * resolves to an item carrying the new one and launched nothing.
 */
@RunWith(RobolectricTestRunner::class)
class GestureLaunchKeyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every gesture launching the old key launches the new one`() = runTest {
        seedSettingsFile(
            context,
            LauncherSettingsData(
                gesturesSwipeLeft = GestureAction.Launch("contact://0r1-A"),
                gesturesLongPress = GestureAction.Launch("contact://0r1-A"),
                gesturesDoubleTap = GestureAction.Launch("app://com.example.mail"),
                gesturesSwipeUp = GestureAction.Search,
            ),
        )
        val gestures = GestureSettings(LauncherDataStore(context))

        gestures.replaceLaunchKey("contact://0r1-A", "contact://0r1-A.0r2-B")

        assertEquals(GestureAction.Launch("contact://0r1-A.0r2-B"), gestures.swipeLeft.first())
        assertEquals(GestureAction.Launch("contact://0r1-A.0r2-B"), gestures.longPress.first())
        // Controls: another launch and a non-launch are left as they were.
        assertEquals(GestureAction.Launch("app://com.example.mail"), gestures.doubleTap.first())
        assertEquals(GestureAction.Search, gestures.swipeUp.first())
    }
}

package de.mm20.launcher2.ui.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #120: fold, unfold and rotation change the window's size and layout
 * (measured on the Fold emulator: config changes 0xd00). The launcher
 * handles those itself, as Launcher3 does, so the activity and its
 * composition survive and nothing (icons, the backdrop) is rebuilt.
 */
@RunWith(AndroidJUnit4::class)
class LauncherActivityConfigChangesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val info = context.packageManager.getActivityInfo(ComponentName(context, LauncherActivity::class.java), 0)

    @Test
    fun `fold, unfold and rotation do not recreate the launcher`() {
        val handled = ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_ORIENTATION
        assertEquals("configChanges ${Integer.toHexString(info.configChanges)}", handled, info.configChanges and handled)
    }

    /**
     * Control: dark mode and a density change still recreate it, so the
     * theme and every widget's RemoteViews are inflated anew.
     */
    @Test
    fun `dark mode and density still recreate it`() {
        assertTrue(info.configChanges and ActivityInfo.CONFIG_UI_MODE == 0)
        assertTrue(info.configChanges and ActivityInfo.CONFIG_DENSITY == 0)
    }
}

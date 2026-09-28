package de.mm20.launcher2.appshortcuts

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A config activity's result is what another app put there, and a rejected
 * one used to reach logcat with its intent and name (#15). The log says which
 * extra was missing and nothing about the app: a launcher must not write an
 * app inventory to logcat. Each case first finds the rejection line, so the
 * absence of the payload is checked on a path that ran.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyShortcutLoggingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    @Test
    fun `a result without a name logs the missing extra, not the intent`() {
        val target = Intent(Intent.ACTION_VIEW)
            .setClassName("com.example.inventory", "com.example.inventory.Secret")
            .putExtra("token", "s3cr3t-token")
        val result = Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)

        assertNull(LegacyShortcut.fromConfigActivityResult(context, result))

        val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }
        val rejection = lines.single { "missing required extras" in it }
        assertTrue(rejection, Intent.EXTRA_SHORTCUT_NAME in rejection)
        assertTrue(rejection, Intent.EXTRA_SHORTCUT_INTENT !in rejection)
        // "Secret" alone too: a log could print the short class name without the package.
        for (payload in listOf("com.example.inventory", "s3cr3t-token", "Secret")) {
            assertTrue("$payload in $lines", lines.none { payload in it })
        }
    }

    @Test
    fun `a result without an intent logs the missing extra, not the name`() {
        val result = Intent().putExtra(Intent.EXTRA_SHORTCUT_NAME, "My-bank-login")

        assertNull(LegacyShortcut.fromConfigActivityResult(context, result))

        val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }
        val rejection = lines.single { "missing required extras" in it }
        assertTrue(rejection, Intent.EXTRA_SHORTCUT_INTENT in rejection)
        assertTrue(rejection, Intent.EXTRA_SHORTCUT_NAME !in rejection)
        assertTrue(lines.toString(), lines.none { "My-bank-login" in it })
    }
}

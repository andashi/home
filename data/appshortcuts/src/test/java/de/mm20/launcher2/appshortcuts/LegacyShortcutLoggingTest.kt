package de.mm20.launcher2.appshortcuts

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    private fun rejectionLines(): List<String> =
        ShadowLog.getLogs().map { it.msg }.filter { "missing required extras" in it }

    private fun allLines(): List<String> = ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }

    @Test
    fun `a result without a name logs the missing extra, not the intent`() {
        val target = Intent(Intent.ACTION_VIEW)
            .setClassName("com.example.inventory", "com.example.inventory.Secret")
            .putExtra("token", "s3cr3t-token")
        val result = Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)

        assertNull(LegacyShortcut.fromConfigActivityResult(context, result))

        val rejection = rejectionLines()
        assertEquals(allLines().toString(), 1, rejection.size)
        assertTrue(rejection.single(), Intent.EXTRA_SHORTCUT_NAME in rejection.single())
        assertTrue(rejection.single(), Intent.EXTRA_SHORTCUT_INTENT !in rejection.single())
        for (payload in listOf("com.example.inventory", "s3cr3t-token", "Secret")) {
            assertTrue("$payload in ${allLines()}", allLines().none { payload in it })
        }
    }

    @Test
    fun `a result without an intent logs the missing extra, not the name`() {
        val result = Intent().putExtra(Intent.EXTRA_SHORTCUT_NAME, "My-bank-login")

        assertNull(LegacyShortcut.fromConfigActivityResult(context, result))

        val rejection = rejectionLines()
        assertEquals(allLines().toString(), 1, rejection.size)
        assertTrue(rejection.single(), Intent.EXTRA_SHORTCUT_INTENT in rejection.single())
        assertTrue(rejection.single(), Intent.EXTRA_SHORTCUT_NAME !in rejection.single())
        assertTrue(allLines().toString(), allLines().none { "My-bank-login" in it })
    }
}

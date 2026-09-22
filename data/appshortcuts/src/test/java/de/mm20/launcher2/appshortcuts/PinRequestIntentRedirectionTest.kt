package de.mm20.launcher2.appshortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for andashi/home#5.
 *
 * `AddItemActivity` is exported with an intent filter on
 * `android.content.pm.action.CONFIRM_PIN_SHORTCUT`, which is only protected as
 * a broadcast action - as an activity action any app can start it with extras
 * of its choosing. The launcher used to fall back to reading
 * `EXTRA_SHORTCUT_INTENT` off that intent and to persist it as a favorite,
 * which it would later start under its own identity and permissions.
 *
 * The two doors are now separate functions, and these tests pin the difference:
 * a pin request never yields a shortcut from legacy extras, a config activity's
 * result still does, and what the second one stores is sanitised.
 */
@RunWith(RobolectricTestRunner::class)
class PinRequestIntentRedirectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The shape a malicious app would send: legacy extras, no pin request. */
    private fun forgedPinRequest(
        target: Intent = Intent(Intent.ACTION_VIEW).setPackage("com.example.victim"),
        label: String = "Harmless looking",
    ) = Intent("android.content.pm.action.CONFIRM_PIN_SHORTCUT").apply {
        putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
        putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
    }

    @Test
    fun `a pin request carrying legacy extras yields nothing`() {
        assertNull(appShortcutFromPinRequest(context, forgedPinRequest()))
    }

    @Test
    fun `a pin request naming an internal launcher component yields nothing`() {
        val internal = Intent().setComponent(
            ComponentName(context.packageName, "${context.packageName}.SomeInternalActivity")
        )
        assertNull(appShortcutFromPinRequest(context, forgedPinRequest(target = internal)))
    }

    @Test
    fun `the config activity result still accepts the legacy shape`() {
        val shortcut = appShortcutFromConfigActivityResult(context, forgedPinRequest())
        assertNotNull("the favorites editor must keep working", shortcut)
        assertEquals("Harmless looking", shortcut!!.label)
    }

    @Test
    fun `a config activity result naming the launcher itself is refused`() {
        val viaComponent = Intent().setComponent(
            ComponentName(context.packageName, "${context.packageName}.SomeInternalActivity")
        )
        assertNull(appShortcutFromConfigActivityResult(context, forgedPinRequest(target = viaComponent)))

        val viaPackage = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        assertNull(appShortcutFromConfigActivityResult(context, forgedPinRequest(target = viaPackage)))
    }

    @Test
    fun `uri grant flags are stripped from a stored shortcut`() {
        val grabby = Intent(Intent.ACTION_VIEW).setPackage("com.example.other").apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shortcut = appShortcutFromConfigActivityResult(context, forgedPinRequest(target = grabby))
                as LegacyShortcut

        assertEquals(
            "no grant flag may survive",
            0,
            shortcut.intent.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
        )
        assertEquals(
            "unrelated flags are left alone",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            shortcut.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK
        )
    }

    @Test
    fun `an already stored shortcut is sanitised on the way out`() = runBlocking {
        val deserializer = LegacyShortcutDeserializer(context)

        val hostile = Intent().setComponent(
            ComponentName(context.packageName, "${context.packageName}.SomeInternalActivity")
        )
        assertNull(
            "a favorite planted before the fix must not come back",
            deserializer.deserialize(serialized(hostile))
        )

        val benign = Intent(Intent.ACTION_VIEW).setPackage("com.example.other").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val restored = deserializer.deserialize(serialized(benign)) as LegacyShortcut
        assertEquals(
            0,
            restored.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    /**
     * The quieter way to name a target: with no explicit component,
     * PackageManager resolves an Intent through its selector and takes the
     * component from there, so a selector reaches the same non-exported
     * activity that a component would.
     */
    @Test
    fun `a selector naming the launcher is refused`() {
        val viaSelector = Intent(Intent.ACTION_MAIN).apply {
            selector = Intent().setComponent(
                ComponentName(context.packageName, "${context.packageName}.SomeInternalActivity")
            )
        }
        assertNull(appShortcutFromConfigActivityResult(context, forgedPinRequest(target = viaSelector)))
        assertNull(appShortcutFromPinRequest(context, forgedPinRequest(target = viaSelector)))
    }

    @Test
    fun `a selector naming the launcher is refused on the way out`() = runBlocking {
        val viaSelector = Intent(Intent.ACTION_MAIN).apply {
            selector = Intent().setPackage(context.packageName)
        }
        val uri = viaSelector.toUri(0)
        assertTrue("the selector must survive the round trip, or this asserts nothing",
            Intent.parseUri(uri, 0).selector?.`package` == context.packageName)

        assertNull(LegacyShortcutDeserializer(context).deserialize(serialized(viaSelector)))
    }

    @Test
    fun `a shortcut into another app keeps its selector`() {
        val elsewhere = Intent(Intent.ACTION_MAIN).apply {
            selector = Intent().setPackage("com.example.other")
        }
        val shortcut = appShortcutFromConfigActivityResult(context, forgedPinRequest(target = elsewhere))
                as LegacyShortcut
        assertEquals("com.example.other", shortcut.intent.selector?.`package`)
    }

    private fun serialized(intent: Intent): String =
        """{"label":"Stored","intent":"${intent.toUri(0)}"}"""
}

package de.mm20.launcher2.ui.launcher.search.contacts

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A tap on a contact's number (#3 slice 1). Without CALL_PHONE a call cannot
 * start: the system throws, `tryStartActivity` returns false, and the tap did
 * nothing at all. On GrapheneOS a denied permission is the normal path, and it
 * can be revoked after the setting was switched on, so the tap dials instead.
 */
@RunWith(RobolectricTestRunner::class)
class ContactTapTest {

    private val started = mutableListOf<String>()

    private fun starter(callWorks: Boolean): (Intent) -> Boolean = { intent ->
        started += intent.action!!
        intent.action != Intent.ACTION_CALL || callWorks
    }

    @Test
    fun `call on tap without the call permission reaches the dialer`() {
        val reached = callOrDial("+49 30 1234", callOnTap = true, start = starter(callWorks = false))

        assertTrue(reached)
        assertEquals(listOf(Intent.ACTION_CALL, Intent.ACTION_DIAL), started)
    }

    /** Control: with the permission the call starts, and nothing else does. */
    @Test
    fun `call on tap with the call permission calls`() {
        callOrDial("+49 30 1234", callOnTap = true, start = starter(callWorks = true))

        assertEquals(listOf(Intent.ACTION_CALL), started)
    }

    /** Control: switched off, a tap only ever dials. */
    @Test
    fun `without call on tap a tap dials`() {
        callOrDial("+49 30 1234", callOnTap = false, start = starter(callWorks = true))

        assertEquals(listOf(Intent.ACTION_DIAL), started)
    }
}

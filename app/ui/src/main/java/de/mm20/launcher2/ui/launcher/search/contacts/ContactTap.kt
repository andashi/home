package de.mm20.launcher2.ui.launcher.search.contacts

import android.content.Intent
import androidx.core.net.toUri

/**
 * A tap on a contact's [number]: a call when [callOnTap] is set, the dialer
 * otherwise, and the dialer as well when the call cannot start. Without
 * CALL_PHONE the system refuses the call and the tap would do nothing; on
 * GrapheneOS a denied or revoked permission is the normal path, and the
 * dialer leaves the call to the person (#3 slice 1). Returns whether an
 * activity started.
 */
internal fun callOrDial(number: String, callOnTap: Boolean, start: (Intent) -> Boolean): Boolean {
    val uri = "tel:$number".toUri()
    if (callOnTap && start(Intent(Intent.ACTION_CALL).setData(uri))) return true
    return start(Intent(Intent.ACTION_DIAL).setData(uri))
}

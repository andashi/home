package de.mm20.launcher2.appshortcuts

import android.util.Log
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager

/**
 * Run a `LauncherApps` call that only the default launcher may make, and
 * return [unavailable] instead of throwing when this launcher may not.
 *
 * `LauncherApps` answers shortcut queries for the holder of the HOME role and
 * nobody else. The role can move at any moment - provisioning does exactly
 * that when it hands HOME to another build - and the launcher finds out by
 * being told "Caller can't access shortcut information" mid-query. Checking
 * `hasShortcutHostPermission()` first narrows the window but cannot close it,
 * so the call has to survive losing the race as well.
 *
 * Passing [permissionsManager] also makes the loss recoverable rather than
 * merely survivable. The shortcut flows are keyed on
 * [PermissionGroup.AppShortcuts]; if the published state keeps claiming the
 * permission that was just refused, it never changes back either, and nothing
 * re-queries when the role returns. Re-reading it here flips the flow to false
 * now and lets `onResume` flip it to true later, which is what makes
 * collectors run again.
 *
 * An [IllegalStateException] means something else - a locked or stopped
 * profile - and degrades without touching the permission state.
 */
internal fun <T> queryShortcutHost(
    unavailable: T,
    permissionsManager: PermissionsManager? = null,
    query: () -> T,
): T {
    return try {
        query()
    } catch (e: SecurityException) {
        Log.w("MM20", "Shortcut query refused; this launcher does not hold the HOME role", e)
        permissionsManager?.recheckPermission(PermissionGroup.AppShortcuts)
        unavailable
    } catch (e: IllegalStateException) {
        unavailable
    }
}

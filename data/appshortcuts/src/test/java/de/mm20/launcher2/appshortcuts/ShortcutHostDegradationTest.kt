package de.mm20.launcher2.appshortcuts

import androidx.appcompat.app.AppCompatActivity
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for andashi/home#30.
 *
 * `LauncherApps` answers shortcut queries only for the holder of the HOME
 * role. When provisioning moved that role to another build, the running
 * launcher died with `SecurityException: Caller can't access shortcut
 * information` - thrown inside a `combine` that is shared eagerly into a scope
 * with no exception handler, so it took the process with it. A launcher that
 * is no longer the default has to go quiet, not down.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutHostDegradationTest {

    private class RecordingPermissionsManager : PermissionsManager {
        val rechecked = mutableListOf<PermissionGroup>()

        override fun recheckPermission(permissionGroup: PermissionGroup) {
            rechecked += permissionGroup
        }

        override fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup) {}
        override fun checkPermissionOnce(permissionGroup: PermissionGroup) = true
        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray,
        ) {}

        override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> = flowOf(true)
        override fun reportNotificationListenerState(running: Boolean) {}
        override fun reportAccessibilityServiceState(running: Boolean) {}
    }

    @Test
    fun `losing the role degrades instead of propagating`() {
        val permissions = RecordingPermissionsManager()

        val result = queryShortcutHost(
            unavailable = emptyList<String>(),
            permissionsManager = permissions,
        ) {
            throw SecurityException("Caller can't access shortcut information")
        }

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `losing the role is reported, so the permission flow can change back`() {
        val permissions = RecordingPermissionsManager()

        queryShortcutHost(unavailable = null, permissionsManager = permissions) {
            throw SecurityException("Caller can't access shortcut information")
        }

        assertEquals(
            "without this the state keeps claiming the permission and nothing re-queries",
            listOf(PermissionGroup.AppShortcuts),
            permissions.rechecked,
        )
    }

    /** A locked or stopped profile is not a lost role, and must not be reported as one. */
    @Test
    fun `a locked profile degrades without touching the permission state`() {
        val permissions = RecordingPermissionsManager()

        val result = queryShortcutHost(unavailable = null, permissionsManager = permissions) {
            throw IllegalStateException("user is locked")
        }

        assertNull(result)
        assertTrue(permissions.rechecked.isEmpty())
    }

    @Test
    fun `a call that succeeds reports nothing and returns its value`() {
        val permissions = RecordingPermissionsManager()

        val result = queryShortcutHost(unavailable = null, permissionsManager = permissions) {
            listOf("one", "two")
        }

        assertEquals(listOf("one", "two"), result)
        assertTrue(permissions.rechecked.isEmpty())
    }

    /** `delete()` and the config-activity IntentSender have no manager to report to. */
    @Test
    fun `a call without a permissions manager still degrades`() {
        val result = queryShortcutHost(unavailable = "fallback") {
            throw SecurityException("Caller can't access shortcut information")
        }

        assertEquals("fallback", result)
    }
}

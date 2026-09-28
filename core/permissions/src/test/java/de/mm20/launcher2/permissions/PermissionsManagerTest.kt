package de.mm20.launcher2.permissions

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.LauncherApps
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests for [PermissionsManagerImpl.recheckPermission], added with andashi/home#30.
 *
 * The permission states are published as flows and read only at construction
 * and in [PermissionsManagerImpl.onResume]. That is fine for a permission
 * someone grants in Settings and then comes back from, and wrong for one that
 * disappears while the launcher runs: the HOME role can move to another
 * package at any moment, and the first thing to notice is a `LauncherApps`
 * call that throws. `recheckPermission` is how that caller tells the state to
 * catch up.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setShortcutHost(granted: Boolean) {
        shadowOf(context.getSystemService<LauncherApps>()!!).setHasShortcutHostPermission(granted)
    }

    private fun setHomeRole(held: Boolean) {
        val roles = shadowOf(context.getSystemService<RoleManager>()!!)
        if (held) roles.addHeldRole(RoleManager.ROLE_HOME) else roles.removeHeldRole(RoleManager.ROLE_HOME)
    }

    private suspend fun PermissionsManager.state(group: PermissionGroup) = hasPermission(group).first()

    @Test
    fun `the published state goes stale on its own, which is the problem`() = runTest {
        setShortcutHost(true)
        val manager = PermissionsManagerImpl(context)
        assertEquals(true, manager.state(PermissionGroup.AppShortcuts))

        setShortcutHost(false)

        assertEquals(
            "nothing polls, so the flow still claims a permission the system has withdrawn",
            true,
            manager.state(PermissionGroup.AppShortcuts),
        )
    }

    @Test
    fun `recheckPermission publishes the withdrawal`() = runTest {
        setShortcutHost(true)
        val manager = PermissionsManagerImpl(context)

        setShortcutHost(false)
        manager.recheckPermission(PermissionGroup.AppShortcuts)

        assertEquals(false, manager.state(PermissionGroup.AppShortcuts))
    }

    /**
     * The recovery half: the value has to have really changed, or the flow
     * emits nothing when the role returns and nothing re-queries.
     */
    @Test
    fun `recheckPermission publishes the return as well`() = runTest {
        setShortcutHost(true)
        val manager = PermissionsManagerImpl(context)
        setShortcutHost(false)
        manager.recheckPermission(PermissionGroup.AppShortcuts)
        assertEquals(false, manager.state(PermissionGroup.AppShortcuts))

        setShortcutHost(true)
        manager.recheckPermission(PermissionGroup.AppShortcuts)

        assertEquals(true, manager.state(PermissionGroup.AppShortcuts))
    }

    @Test
    fun `recheckPermission re-reads the HOME role too`() = runTest {
        setHomeRole(true)
        val manager = PermissionsManagerImpl(context)
        assertEquals(true, manager.state(PermissionGroup.ManageProfiles))

        setHomeRole(false)
        manager.recheckPermission(PermissionGroup.ManageProfiles)

        assertEquals(false, manager.state(PermissionGroup.ManageProfiles))
    }

    /**
     * Notifications and accessibility are reported by the service that
     * implements them; there is nothing to ask the system about, and a recheck
     * must not overwrite what the service said with a guess.
     */
    @Test
    fun `recheckPermission leaves service-reported states alone`() = runTest {
        val manager = PermissionsManagerImpl(context)
        manager.reportNotificationListenerState(true)
        manager.reportAccessibilityServiceState(true)

        manager.recheckPermission(PermissionGroup.Notifications)
        manager.recheckPermission(PermissionGroup.Accessibility)

        assertEquals(true, manager.state(PermissionGroup.Notifications))
        assertEquals(true, manager.state(PermissionGroup.Accessibility))
    }

    @Test
    fun `onResume keeps re-reading both system-owned groups`() = runTest {
        setShortcutHost(true)
        setHomeRole(true)
        val manager = PermissionsManagerImpl(context)

        setShortcutHost(false)
        setHomeRole(false)
        manager.onResume()

        assertEquals(false, manager.state(PermissionGroup.AppShortcuts))
        assertEquals(false, manager.state(PermissionGroup.ManageProfiles))
    }

    /**
     * A runtime permission granted in Settings while the launcher runs reaches
     * the published state when it comes back, not only one granted through its
     * own dialog: a contact favorite that was Unknown resolves again on that
     * change (review on andashi/home#241). Revoking kills the process, so the
     * grant is the case that matters.
     */
    @Test
    fun `onResume re-reads the runtime permissions granted in Settings`() = runTest {
        val manager = PermissionsManagerImpl(context)
        assertEquals(false, manager.state(PermissionGroup.Contacts))
        assertEquals(false, manager.state(PermissionGroup.Call))

        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        manager.onResume()

        assertEquals(true, manager.state(PermissionGroup.Contacts))
        assertEquals(true, manager.state(PermissionGroup.Call))
    }

    /** onResume goes through recheckPermission, so the service-reported groups stay as their services said. */
    @Test
    fun `onResume leaves service-reported states alone`() = runTest {
        val manager = PermissionsManagerImpl(context)
        manager.reportNotificationListenerState(true)
        manager.reportAccessibilityServiceState(true)

        manager.onResume()

        assertEquals(true, manager.state(PermissionGroup.Notifications))
        assertEquals(true, manager.state(PermissionGroup.Accessibility))
    }

    // ---- enabled in the system, whether or not the service has connected (#140) ----

    private fun setSecure(key: String, value: String?) {
        android.provider.Settings.Secure.putString(context.contentResolver, key, value)
    }

    private val listeners = "enabled_notification_listeners"
    private val services = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    /**
     * The trap: the notification listener's and the accessibility service's
     * states start false and flip only when the service connects. A reload
     * right after the process starts would read "not yet known" as "absent"
     * and report a permission the person has granted.
     */
    @Test
    fun `a listener enabled in Settings is enabled before it has connected`() {
        setSecure(listeners, "${context.packageName}/de.mm20.launcher2.notifications.NotificationService")
        val manager = PermissionsManagerImpl(context)

        assertEquals("the connection state, which is the trap", false, manager.checkPermissionOnce(PermissionGroup.Notifications))
        assertEquals(true, manager.checkEnabledInSystem(PermissionGroup.Notifications))
    }

    @Test
    fun `an accessibility service enabled in Settings is enabled before it has connected`() {
        setSecure(services, "com.example.other/.Service:${context.packageName}/de.mm20.launcher2.globalactions.LauncherAccessibilityService")
        val manager = PermissionsManagerImpl(context)

        assertEquals("the connection state, which is the trap", false, manager.checkPermissionOnce(PermissionGroup.Accessibility))
        assertEquals(true, manager.checkEnabledInSystem(PermissionGroup.Accessibility))
    }

    /** Controls: nothing enabled, and another package whose name only starts with ours. */
    @Test
    fun `a listener or service of another package is not ours`() {
        val manager = PermissionsManagerImpl(context)
        setSecure(listeners, null)
        setSecure(services, null)
        assertEquals(false, manager.checkEnabledInSystem(PermissionGroup.Notifications))
        assertEquals(false, manager.checkEnabledInSystem(PermissionGroup.Accessibility))

        setSecure(listeners, "${context.packageName}.debug/.Listener:com.example.other/.Listener")
        setSecure(services, "${context.packageName}.debug/.Service")
        assertEquals(false, manager.checkEnabledInSystem(PermissionGroup.Notifications))
        assertEquals(false, manager.checkEnabledInSystem(PermissionGroup.Accessibility))
    }

    /** The groups the system answers directly need nothing else. */
    @Test
    fun `other groups answer as checkPermissionOnce does`() {
        setShortcutHost(false)
        val manager = PermissionsManagerImpl(context)
        assertEquals(false, manager.checkEnabledInSystem(PermissionGroup.AppShortcuts))

        setShortcutHost(true)
        assertEquals(true, manager.checkEnabledInSystem(PermissionGroup.AppShortcuts))
    }
}

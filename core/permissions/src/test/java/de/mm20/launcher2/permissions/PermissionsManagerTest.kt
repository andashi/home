package de.mm20.launcher2.permissions

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
}

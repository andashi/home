package de.mm20.launcher2.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.ktx.checkPermission
import de.mm20.launcher2.ktx.tryStartActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.net.toUri

interface PermissionsManager {
    fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup)

    /**
     * Check if this permission is granted right now without receiving further updates
     * about the granted state.
     * @return true if the given permission group is fully granted
     */
    fun checkPermissionOnce(permissionGroup: PermissionGroup): Boolean

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    )

    fun onResume() {

    }

    /**
     * Re-read [permissionGroup] from the system and publish the result to
     * [hasPermission].
     *
     * [onResume] already does this for the groups that can change while the
     * launcher runs, which covers someone granting a permission in Settings
     * and coming back. It does not cover one that goes away *without* the
     * launcher being resumed: the HOME role can move to another package at any
     * moment, and the first thing to notice is a call that throws. Whatever
     * catches such a call reports here, so the published state stops claiming
     * a permission the system no longer grants - and because the flow really
     * changes, collectors run again when it comes back.
     */
    fun recheckPermission(permissionGroup: PermissionGroup) {

    }

    fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean>

    /**
     * Special function for the Notification listener to report its status.
     * May not be called by anything else.
     */
    fun reportNotificationListenerState(running: Boolean)

    /**
     * Special function for the accessibility service to report its status.
     * May not be called by anything else.
     */
    fun reportAccessibilityServiceState(running: Boolean)
}

enum class PermissionGroup {
    Contacts,
    Notifications,
    AppShortcuts,
    Accessibility,
    ManageProfiles,
    Call,
}

internal class PermissionsManagerImpl(
    private val context: Context
) : PermissionsManager {

    private val pendingPermissionRequests = mutableSetOf<PermissionGroup>()

    private val contactsPermissionState = MutableStateFlow(
        checkPermissionOnce(PermissionGroup.Contacts)
    )
    private val notificationsPermissionState = MutableStateFlow(false)
    private val accessibilityPermissionState = MutableStateFlow(false)
    private val appShortcutsPermissionState = MutableStateFlow(
        checkPermissionOnce(PermissionGroup.AppShortcuts)
    )
    private val manageProfilesPermissionState = MutableStateFlow(
        checkPermissionOnce(PermissionGroup.ManageProfiles)
    )
    private val callPermissionState = MutableStateFlow(
        checkPermissionOnce(PermissionGroup.Call)
    )

    override fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup) {
        when (permissionGroup) {
            PermissionGroup.Contacts -> {
                ActivityCompat.requestPermissions(
                    context,
                    contactPermissions,
                    permissionGroup.ordinal
                )
            }

            PermissionGroup.Notifications -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    CrashReporter.logException(e)
                }
            }

            PermissionGroup.ManageProfiles,
            PermissionGroup.AppShortcuts -> {
                val roleManager = context.getSystemService<RoleManager>()
                context.startActivityForResult(
                    roleManager!!.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    permissionGroup.ordinal
                )
                pendingPermissionRequests.add(PermissionGroup.AppShortcuts)
            }

            PermissionGroup.Accessibility -> {
                try {
                    context.tryStartActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    pendingPermissionRequests.add(PermissionGroup.Accessibility)
                } catch (e: ActivityNotFoundException) {
                    CrashReporter.logException(e)
                }
            }

            PermissionGroup.Call -> {
                ActivityCompat.requestPermissions(
                    context,
                    callPermissions,
                    permissionGroup.ordinal
                )
            }
        }
    }

    override fun checkPermissionOnce(permissionGroup: PermissionGroup): Boolean {
        return when (permissionGroup) {
            PermissionGroup.Contacts -> {
                contactPermissions.all { context.checkPermission(it) }
            }

            PermissionGroup.Notifications -> {
                notificationsPermissionState.value
            }

            PermissionGroup.AppShortcuts -> {
                context.getSystemService<LauncherApps>()?.hasShortcutHostPermission() == true
            }

            PermissionGroup.ManageProfiles -> {
                context.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_HOME) == true
            }

            PermissionGroup.Accessibility -> {
                accessibilityPermissionState.value
            }

            PermissionGroup.Call -> {
                callPermissions.all { context.checkPermission(it) }
            }
        }
    }

    override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> {
        return when (permissionGroup) {
            PermissionGroup.Contacts -> contactsPermissionState
            PermissionGroup.Notifications -> notificationsPermissionState
            PermissionGroup.AppShortcuts -> appShortcutsPermissionState
            PermissionGroup.Accessibility -> accessibilityPermissionState
            PermissionGroup.ManageProfiles -> manageProfilesPermissionState
            PermissionGroup.Call -> callPermissionState
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val permissionGroup = PermissionGroup.entries.getOrNull(requestCode) ?: return
        val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (permissionGroup) {
            PermissionGroup.Contacts -> contactsPermissionState.value = granted
            PermissionGroup.Notifications -> notificationsPermissionState.value = granted
            PermissionGroup.AppShortcuts -> appShortcutsPermissionState.value = granted
            PermissionGroup.Accessibility -> accessibilityPermissionState.value = granted
            PermissionGroup.ManageProfiles -> manageProfilesPermissionState.value = granted
            PermissionGroup.Call -> callPermissionState.value = granted
        }
    }

    override fun onResume() {
        appShortcutsPermissionState.value = checkPermissionOnce(PermissionGroup.AppShortcuts)
        manageProfilesPermissionState.value = checkPermissionOnce(PermissionGroup.ManageProfiles)
    }

    override fun recheckPermission(permissionGroup: PermissionGroup) {
        val state = when (permissionGroup) {
            PermissionGroup.Contacts -> contactsPermissionState
            PermissionGroup.AppShortcuts -> appShortcutsPermissionState
            PermissionGroup.ManageProfiles -> manageProfilesPermissionState
            PermissionGroup.Call -> callPermissionState
            // Reported by the service that implements them; there is nothing
            // to ask the system about.
            PermissionGroup.Notifications,
            PermissionGroup.Accessibility -> return
        }
        state.value = checkPermissionOnce(permissionGroup)
    }

    override fun reportNotificationListenerState(running: Boolean) {
        notificationsPermissionState.value = running
    }

    override fun reportAccessibilityServiceState(running: Boolean) {
        accessibilityPermissionState.value = running
    }

    companion object {
        private val taskPermissions = arrayOf("org.tasks.permission.READ_TASKS")
        private val contactPermissions = arrayOf(Manifest.permission.READ_CONTACTS)
        private val callPermissions = arrayOf(Manifest.permission.CALL_PHONE)
    }
}

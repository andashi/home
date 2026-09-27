package de.mm20.launcher2.profiles

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "No profiles read yet" is not "no profiles". The config reload that an
 * ingest starts runs right after the process does, and read as an answer,
 * the empty start made every favorite's profile - and every app in it -
 * look absent.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The first refresh waits for this flow's first value. */
    private class HeldPermissions : PermissionsManager {
        val manageProfiles = MutableSharedFlow<Boolean>(replay = 1)
        override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> = manageProfiles
        override fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup) = Unit
        override fun checkPermissionOnce(permissionGroup: PermissionGroup) = false
        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = Unit
        override fun reportNotificationListenerState(running: Boolean) = Unit
        override fun reportAccessibilityServiceState(running: Boolean) = Unit
    }

    @Test
    fun `the profiles say nothing before they have been read`() = runBlocking {
        val permissions = HeldPermissions()
        val manager = ProfileManager(context, permissions)

        assertNull("unlockedProfiles answered before the first refresh", withTimeoutOrNull(500) { manager.unlockedProfiles.first() })
        assertNull("profiles answered before the first refresh", withTimeoutOrNull(500) { manager.profiles.first() })
    }

    /** Control: once read, they answer, whatever the device has. */
    @Test
    fun `the profiles answer once they have been read`() = runBlocking {
        val permissions = HeldPermissions()
        val manager = ProfileManager(context, permissions)

        permissions.manageProfiles.emit(false)

        // Whether the test device's profile counts as unlocked is Robolectric's
        // business; that both answer is the point.
        val profiles = withTimeout(5_000) { manager.profiles.first() }
        val unlocked = withTimeout(5_000) { manager.unlockedProfiles.first() }
        assertEquals(listOf(Profile.Type.Personal), profiles.map { it.type })
        assertTrue(profiles.containsAll(unlocked))
    }

    @Test
    fun `awaitRead waits for the first read, and then the getters answer`() = runBlocking {
        val permissions = HeldPermissions()
        val manager = ProfileManager(context, permissions)

        assertNull("awaitRead returned before the first refresh", withTimeoutOrNull(500) { manager.awaitRead() })
        permissions.manageProfiles.emit(false)

        withTimeout(5_000) { manager.awaitRead() }
        assertEquals(Profile.Type.Personal, manager.getProfile(Profile.Type.Personal)?.type)
        // The test device has no work profile: an answer, and it is null.
        assertNull(manager.getProfile(Profile.Type.Work))
    }
}

package de.mm20.launcher2.contacts

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.contacts.providers.AndroidContact
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.search.Resolved
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A stored contact is never reported gone (#237). Without the permission the
 * launcher cannot look, and with it GrapheneOS's Contact Scopes can hide a
 * contact so that it looks exactly like a deleted one - so a failed resolve is
 * no evidence, and the repository must keep the row.
 */
@RunWith(RobolectricTestRunner::class)
class ContactResolveTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stored = AndroidContactSerializer().serialize(
        AndroidContact(
            id = 1,
            name = "Alice",
            phoneNumbers = emptyList(),
            emailAddresses = emptyList(),
            postalAddresses = emptyList(),
            customActions = emptyList(),
            lookupKey = "0r1-2B413B2F33",
        )
    )

    private fun deserializer(granted: Boolean) =
        AndroidContactDeserializer(context, FakePermissionsManager(MutableStateFlow(granted)))

    @Test
    fun withoutThePermissionAStoredContactIsUnknown() = runBlocking {
        assertEquals(Resolved.Unknown, deserializer(granted = false).resolve(stored))
    }

    /** Robolectric has no contacts provider, so the lookup finds nothing: a scoped-out contact looks the same. */
    @Test
    fun aContactTheProviderDoesNotReturnIsUnknown() = runBlocking {
        assertEquals(Resolved.Unknown, deserializer(granted = true).resolve(stored))
    }

    /**
     * Granting the permission is announced, so the repository resolves stored
     * contacts again instead of keeping them hidden until the database
     * changes (review on #241).
     */
    @Test
    fun grantingThePermissionAsksForAnotherResolve() = runBlocking {
        val granted = MutableStateFlow(false)
        val deserializer = AndroidContactDeserializer(context, FakePermissionsManager(granted))

        val announced = async { withTimeout(5000) { deserializer.resolveAgain.first() } }
        yield()
        granted.value = true

        assertEquals(Unit, announced.await())
    }

    private class FakePermissionsManager(private val granted: MutableStateFlow<Boolean>) : PermissionsManager {
        override fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup) {}
        override fun checkPermissionOnce(permissionGroup: PermissionGroup): Boolean = granted.value
        override fun checkEnabledInSystem(permissionGroup: PermissionGroup): Boolean = granted.value
        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {}
        override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> = granted
        override fun reportNotificationListenerState(running: Boolean) {}
        override fun reportAccessibilityServiceState(running: Boolean) {}
    }
}

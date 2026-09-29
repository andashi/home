package de.mm20.launcher2.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.contacts.providers.AndroidContact
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.search.Resolved
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A contact is stored by its lookup key, not its row id (#237). The row id is
 * reassigned when contacts are merged and split again: measured on the
 * emulator, Alice at id 1 and Bob at id 2, merged, then split, left id 1 with
 * Bob and Alice at id 4, and a pin stored as `contact://1` opened Bob. The
 * lookup key changes on a merge, a split and a rename too, but every old key
 * still resolves to the right person - which is what the fake provider below
 * reproduces: it answers a lookup by key the way ContactsProvider2 did there.
 */
@RunWith(RobolectricTestRunner::class)
class ContactLookupKeyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun provider() {
        FakeContacts.contacts = emptyList()
        FakeContacts.lookups = emptyMap()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    private val deserializer get() = AndroidContactDeserializer(context, GrantedPermissions)

    private fun contact(id: Long, lookupKey: String) = AndroidContact(
        id = id, name = "x", phoneNumbers = emptyList(), emailAddresses = emptyList(),
        postalAddresses = emptyList(), customActions = emptyList(), lookupKey = lookupKey,
    )

    @Test
    fun theKeyIsTheLookupKey() {
        assertEquals("contact://0r1-A", contact(1, "0r1-A").key)
    }

    @Test
    fun theStoredFormCarriesTheLookupKeyAndTheId() {
        val stored = org.json.JSONObject(AndroidContactSerializer().serialize(contact(4, "0r1-A")))
        assertEquals("0r1-A", stored.getString("lookupKey"))
        assertEquals(4L, stored.getLong("id"))
    }

    /** A row from before this change, `{"id": 1}`, moves to the key of whoever id 1 is now - the residue: see the PR. */
    @Test
    fun aLegacyRowMovesToItsLookupKey() = runBlocking {
        FakeContacts.contacts = listOf(FakeContacts.Contact(1, "0r1-A", "Alice"))

        val resolved = deserializer.resolve("""{"id":1}""")

        assertTrue("$resolved", resolved is Resolved.Moved)
        assertEquals("contact://0r1-A", (resolved as Resolved.Moved).searchable.key)
    }

    @Test
    fun aStoredKeyThatStillMatchesIsFound() = runBlocking {
        FakeContacts.contacts = listOf(FakeContacts.Contact(1, "0r1-A", "Alice"))
        FakeContacts.lookups = mapOf("0r1-A" to 1)

        val resolved = deserializer.resolve("""{"lookupKey":"0r1-A","id":1}""")

        assertTrue("$resolved", resolved is Resolved.Found)
        assertEquals("contact://0r1-A", (resolved as Resolved.Found).searchable.key)
    }

    /**
     * The defect itself: after a merge and a split, id 1 is Bob's. A stored
     * Alice must resolve to Alice, wherever she is now, and never to Bob.
     */
    @Test
    fun aSplitDoesNotHandAStoredContactToWhoeverHasItsIdNow() = runBlocking {
        FakeContacts.contacts = listOf(
            FakeContacts.Contact(1, "0r2-B", "Bob"),
            FakeContacts.Contact(4, "0r1-A", "Alice"),
        )
        FakeContacts.lookups = mapOf("0r1-A" to 4, "0r2-B" to 1)

        val resolved = deserializer.resolve("""{"lookupKey":"0r1-A","id":1}""")

        assertTrue("$resolved", resolved is Resolved.Found)
        val alice = (resolved as Resolved.Found).searchable as AndroidContact
        assertEquals("Alice", alice.name)
        assertEquals(4L, alice.id)
    }

    /** A merge joins the keys: the stored contact moves to the merged one's key, where the repository merges the rows. */
    @Test
    fun aMergeMovesAStoredContactToTheMergedKey() = runBlocking {
        FakeContacts.contacts = listOf(FakeContacts.Contact(1, "0r1-A.0r2-B", "Alice"))
        FakeContacts.lookups = mapOf("0r1-A" to 1, "0r2-B" to 1, "0r1-A.0r2-B" to 1)

        val resolved = deserializer.resolve("""{"lookupKey":"0r2-B","id":2}""")

        assertTrue("$resolved", resolved is Resolved.Moved)
        assertEquals("contact://0r1-A.0r2-B", (resolved as Resolved.Moved).searchable.key)
    }

    /** Deleted, or hidden by Contact Scopes: the same answer, and never Gone. */
    @Test
    fun aKeyNoContactAnswersIsUnknown() = runBlocking {
        FakeContacts.contacts = listOf(FakeContacts.Contact(1, "0r2-B", "Bob"))

        assertEquals(Resolved.Unknown, deserializer.resolve("""{"lookupKey":"0r1-A","id":1}"""))
    }

    private object GrantedPermissions : PermissionsManager {
        override fun requestPermission(context: AppCompatActivity, permissionGroup: PermissionGroup) {}
        override fun checkPermissionOnce(permissionGroup: PermissionGroup) = true
        override fun checkEnabledInSystem(permissionGroup: PermissionGroup) = true
        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {}
        override fun hasPermission(permissionGroup: PermissionGroup): Flow<Boolean> = flowOf(true)
        override fun reportNotificationListenerState(running: Boolean) {}
        override fun reportAccessibilityServiceState(running: Boolean) {}
    }
}

/** What the fake provider holds; set per test. */
object FakeContacts {
    /** One raw contact per contact, whose id is the contact's. */
    data class Contact(val id: Long, val lookupKey: String, val name: String)

    var contacts: List<Contact> = emptyList()

    /** Which contact a lookup key resolves to, old keys included, as the real provider's fallback does. */
    var lookups: Map<String, Long> = emptyMap()
}

/** Answers the four queries the launcher makes: lookup, raw contacts, data and the lookup key column. */
class FakeContactsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val path = uri.pathSegments
        return when {
            path.size >= 3 && path[0] == "contacts" && path[1] == "lookup" -> MatrixCursor(arrayOf(ContactsContract.Contacts._ID)).apply {
                FakeContacts.lookups[path[2]]?.let { addRow(arrayOf<Any>(it)) }
            }
            path == listOf("raw_contacts") -> MatrixCursor(arrayOf(ContactsContract.RawContacts._ID)).apply {
                val id = selectionArgs!![0].toLong()
                FakeContacts.contacts.filter { it.id == id }.forEach { addRow(arrayOf<Any>(it.id)) }
            }
            path == listOf("data") -> MatrixCursor(arrayOf("_id", "mimetype", "data1", "data2", "data3", "account_type_and_data_set")).apply {
                val ids = Regex("""\d+""").findAll(selection!!.substringAfter("IN")).map { it.value.toLong() }.toSet()
                FakeContacts.contacts.filter { it.id in ids }.forEach {
                    addRow(arrayOf<Any?>(it.id, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, it.name, null, null, null))
                }
            }
            path == listOf("contacts") -> MatrixCursor(arrayOf(ContactsContract.Contacts.LOOKUP_KEY)).apply {
                val id = selectionArgs!![0].toLong()
                FakeContacts.contacts.filter { it.id == id }.forEach { addRow(arrayOf<Any>(it.lookupKey)) }
            }
            else -> throw IllegalArgumentException("unexpected query $uri")
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

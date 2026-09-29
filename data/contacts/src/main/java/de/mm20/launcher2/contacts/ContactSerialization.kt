package de.mm20.launcher2.contacts

import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import de.mm20.launcher2.contacts.providers.AndroidContact
import de.mm20.launcher2.contacts.providers.AndroidContactProvider
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.search.Resolved
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableDeserializer
import de.mm20.launcher2.search.SearchableSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AndroidContactSerializer : SearchableSerializer {
    override fun serialize(searchable: SavableSearchable): String {
        searchable as AndroidContact
        return jsonObjectOf(
            "lookupKey" to searchable.lookupKey,
            "id" to searchable.id,
        ).toString()
    }

    override val typePrefix: String
        get() = AndroidContact.Domain
}

internal class AndroidContactDeserializer(
    private val context: Context,
    private val permissionsManager: PermissionsManager
) : SearchableDeserializer {

    override suspend fun deserialize(serialized: String): SavableSearchable? =
        when (val resolved = resolve(serialized)) {
            is Resolved.Found -> resolved.searchable
            is Resolved.Moved -> resolved.searchable
            else -> null
        }

    /** A contact's lookup key changes on a merge, a split or a rename, read or not. */
    override val storedKeysMove: Boolean get() = true

    /** Every change of the permission, not its current state: that one was just resolved. */
    override val resolveAgain: Flow<Unit>
        get() = permissionsManager.hasPermission(PermissionGroup.Contacts).distinctUntilChanged().drop(1).map { }

    /**
     * Never [Resolved.Gone]: the launcher cannot know that a contact is gone.
     * Without the permission it cannot look, and with it GrapheneOS's Contact
     * Scopes can hide a contact so that it looks exactly like a deleted one.
     */
    /**
     * Never [Resolved.Gone]: the launcher cannot know that a contact is gone.
     * Without the permission it cannot look, and with it GrapheneOS's Contact
     * Scopes can hide a contact so that it looks exactly like a deleted one.
     *
     * A stored contact is found through its lookup key, with the stored id as
     * a hint only: after a merge and a split the id can belong to somebody
     * else, while every old lookup key still resolves to the right person
     * (#237). When the contact's key is no longer the stored one - it changed
     * on a merge, split or rename, or the row predates lookup keys - the
     * answer is [Resolved.Moved], and the repository moves the row after it.
     */
    override suspend fun resolve(serialized: String): Resolved {
        if (!permissionsManager.checkPermissionOnce(PermissionGroup.Contacts)) return Resolved.Unknown
        val json = JSONObject(serialized)
        val id = json.getLong("id")
        val storedKey = json.optString("lookupKey").takeIf { it.isNotEmpty() }

        val contact = try {
            withContext(Dispatchers.IO) { lookUp(id, storedKey) }
        } catch (e: SecurityException) {
            // The permission went between the check and the query.
            return Resolved.Unknown
        } as? AndroidContact ?: return Resolved.Unknown
        return when {
            contact.lookupKey == storedKey -> Resolved.Found(contact)
            // Merged with other contacts: found, not moved. The row stays
            // under its own key, so a later split finds it with its owner
            // again; hiding still reaches the merged contact, because the
            // repository resolves hidden rows (#237).
            storedKey != null && isMergeOf(storedKey, contact.lookupKey) -> Resolved.Found(contact)
            else -> Resolved.Moved(contact)
        }
    }

    /** Provider queries, on the caller's thread: [resolve] moves them off the main one. */
    private suspend fun lookUp(id: Long, storedKey: String?) =
        if (storedKey == null) {
            // Stored before lookup keys: the id is all there is. If it has
            // already drifted to somebody else, this moves the row to them
            // and nothing can tell (#237).
            AndroidContactProvider(context).get(id)
        } else {
            val uri = ContactsContract.Contacts.getLookupUri(id, storedKey)
            val current = ContactsContract.Contacts.lookupContact(context.contentResolver, uri)
            current?.let { AndroidContactProvider(context).get(ContentUris.parseId(it)) }
        }
}

/**
 * Whether [current] is [stored] joined with other contacts' keys - a merge
 * (#237).
 *
 * An assumption about a format Android documents as opaque. Measured on this
 * emulator image on 2026-09-28: merging two local contacts joined their keys
 * with a dot (`0r1-2B413B2F33` and `0r2-2D472D` became
 * `0r1-2B413B2F33.0r2-2D472D`), a split left one part, a rename changed the
 * name part of one. If a platform ever joins keys differently, this answers
 * false and a merged contact's rows move to the merged key, as they did before
 * this check - the rows of two people then follow one of them after a split.
 * The unit test pins the measured example; the L4 scenario, which asserts
 * who carries a tag and who is hidden across a merge and a split, is what
 * notices a platform change.
 */
internal fun isMergeOf(stored: String, current: String): Boolean {
    val storedParts = stored.split('.').toSet()
    val currentParts = current.split('.').toSet()
    return currentParts.size > storedParts.size && currentParts.containsAll(storedParts)
}

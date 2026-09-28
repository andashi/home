package de.mm20.launcher2.contacts

import android.content.Context
import de.mm20.launcher2.contacts.providers.AndroidContact
import de.mm20.launcher2.contacts.providers.AndroidContactProvider
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.search.Resolved
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableDeserializer
import de.mm20.launcher2.search.SearchableSerializer
import org.json.JSONObject

internal class AndroidContactSerializer : SearchableSerializer {
    override fun serialize(searchable: SavableSearchable): String {
        searchable as AndroidContact
        return jsonObjectOf(
            "id" to searchable.id
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
        (resolve(serialized) as? Resolved.Found)?.searchable

    /**
     * Never [Resolved.Gone]: the launcher cannot know that a contact is gone.
     * Without the permission it cannot look, and with it GrapheneOS's Contact
     * Scopes can hide a contact so that it looks exactly like a deleted one.
     */
    override suspend fun resolve(serialized: String): Resolved {
        if (!permissionsManager.checkPermissionOnce(PermissionGroup.Contacts)) return Resolved.Unknown
        val id = JSONObject(serialized).getLong("id")

        val androidContactProvider = AndroidContactProvider(context)

        return androidContactProvider.get(id)?.let { Resolved.Found(it) } ?: Resolved.Unknown
    }
}

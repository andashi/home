package de.mm20.launcher2.appshortcuts

import android.content.Context
import android.content.Intent
import android.content.Intent.ShortcutIconResource
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.core.content.getSystemService
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableDeserializer
import de.mm20.launcher2.search.SearchableSerializer
import org.json.JSONObject
import org.koin.core.component.KoinComponent


class LauncherShortcutSerializer : SearchableSerializer {
    override fun serialize(searchable: SavableSearchable): String {
        searchable as LauncherShortcut
        return jsonObjectOf(
            "packagename" to searchable.launcherShortcut.`package`,
            "id" to searchable.launcherShortcut.id,
            "user" to searchable.userSerialNumber,
        ).toString()
    }

    override val typePrefix: String
        get() = "shortcut"

}

class LauncherShortcutDeserializer(
    val context: Context,
    private val permissionsManager: PermissionsManager,
) : SearchableDeserializer, KoinComponent {

    override suspend fun deserialize(serialized: String): SavableSearchable? {
        try {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            val json = JSONObject(serialized)
            val packageName = json.getString("packagename")
            val id = json.getString("id")
            val userSerial = json.optLong("user", -1L)

            val userManager = context.getSystemService<UserManager>()!!
            val user = if (userSerial == -1L) Process.myUserHandle() else (userManager.getUserForSerialNumber(userSerial) ?: return null)

            if (!launcherApps.hasShortcutHostPermission()) {
                return UnavailableShortcut(context, id, packageName, user, userSerial)
            } else {
                val query = LauncherApps.ShortcutQuery()
                query.setPackage(packageName)
                query.setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER
                )
                query.setShortcutIds(mutableListOf(id))
                // A query that could not run and a query that found nothing
                // mean opposite things here, so they must stay
                // distinguishable: orEmpty() makes success non-null, leaving
                // null for the failure the helper reports.
                val shortcuts = queryShortcutHost(
                    unavailable = null,
                    permissionsManager = permissionsManager,
                ) {
                    launcherApps.getShortcuts(query, user).orEmpty()
                }
                // "Cannot look right now" must not delete the favorite, and
                // returning null here would: SavableSearchableRepository drops
                // the row for anything that fails to deserialize. A locked
                // profile, or a HOME role that moved between the permission
                // check above and this line, must not cost someone their
                // pinned shortcuts (andashi/home#30).
                    ?: return UnavailableShortcut(context, id, packageName, user, userSerial)

                if (shortcuts.isEmpty()) {
                    // Really gone, not merely unreachable: the row goes.
                    return null
                } else {
                    return LauncherShortcut(
                        context = context,
                        launcherShortcut = shortcuts[0],
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e("MM20", "Failed to deserialize shortcut: $serialized", e)
            return null
        }
    }
}

class UnavailableShortcutSerializer: SearchableSerializer {
    override fun serialize(searchable: SavableSearchable): String? {
        searchable as UnavailableShortcut
        return jsonObjectOf(
            "packagename" to searchable.packageName,
            "id" to searchable.shortcutId,
            "user" to searchable.userSerial,
        ).toString()
    }

    override val typePrefix: String
        get() = LauncherShortcut.Domain

}

class LegacyShortcutSerializer: SearchableSerializer {
    override fun serialize(searchable: SavableSearchable): String {
        searchable as LegacyShortcut
        return jsonObjectOf(
            "label" to searchable.label,
            "intent" to searchable.intent.toUri(0),
            "iconResource" to searchable.iconResource?.let {
                jsonObjectOf(
                    "package" to it.packageName,
                    "resource" to it.resourceName,
                )
            }
        ).toString()
    }

    override val typePrefix: String
        get() = "legacyshortcut"
}

class LegacyShortcutDeserializer(
    val context: Context
): SearchableDeserializer {
    /**
     * Returns null for a favorite whose stored Intent does not survive
     * [LegacyShortcut.sanitize], which drops the row: before andashi/home#5
     * was fixed, any app could have had one of these persisted through the
     * exported pin-request path, so a favorite that is already in the database
     * is checked on the way out as well as on the way in.
     */
    override suspend fun deserialize(serialized: String): SavableSearchable? {
        val json = JSONObject(serialized)
        val label = json.getString("label")
        val storedIntent = Intent.parseUri(json.getString("intent"), 0)
        val intent = LegacyShortcut.sanitize(context, storedIntent) ?: return null
        val iconResourceObj = json.optJSONObject("iconResource")
        val iconResource = iconResourceObj?.let {
            ShortcutIconResource().apply {
                packageName = iconResourceObj.getString("package")
                resourceName = iconResourceObj.getString("resource")
            }
        }

        val packageName = intent.`package` ?: intent.component?.packageName

        val appName = try {
            packageName?.let {
                context
                    .packageManager
                    .getApplicationInfo(it, 0)
                    .loadLabel(context.packageManager)
                    .toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        return LegacyShortcut(
            intent = intent,
            label = label,
            iconResource = iconResource,
            appName = appName,
        )
    }

}
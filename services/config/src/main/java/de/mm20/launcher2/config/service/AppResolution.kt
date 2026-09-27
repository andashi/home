package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.data.customattrs.CustomIconPackIcon
import de.mm20.launcher2.search.Application
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import de.mm20.launcher2.config.Profile as ConfigProfile

/*
 * What the lists that name apps share (#3 slice 4): `apps` and `tags[].apps`
 * name an app the same way, store a pack icon the same way, and record how
 * the file wrote each app the same way. Defined once, so the two cannot come
 * to mean different apps by the same entry.
 */

/** Which installed app an entry means, or why there is none here. */
internal sealed interface AppLookup {
    data class Found(val app: Application) : AppLookup

    /** A warning: the file keeps its entry, and it applies once the app is there. */
    data class Missing(val diagnostic: Diagnostic) : AppLookup
}

/**
 * The app [packageName] in [profile] means: the entry with [activity], or the
 * package's first launcher entry where [activity] is null - the one a favorite
 * without an activity means.
 */
internal fun ProfileResolver.lookUpApp(
    installed: List<Application>,
    packageName: String,
    profile: ConfigProfile,
    activity: String?,
    path: String,
): AppLookup {
    val user = getProfile(profile.toProfileType())?.userHandle ?: return AppLookup.Missing(
        Diagnostic(
            DiagnosticCode.ProfileUnavailable, path,
            "The ${profile.name.lowercase()} profile does not exist on this device; " +
                "the entry for '$packageName' is kept and applies once it does",
        )
    )
    val entries = installed.filter { it.componentName.packageName == packageName && it.user == user }
    val app = activity?.let { entries.firstOrNull { it.componentName.className == activity } }
        ?: entries.firstOrNull().takeIf { activity == null }
    return app?.let { AppLookup.Found(it) } ?: AppLookup.Missing(
        Diagnostic(
            DiagnosticCode.AppUnavailable, path,
            "'$packageName'${activity?.let { " ($it)" } ?: ""} is not installed in the " +
                "${profile.name.lowercase()} profile; its entry is kept and applies once it is",
        )
    )
}

/**
 * The entry's class name, or null for a package's first launcher entry -
 * the one an entry without an activity means, as a favorite does.
 */
internal fun activityOf(app: Application, installed: List<Application>): String? {
    val first = installed.firstOrNull {
        it.componentName.packageName == app.componentName.packageName && it.user == app.user
    }
    return if (first?.key == app.key) null else app.componentName.className
}

/** What the picker stores for a pack icon, or why it cannot be stored on this device. */
internal sealed interface StoredPackIcon {
    data class Found(val icon: CustomIconPackIcon) : StoredPackIcon
    data class Missing(val what: String) : StoredPackIcon
}

/**
 * [drawable] of [pack], completed from the pack's index as the picker stores
 * it. Unthemed when [themed] is false, or when the pack cannot theme it.
 */
internal suspend fun IconPackIndex.stored(pack: String, drawable: String, themed: Boolean): StoredPackIcon =
    when (val found = resolve(pack, drawable)) {
        is IconPackIndex.Resolution.Found ->
            StoredPackIcon.Found(CustomIconPackIcon(pack, found.type, found.drawable, found.extras, found.themed && themed))
        IconPackIndex.Resolution.PackMissing -> StoredPackIcon.Missing("The icon pack '$pack' is not installed")
        IconPackIndex.Resolution.DrawableMissing -> StoredPackIcon.Missing("The icon pack '$pack' has no drawable '$drawable'")
    }

/** The file's form of a stored pack icon: pack, drawable and themed; null for an older row without its drawable. */
internal data class FilePackIcon(val pack: String, val drawable: String, val themed: Boolean)

internal suspend fun IconPackIndex.fileForm(icon: CustomIconPackIcon): FilePackIcon? = icon.drawable?.let { drawable ->
    // `themed: false` only where the pack could have themed it: a drawable
    // it cannot theme is stored unthemed whatever the file said, and
    // reading that as a choice would write it into a file that never asked.
    val themeable = (resolve(icon.iconPackPackage, drawable) as? IconPackIndex.Resolution.Found)?.themed ?: true
    FilePackIcon(icon.iconPackPackage, drawable, themed = icon.allowThemed || !themeable)
}

/**
 * Records [naming] and runs [write], the device write it describes. Recorded
 * first: the change the write sets off must read the new form. If the write
 * fails, the record goes back too, since the apply is reported failed and
 * the baseline stays as it was (review on #214).
 */
internal suspend fun AppNaming.replaceAround(naming: Map<String, String?>, write: suspend () -> Unit) {
    val before = observe().first()
    // No record reads as empty; putting "empty" back would make one, and
    // the next start would not try again (review on #214).
    val hadRecord = recorded()
    try {
        replace(naming)
        write()
    } catch (e: Throwable) {
        // The restore can fail too, and then the record would claim a form
        // the device is not in; no record is the honest state instead.
        // Nothing is swallowed: the apply's failure propagates with the
        // repair's attached (review on #214). A cancelled apply is a
        // failure as well, and a cancelled coroutine cannot write, so the
        // repair runs non-cancellable.
        withContext(NonCancellable) {
            try {
                if (hadRecord) replace(before) else forget()
            } catch (restore: Throwable) {
                e.addSuppressed(restore)
                try {
                    forget()
                } catch (forget: Throwable) {
                    e.addSuppressed(forget)
                }
            }
        }
        throw e
    }
}

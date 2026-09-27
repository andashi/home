package de.mm20.launcher2.config.service

import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.AppConfig
import de.mm20.launcher2.config.AppVisibility
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.normalizedApps
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.searchable.VisibilityLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * `apps` (#3 slice 4): an app's own name and visibility, as the file names
 * them. Apps only: the names and visibility of contacts and shortcuts are
 * the phone's, and nothing here reads or writes them.
 */
interface AppCustomizationStore {
    /** The customizations of the installed apps: what the read-back serves and write-back compares. */
    suspend fun read(): List<AppConfig>

    /** Emits once on collection, then whenever [read] would return something else. */
    fun changes(): Flow<Unit>

    /**
     * Makes [apps] the whole state of the installed apps, and returns the
     * diagnostics and what it wrote, read back. An installed app the list
     * does not name loses its name and is shown normally; an entry for an
     * app that is not installed is reported, and applies once it is.
     */
    suspend fun replaceAndRead(apps: List<AppConfig>): Pair<List<Diagnostic>, List<AppConfig>>
}

internal class AndroidAppCustomizationStore(
    private val appRepository: AppRepository,
    private val profileResolver: ProfileResolver,
    private val customAttributes: CustomAttributesRepository,
    private val searchables: SavableSearchableRepository,
    /** How the file wrote each app it customizes; see [AppNaming]. */
    private val naming: AppNaming,
) : AppCustomizationStore {

    private fun keysAt(level: VisibilityLevel) = searchables.getKeys(
        includeTypes = listOf("app"),
        minVisibility = level,
        maxVisibility = level,
    )

    /**
     * Installed apps only: a customization left behind by an uninstalled app
     * stays in the database (it comes back with the app), but it is not
     * the phone's state, and it never reaches the file.
     */
    private fun state(): Flow<List<AppConfig>> = combine(
        appRepository.findMany(),
        customAttributes.getAppLabels(),
        keysAt(VisibilityLevel.SearchOnly),
        keysAt(VisibilityLevel.Hidden),
        naming.observe(),
    ) { apps, labels, searchOnly, hidden, written -> Snapshot(apps, labels, searchOnly.toSet(), hidden.toSet(), written) }
        .map { describe(it) }
        .distinctUntilChanged()

    override suspend fun read(): List<AppConfig> = state().first()

    override fun changes(): Flow<Unit> = state().map { }

    override suspend fun replaceAndRead(apps: List<AppConfig>): Pair<List<Diagnostic>, List<AppConfig>> {
        val installed = appRepository.findMany().first()
        val diagnostics = mutableListOf<Diagnostic>()
        val labels = mutableMapOf<String, String>()
        val wanted = mutableMapOf<String, VisibilityLevel>()
        // Which entry claimed each app: without an activity an entry means the
        // package's first launcher entry, so naming that activity too is the
        // same app twice, which the validator cannot see (review on #207).
        val claimedBy = mutableMapOf<String, Int>()
        // How each claiming entry wrote its app, so it reads back that way.
        val written = mutableMapOf<String, String?>()

        apps.forEachIndexed { index, entry ->
            val path = "apps[$index]"
            val profile = profileResolver.getProfile(entry.profile.toProfileType())
            if (profile == null) {
                diagnostics += Diagnostic(
                    Severity.Warning, "profile-unavailable", path,
                    "The ${entry.profile.name.lowercase()} profile does not exist on this device; " +
                        "the entry for '${entry.packageName}' is kept and applies once it does",
                )
                return@forEachIndexed
            }
            val entries = installed.filter {
                it.componentName.packageName == entry.packageName && it.user == profile.userHandle
            }
            val app = entry.activity?.let { activity -> entries.firstOrNull { it.componentName.className == activity } }
                ?: entries.firstOrNull().takeIf { entry.activity == null }
            if (app == null) {
                diagnostics += Diagnostic(
                    Severity.Warning, "app-unavailable", path,
                    "'${entry.packageName}'${entry.activity?.let { " ($it)" } ?: ""} is not installed in the " +
                        "${entry.profile.name.lowercase()} profile; its entry is kept and applies once it is",
                )
                return@forEachIndexed
            }
            claimedBy[app.key]?.let { first ->
                diagnostics += Diagnostic(
                    Severity.Warning, "duplicate-app", path,
                    "'${entry.packageName}'${entry.activity?.let { " ($it)" } ?: ""} is the same app on this device " +
                        "as apps[$first]; that entry applies and this one does not",
                )
                return@forEachIndexed
            }
            claimedBy[app.key] = index
            written[app.key] = entry.activity
            entry.label?.let { labels[app.key] = it }
            wanted[app.key] = entry.visibility.toLevel()
        }

        // Before the labels: the change their write sets off must read the new form.
        naming.replace(written)
        customAttributes.replaceCustomLabelsAwaited(installed, labels)

        // Only what differs is written: an app shown normally that stays so
        // gets no row it never had.
        val searchOnly = keysAt(VisibilityLevel.SearchOnly).first().toSet()
        val hidden = keysAt(VisibilityLevel.Hidden).first().toSet()
        val changes: Map<SavableSearchable, VisibilityLevel> = installed.mapNotNull { app ->
            val want = wanted[app.key] ?: VisibilityLevel.Default
            val have = levelOf(app.key, searchOnly, hidden)
            if (want != have) (app as SavableSearchable) to want else null
        }.toMap()
        searchables.setVisibilitiesAwaited(changes)

        return diagnostics to read()
    }

    private suspend fun describe(snapshot: Snapshot): List<AppConfig> = snapshot.apps.mapNotNull { app ->
        val label = snapshot.labels[app.key]
        val visibility = when (levelOf(app.key, snapshot.searchOnly, snapshot.hidden)) {
            VisibilityLevel.SearchOnly -> AppVisibility.SearchOnly
            VisibilityLevel.Hidden -> AppVisibility.Hidden
            VisibilityLevel.Default -> null
        }
        if (label == null && visibility == null) return@mapNotNull null
        val profile = profileResolver.getProfile(app.user)?.type?.toConfigProfile() ?: return@mapNotNull null
        AppConfig(
            packageName = app.componentName.packageName,
            profile = profile,
            // As the file wrote it, where the file customizes this app; else by the rule.
            activity = if (app.key in snapshot.written) snapshot.written[app.key] else activityOf(app, snapshot.apps),
            label = label,
            visibility = visibility,
        )
    }.normalizedApps()

    /**
     * The entry's class name, or null for a package's first launcher entry -
     * the one an entry without an activity means, as a favorite does.
     */
    private fun activityOf(app: Application, installed: List<Application>): String? {
        val first = installed.firstOrNull {
            it.componentName.packageName == app.componentName.packageName && it.user == app.user
        }
        return if (first?.key == app.key) null else app.componentName.className
    }

    private fun levelOf(key: String, searchOnly: Set<String>, hidden: Set<String>) = when (key) {
        in hidden -> VisibilityLevel.Hidden
        in searchOnly -> VisibilityLevel.SearchOnly
        else -> VisibilityLevel.Default
    }

    private fun AppVisibility?.toLevel() = when (this) {
        AppVisibility.SearchOnly -> VisibilityLevel.SearchOnly
        AppVisibility.Hidden -> VisibilityLevel.Hidden
        AppVisibility.Default, null -> VisibilityLevel.Default
    }

    private data class Snapshot(
        val apps: List<Application>,
        val labels: Map<String, String>,
        val searchOnly: Set<String>,
        val hidden: Set<String>,
        val written: Map<String, String?>,
    )
}

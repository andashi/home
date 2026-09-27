package de.mm20.launcher2.config.service

import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.AppConfig
import de.mm20.launcher2.config.AppIcon
import de.mm20.launcher2.config.IconBackground
import de.mm20.launcher2.config.AppVisibility
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.normalized
import de.mm20.launcher2.config.normalizedApps
import de.mm20.launcher2.data.customattrs.AdaptifiedLegacyIcon
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.CustomIcon
import de.mm20.launcher2.data.customattrs.CustomIconPackIcon
import de.mm20.launcher2.data.customattrs.DefaultPlaceholderIcon
import de.mm20.launcher2.data.customattrs.ForceThemedIcon
import de.mm20.launcher2.data.customattrs.UnmodifiedSystemDefaultIcon
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
 * `apps` (#3 slice 4): an app's own name, icon and visibility, as the file
 * names them. Apps only: the names and visibility of contacts and shortcuts are
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
    /** Completes a pack icon as the picker stores it; see [IconPackIndex]. */
    private val iconPacks: IconPackIndex,
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
    ) { apps, labels, searchOnly, hidden, written -> Snapshot(apps, labels, emptyMap(), searchOnly.toSet(), hidden.toSet(), written) }
        .combine(customAttributes.getAppIcons()) { snapshot, icons -> snapshot.copy(icons = icons) }
        .map { describe(it) }
        .distinctUntilChanged()

    override suspend fun read(): List<AppConfig> = state().first()

    override fun changes(): Flow<Unit> = state().map { }

    override suspend fun replaceAndRead(apps: List<AppConfig>): Pair<List<Diagnostic>, List<AppConfig>> {
        val installed = appRepository.findMany().first()
        val diagnostics = mutableListOf<Diagnostic>()
        val labels = mutableMapOf<String, String>()
        val icons = mutableMapOf<String, CustomIcon>()
        val wanted = mutableMapOf<String, VisibilityLevel>()
        // Which entry claimed each app: without an activity an entry means the
        // package's first launcher entry, so naming that activity too is the
        // same app twice, which the validator cannot see (review on #207).
        val claimedBy = mutableMapOf<String, Int>()
        // How each claiming entry wrote its app, so it reads back that way.
        val written = mutableMapOf<String, String?>()

        apps.forEachIndexed { index, entry ->
            val path = "apps[$index]"
            val app = when (val found = profileResolver.lookUpApp(installed, entry.packageName, entry.profile, entry.activity, path)) {
                is AppLookup.Found -> found.app
                is AppLookup.Missing -> {
                    diagnostics += found.diagnostic
                    return@forEachIndexed
                }
            }
            claimedBy[app.key]?.let { first ->
                diagnostics += Diagnostic(
                    DiagnosticCode.DuplicateAppOnDevice, path,
                    "'${entry.packageName}'${entry.activity?.let { " ($it)" } ?: ""} is the same app on this device " +
                        "as apps[$first]; that entry applies and this one does not",
                )
                return@forEachIndexed
            }
            claimedBy[app.key] = index
            written[app.key] = entry.activity
            entry.label?.let { labels[app.key] = it }
            entry.icon?.let { icon ->
                when (val stored = storedIcon(icon)) {
                    is StoredIcon.Found -> icons[app.key] = stored.icon
                    // The app shows its normal icon until the pack is there (apps.md).
                    is StoredIcon.Missing -> diagnostics += Diagnostic(
                        DiagnosticCode.IconPackUnavailable, "$path.icon",
                        "${stored.what}; '${entry.packageName}' shows its normal icon until it is",
                    )
                }
            }
            wanted[app.key] = entry.visibility.toLevel()
        }

        // Before the labels: the change their write sets off must read the new form.
        naming.replaceAround(written) { writeDeviceState(installed, labels, icons, wanted) }

        return diagnostics to read()
    }

    private suspend fun writeDeviceState(
        installed: List<Application>,
        labels: Map<String, String>,
        icons: Map<String, CustomIcon>,
        wanted: Map<String, VisibilityLevel>,
    ) {
        customAttributes.replaceCustomLabelsAwaited(installed, labels)
        customAttributes.replaceCustomIconsAwaited(installed, icons)

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
    }

    private suspend fun describe(snapshot: Snapshot): List<AppConfig> = snapshot.apps.mapNotNull { app ->
        val label = snapshot.labels[app.key]
        val icon = snapshot.icons[app.key]?.let { fileIcon(it) }
        val visibility = when (levelOf(app.key, snapshot.searchOnly, snapshot.hidden)) {
            VisibilityLevel.SearchOnly -> AppVisibility.SearchOnly
            VisibilityLevel.Hidden -> AppVisibility.Hidden
            VisibilityLevel.Default -> null
        }
        if (label == null && icon == null && visibility == null) return@mapNotNull null
        val profile = profileResolver.getProfile(app.user)?.type?.toConfigProfile() ?: return@mapNotNull null
        AppConfig(
            packageName = app.componentName.packageName,
            profile = profile,
            // As the file wrote it, where the file customizes this app; else by the rule.
            activity = if (app.key in snapshot.written) snapshot.written[app.key] else activityOf(app, snapshot.apps),
            label = label,
            visibility = visibility,
            icon = icon,
        )
    }.normalizedApps()

    /** What the picker stores for [icon], or why it cannot be stored on this device. */
    private suspend fun storedIcon(icon: AppIcon): StoredIcon = when (icon) {
        AppIcon.System -> StoredIcon.Found(UnmodifiedSystemDefaultIcon)
        AppIcon.Themed -> StoredIcon.Found(ForceThemedIcon)
        AppIcon.Placeholder -> StoredIcon.Found(DefaultPlaceholderIcon)
        is AppIcon.Adaptive -> StoredIcon.Found(
            AdaptifiedLegacyIcon(
                fgScale = icon.scale,
                bgColor = when (val background = icon.background.normalized()) {
                    IconBackground.FromIcon -> AdaptifiedLegacyIcon.UnspecifiedColor
                    IconBackground.Theme -> AdaptifiedLegacyIcon.ThemeColor
                    is IconBackground.Color -> background.argb
                },
            ),
        )
        is AppIcon.Pack -> when (val found = iconPacks.stored(icon.pack, icon.drawable, icon.themed)) {
            is StoredPackIcon.Found -> StoredIcon.Found(found.icon)
            is StoredPackIcon.Missing -> StoredIcon.Missing(found.what)
        }
    }

    private sealed interface StoredIcon {
        data class Found(val icon: CustomIcon) : StoredIcon
        data class Missing(val what: String) : StoredIcon
    }

    /**
     * The file's form of a stored icon, or null for one the file cannot name:
     * an older pack row without its pack, say. Such an app reads back
     * without an icon.
     */
    private suspend fun fileIcon(icon: CustomIcon): AppIcon? = when (icon) {
        UnmodifiedSystemDefaultIcon -> AppIcon.System
        ForceThemedIcon -> AppIcon.Themed
        DefaultPlaceholderIcon -> AppIcon.Placeholder
        is AdaptifiedLegacyIcon -> AppIcon.Adaptive(
            scale = icon.fgScale,
            background = when (icon.bgColor) {
                AdaptifiedLegacyIcon.UnspecifiedColor -> IconBackground.FromIcon
                AdaptifiedLegacyIcon.ThemeColor -> IconBackground.Theme
                else -> IconBackground.Color(icon.bgColor)
            },
        )
        is CustomIconPackIcon -> iconPacks.fileForm(icon)?.let { AppIcon.Pack(it.pack, it.drawable, it.themed) }
        else -> null
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
        val icons: Map<String, CustomIcon>,
        val searchOnly: Set<String>,
        val hidden: Set<String>,
        val written: Map<String, String?>,
    )
}

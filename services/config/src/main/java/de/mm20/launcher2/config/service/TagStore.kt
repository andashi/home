package de.mm20.launcher2.config.service

import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.TagApp
import de.mm20.launcher2.config.TagConfig
import de.mm20.launcher2.config.TagIcon
import de.mm20.launcher2.config.normalizedTags
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.CustomIcon
import de.mm20.launcher2.data.customattrs.CustomIconPackIcon
import de.mm20.launcher2.data.customattrs.CustomTextIcon
import de.mm20.launcher2.search.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * `tags` (#3 slice 4): which apps carry which tags, and each tag's icon, as
 * the file names them. Apps only: a tag's contacts and shortcuts are the
 * phone's, and nothing here reads or writes them.
 */
interface TagStore {
    /** The installed apps' tags: what the read-back serves and write-back compares. */
    suspend fun read(): List<TagConfig>

    /** Emits once on collection, then whenever [read] would return something else. */
    fun changes(): Flow<Unit>

    /**
     * Makes [tags] the whole state of the installed apps' tags, and returns
     * the diagnostics and what it wrote, read back. An installed app no tag
     * names carries none; a tag's icon is set for every tag listed, and left
     * alone for one the list does not name. An entry for an app that is not
     * installed is reported, and applies once it is.
     */
    suspend fun replaceAndRead(tags: List<TagConfig>): Pair<List<Diagnostic>, List<TagConfig>>
}

internal class AndroidTagStore(
    private val appRepository: AppRepository,
    private val profileResolver: ProfileResolver,
    private val customAttributes: CustomAttributesRepository,
    /**
     * How the file wrote each tagged app, keyed by [recordKey]: an app's
     * first launcher entry can be named with or without its activity, as in
     * `apps`, and per tag, since two tags can write it differently. A record
     * of its own, not `apps`' one: each list replaces its record whole.
     * Reloads make it where it is missing and write-back waits for it, as
     * for `apps` (review on #224).
     */
    private val naming: AppNaming,
    private val iconPacks: IconPackIndex,
) : TagStore {

    /**
     * Installed apps only: a tag left on an uninstalled app stays in the
     * database (it comes back with the app), but it is not the phone's
     * state, and it never reaches the file. Nor is a tag no installed app
     * carries, whatever its icon.
     */
    private fun state(): Flow<List<TagConfig>> = combine(
        appRepository.findMany(),
        customAttributes.getAppTags(),
        customAttributes.getTagIcons(),
        naming.observe(),
    ) { apps, tags, icons, written -> describe(apps, tags, icons, written) }
        .distinctUntilChanged()

    override suspend fun read(): List<TagConfig> = state().first()

    override fun changes(): Flow<Unit> = state().map { }

    override suspend fun replaceAndRead(tags: List<TagConfig>): Pair<List<Diagnostic>, List<TagConfig>> {
        val installed = appRepository.findMany().first()
        val diagnostics = mutableListOf<Diagnostic>()
        val wanted = mutableMapOf<String, MutableSet<String>>()
        val icons = mutableMapOf<String, CustomIcon>()
        val written = mutableMapOf<String, String?>()

        tags.forEachIndexed { index, tag ->
            val path = "tags[$index]"
            // Which entry of this tag claimed each app: the first entry of a
            // package and its activity spelled out are the same app, which
            // only the device can tell.
            val claimedBy = mutableMapOf<String, Int>()
            tag.apps.forEachIndexed { appIndex, entry ->
                val appPath = "$path.apps[$appIndex]"
                val app = when (val found = profileResolver.lookUpApp(installed, entry.packageName, entry.profile, entry.activity, appPath)) {
                    is AppLookup.Found -> found.app
                    is AppLookup.Missing -> {
                        diagnostics += found.diagnostic
                        return@forEachIndexed
                    }
                }
                claimedBy[app.key]?.let { first ->
                    diagnostics += Diagnostic(
                        DiagnosticCode.DuplicateAppOnDevice, appPath,
                        "'${entry.packageName}'${entry.activity?.let { " ($it)" } ?: ""} is the same app on this device " +
                            "as $path.apps[$first]; the tag counts it once",
                    )
                    return@forEachIndexed
                }
                claimedBy[app.key] = appIndex
                written[recordKey(tag.name, app.key)] = entry.activity
                wanted.getOrPut(app.key) { mutableSetOf() } += tag.name
            }
            when (val icon = tag.icon) {
                is TagIcon.Text -> icons[tag.name] = CustomTextIcon(icon.text)
                is TagIcon.Pack -> when (val stored = iconPacks.stored(icon.pack, icon.drawable, icon.themed)) {
                    is StoredPackIcon.Found -> icons[tag.name] = stored.icon
                    // The tag shows its own icon until the pack is there (tags.md).
                    is StoredPackIcon.Missing -> diagnostics += Diagnostic(
                        DiagnosticCode.IconPackUnavailable, "$path.icon",
                        "${stored.what}; the tag '${tag.name}' shows its own icon until it is",
                    )
                }
                null -> Unit
            }
        }

        naming.replaceAround(written) {
            customAttributes.replaceAppTagsAwaited(installed, wanted)
            customAttributes.replaceTagIconsAwaited(tags.map { it.name }, icons)
        }

        return diagnostics to read()
    }

    private suspend fun describe(
        installed: List<Application>,
        tags: Map<String, Set<String>>,
        icons: Map<String, CustomIcon>,
        written: Map<String, String?>,
    ): List<TagConfig> {
        val members = mutableMapOf<String, MutableList<TagApp>>()
        for (app in installed) {
            val names = tags[app.key] ?: continue
            val profile = profileResolver.getProfile(app.user)?.type?.toConfigProfile() ?: continue
            for (name in names) {
                val key = recordKey(name, app.key)
                // As the file wrote it, where the file tags this app; else by the rule.
                val activity = if (key in written) written[key] else activityOf(app, installed)
                members.getOrPut(name) { mutableListOf() } += TagApp(app.componentName.packageName, profile, activity)
            }
        }
        return members.map { (name, apps) -> TagConfig(name, icons[name]?.let { fileIcon(it) }, apps) }.normalizedTags()
    }

    /**
     * The file's form of a stored icon, or null for one the file cannot
     * name: an icon of another kind, or a text icon in a colour the picker
     * never sets. Such a tag reads back without an icon.
     */
    private suspend fun fileIcon(icon: CustomIcon): TagIcon? = when (icon) {
        is CustomTextIcon -> if (icon.color == 0) TagIcon.Text(icon.text) else null
        is CustomIconPackIcon -> iconPacks.fileForm(icon)?.let { TagIcon.Pack(it.pack, it.drawable, it.themed) }
        else -> null
    }

    private companion object {
        /** An app key has no line break, so the key splits at its last one: no two pairs share it. */
        fun recordKey(tag: String, appKey: String) = "$tag\n$appKey"
    }
}

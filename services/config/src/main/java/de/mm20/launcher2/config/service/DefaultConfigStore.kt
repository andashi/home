package de.mm20.launcher2.config.service

import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Gesture
import de.mm20.launcher2.config.GestureActionName
import de.mm20.launcher2.config.GestureConfig
import de.mm20.launcher2.config.GridItemConfig
import de.mm20.launcher2.config.GridLayoutConfig
import de.mm20.launcher2.config.GridLayouts
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.GridItem
import de.mm20.launcher2.grid.GridLayout
import de.mm20.launcher2.grid.GridSpec
import de.mm20.launcher2.grid.LayoutIssue
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.GridRowsSource
import de.mm20.launcher2.homegrid.MeasuredGridRows
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridItemConfig
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.homegrid.HomeGridInitFlag
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.homegrid.HomeGridRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import de.mm20.launcher2.config.Profile as ConfigProfile

/**
 * Fork addition (Phase 2, ADR 0003): [ConfigStore] implementation backed by
 * the real launcher repositories.
 *
 * - Settings-backed state/mutations go through [LauncherConfigSettings]
 *   (single awaited DataStore write per [apply] call).
 * - `appearance.glass` is settings-backed; the file no longer feeds the
 *   upstream transparency schemes (#73).
 * - Grid layouts (`home.grid.layouts`) are normalised through the layout
 *   engine and written to [HomeGridRepository]; `columns` and `locked` are
 *   settings-backed.
 * - Search actions (`search.actions`, #106) go through [SearchActionStore].
 * - Favorites are resolved from `{packageName, profile}` pairs via
 *   [AppRepository] + [ProfileResolver] and written with
 *   [SavableSearchableRepository.replaceManuallySortedAwaited]. User serials never
 *   appear in config state or diagnostics.
 * - Gestures (`gestures`, #3 slice 2) are settings, written apart from the
 *   others because an app they open is resolved like a favorite first and
 *   saved where the launcher looks it up.
 */
class DefaultConfigStore(
    private val settings: LauncherConfigSettings,
    private val homeGridRepository: HomeGridRepository,
    private val homeGridInitFlag: HomeGridInitFlag,
    private val homeGridInitLock: HomeGridInitLock,
    private val gridLimits: GridLimitsSource,
    private val gridRows: GridRowsSource,
    private val searchableRepository: SavableSearchableRepository,
    private val appRepository: AppRepository,
    private val profileResolver: ProfileResolver,
    private val wallpapers: WallpaperStore,
    private val searchActions: SearchActionStore,
    /** `apps` (#3 slice 4): an app's own name and visibility. */
    private val apps: AppCustomizationStore,
    /** `tags` (#3 slice 4): which apps carry which tags, and each tag's icon. */
    private val tags: TagStore,
) : ConfigStore {

    private fun favoriteApps() = searchableRepository.get(
        includeTypes = listOf(AppDomain),
        minPinnedLevel = PinnedLevel.ManuallySorted,
        maxPinnedLevel = PinnedLevel.ManuallySorted,
    )

    /**
     * Every source [readState] reads, each passing on only a change of what
     * the config sees of it - the favorites' keys, not a launch count that
     * moved; the settings the config covers, not every other one. Combined,
     * not merged: they emit once when every source has its first value, so
     * a change made before anyone collected is not lost, and then once per
     * change of any. Merged, each source's first value was a write-back pass
     * of its own - five per start, each hashing the managed wallpaper
     * (#167). Every source emits its current value on collection (DataStore,
     * Room); one that never did would hold write-back back entirely, which
     * ConfigWriteBackTest's "changes arrive on collection" guards. Nothing
     * here reads the whole state: an app launch or an unrelated setting
     * costs nothing. The wallpaper is not a source: a wallpaper picked on
     * the device has no upload name, so it cannot be written back.
     */
    override fun changes(): Flow<Unit> = combine(
        listOf(
            settings.changes(),
            favoriteApps().map { apps -> apps.map { it.key } }.distinctUntilChanged().map { },
            searchActions.changes(),
            apps.changes(),
            tags.changes(),
        ) + GridLayouts.All.map { layout -> homeGridRepository.observe(layout).distinctUntilChanged().map { } },
    ) { }

    override suspend fun readState(): ConfigState {
        // Profiles first: before their first read every profile lookup is
        // null, and a work favorite would read back as absent.
        profileResolver.awaitRead()
        val settingsState = settings.readState()
        val favorites = favoriteApps().first().mapNotNull { it.toFavorite() }
        val wallpaper = wallpapers.current()

        // One snapshot of the layouts and the flag, under the lock the default
        // row writes under: read apart, the row could land in between and look
        // like an initialised empty grid, and the differ would keep it (#92).
        val (gridLayouts, gridInitialized) = homeGridInitLock.withLock {
            GridLayouts.All.associateWith { layout ->
                GridLayoutConfig(homeGridRepository.observe(layout).first().map { it.toConfig() })
            } to homeGridInitFlag.isInitialized()
        }

        // A widget whose provider is missing here is kept as written; its
        // layout is applied again on each reload, which looks the provider up
        // and reports it, until it is there.
        val unsettled = gridLayouts.filterValues { layout ->
            layout.items.any { item ->
                !item.isFavorites && gridLimits.lookup(item.widget, item.profile, settingsState.gridColumns) == null
            }
        }.keys

        return settingsState.copy(
            favorites = favorites,
            gridLayouts = gridLayouts,
            gridInitialized = gridInitialized,
            unsettledGridLayouts = unsettled,
            searchActions = searchActions.read(),
            apps = apps.read(),
            tags = tags.read(),
            wallpaperImage = wallpaper?.image,
            wallpaperTarget = wallpaper?.target,
            gestures = gesturesOf(settingsState.gestures),
            shortcutsExcluded = shortcutsExcludedOf(settings.readShortcutBlocklist()),
        )
    }

    override suspend fun apply(mutations: List<ConfigMutation>): List<Diagnostic> = applyAndCapture(mutations).diagnostics

    /**
     * Each section as its write left it, taken from the write itself: the
     * settings from the DataStore update's own result, the grid, favorites
     * and search actions from what went into their repositories. A section
     * whose write threw is not among [ConfigStore.Applied.sections]; one
     * written with entries left out (an app not installed) is, as written.
     */
    override suspend fun applyAndCapture(mutations: List<ConfigMutation>): ConfigStore.Applied {
        // The reload an ingest starts runs right after the process does:
        // answered before the profiles are read, every favorite in a work
        // profile was profile-unavailable. Once read, the synchronous lookups
        // below (favorites, the grid's widget limits) answer for real.
        profileResolver.awaitRead()
        val diagnostics = mutableListOf<Diagnostic>()
        val sections = mutableSetOf<String>()

        var written = ConfigState()
        val settingsMutations = mutations.filter { it.isSettingsBacked }
        if (settingsMutations.isNotEmpty()) {
            try {
                written = settings.applyAndRead(settingsMutations)
                sections += settingsMutations.filter { it !is ConfigMutation.SetGrid }.map { it.section }
            } catch (e: Exception) {
                for (mutation in settingsMutations) {
                    diagnostics += mutation.applyFailed(e)
                }
            }
        }
        val settingsWritten = settingsMutations.isNotEmpty() && diagnostics.isEmpty()

        for (mutation in mutations) {
            when (mutation) {
                is ConfigMutation.SetGrid -> try {
                    val layouts = mutableMapOf<String, GridLayoutConfig>()
                    diagnostics += applyGrid(mutation, layouts)
                    written = written.copy(
                        gridLayouts = GridLayouts.All.associateWith { layout ->
                            layouts[layout] ?: GridLayoutConfig(homeGridRepository.observe(layout).first().map { it.toConfig() })
                        },
                        gridInitialized = homeGridInitFlag.isInitialized(),
                    )
                    if (settingsWritten) sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetFavorites -> try {
                    val (applied, favorites) = applyFavorites(mutation)
                    diagnostics += applied
                    written = written.copy(favorites = favorites)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetGestures -> try {
                    val (applied, gestures) = applyGestures(mutation)
                    diagnostics += applied
                    written = written.copy(gestures = gestures)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetSearchActions -> try {
                    val (replaced, actions) = searchActions.replaceAndRead(mutation.actions, "search.actions")
                    diagnostics += replaced
                    written = written.copy(searchActions = actions)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetApps -> try {
                    val (replaced, customizations) = apps.replaceAndRead(mutation.apps)
                    diagnostics += replaced
                    written = written.copy(apps = customizations)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetTags -> try {
                    val (replaced, carried) = tags.replaceAndRead(mutation.tags)
                    diagnostics += replaced
                    written = written.copy(tags = carried)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetShortcutsExcluded -> try {
                    val (applied, excluded) = applyShortcutsExcluded(mutation)
                    diagnostics += applied
                    written = written.copy(shortcutsExcluded = excluded)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                is ConfigMutation.SetWallpaper -> try {
                    diagnostics += wallpapers.apply(mutation.image, mutation.target)
                    // Nothing on the device writes the managed image, so a read is the write.
                    val wallpaper = wallpapers.current()
                    written = written.copy(wallpaperImage = wallpaper?.image, wallpaperTarget = wallpaper?.target)
                    sections += mutation.section
                } catch (e: Exception) {
                    diagnostics += mutation.applyFailed(e)
                }

                else -> Unit
            }
        }

        // Reported whether or not this reload touched the wallpaper: the
        // record makes the differ see no difference, so without this a second
        // run would come back silently green for a wallpaper that is still
        // waiting for the profile to be looked at (#37).
        wallpapers.pending()?.let {
            diagnostics += Diagnostic(
                DiagnosticCode.WallpaperPendingForeground,
                "appearance.wallpaper.image",
                "'${it.image}' is recorded but not set yet: the system crops a static " +
                        "wallpaper only for the current user, so applying it now would cost " +
                        "a crop pass for a result nobody sees. The launcher sets it the next " +
                        "time this profile is in the foreground.",
            )
        }

        return ConfigStore.Applied(diagnostics, written, sections)
    }

    /**
     * Writes every layout the mutation names through the layout engine:
     * items without geometry are placed at the first free cells in array
     * order, then the whole layout is normalised against this device's grid
     * (below-minimum spans enlarged, crossings of the fold line nudged,
     * overlaps re-placed, what does not fit dropped), each correction a
     * warning diagnostic. Items that already exist in the layout keep their
     * device-local AppWidget id, so a re-push does not re-bind anything.
     */
    private suspend fun applyGrid(
        mutation: ConfigMutation.SetGrid,
        written: MutableMap<String, GridLayoutConfig>,
    ): List<Diagnostic> {
        val layouts = mutation.layouts ?: return emptyList()
        val diagnostics = mutableListOf<Diagnostic>()
        val columns = mutation.columns ?: settings.readState().gridColumns
        for ((layoutKey, layout) in layouts) {
            diagnostics += applyLayout(layoutKey, layout, columns, written)
        }
        return diagnostics
    }

    /**
     * Rows for a layout of another form factor: enough for every placed item
     * where the file puts it, at least the default, and room below for the
     * items without a position, so nothing is clamped or dropped. Heights are
     * the ones the engine will use: the provider's default for an omitted
     * one, clamped to the provider's limits. The validator bounds every
     * coordinate and size, so the sum stays small.
     */
    private fun rowsToKeep(sized: List<SizedItem>): Int {
        fun SizedItem.height() =
            (config.h ?: limits.default.h).coerceIn(limits.limits.minH, limits.limits.maxH)
        val placedBottom = sized.filter { it.config.hasPosition }.maxOfOrNull { it.config.y!! + it.height() } ?: 0
        val unplaced = sized.filter { !it.config.hasPosition }.sumOf { it.height() }
        return maxOf(MeasuredGridRows.DefaultRows, placedBottom) + unplaced
    }

    private class SizedItem(val index: Int, val config: GridItemConfig, val limits: ProviderLimits)

    private suspend fun applyLayout(
        layoutKey: String,
        layout: GridLayoutConfig,
        columns: Int,
        written: MutableMap<String, GridLayoutConfig>,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val basePath = "home.grid.layouts.$layoutKey.items"
        val isFold = layoutKey == GridLayouts.Fold
        val specColumns = if (isFold) columns * 2 else columns

        // Limits first, then geometry: items with a position keep it, items
        // without one are placed after them, in array order, at the first
        // free cells (D5).
        val sized = layout.items.mapIndexed { index, item ->
            val limits = if (item.isFavorites) {
                ProviderLimits(default = CellSize(specColumns, 1), limits = SizeLimits.Unbounded)
            } else {
                gridLimits.lookup(item.widget, item.profile, columns) ?: run {
                    diagnostics += Diagnostic(
                        DiagnosticCode.UnknownWidgetProvider,
                        "$basePath[$index]",
                        "No installed widget provider matches '${item.widget}'" +
                                item.profile?.let { " in the ${it.name.lowercase()} profile" }.orEmpty() +
                                "; the item is kept and shown as unavailable",
                    )
                    ProviderLimits(default = CellSize(1, 1), limits = SizeLimits.Unbounded)
                }
            }
            SizedItem(index, item, limits)
        }
        val spec = GridSpec(
            columns = specColumns,
            // A layout this device does not render is kept as written (#90):
            // its rows are unknown here, so they are as many as the file needs.
            rows = gridRows.rows(layoutKey) ?: rowsToKeep(sized),
            foldColumn = if (isFold) columns else null,
        )

        val order = layout.items.withIndex().associate { it.value.id to it.index }

        // A position anchors the item; a missing size is the provider's
        // default. Items without a position are placed after them.
        val placed = mutableListOf<GridItem>()
        for (s in sized) {
            val item = s.config
            if (item.hasPosition) {
                placed += GridItem(
                    id = item.id,
                    span = Span(item.x!!, item.y!!, item.w ?: s.limits.default.w, item.h ?: s.limits.default.h),
                    limits = s.limits.limits,
                    mayCrossFold = item.isFavorites,
                )
            }
        }
        for (s in sized) {
            val item = s.config
            if (item.hasPosition) continue
            val w = item.w ?: s.limits.default.w
            val h = item.h ?: s.limits.default.h
            val candidate = GridItem(
                id = item.id,
                span = Span(0, 0, w, h),
                limits = s.limits.limits,
                mayCrossFold = item.isFavorites,
            )
            val free = GridLayout.place(spec, placed, candidate)
            if (free == null) {
                diagnostics += Diagnostic(
                    DiagnosticCode.GridOverflow,
                    "$basePath[${s.index}]",
                    "No free ${w}x$h cells left for '${item.id}'; the item was dropped",
                )
                continue
            }
            // Placement fits the size the way normalize does, and normalize
            // then sees the fitted span; so the fit is said here (#140).
            for (issue in GridLayout.fitSize(spec, candidate).issues) {
                issue.toDiagnostic(basePath, order, spec)?.let { diagnostics += it }
            }
            placed += free
        }

        // Back into array order, then normalise against this device's grid.
        val result = GridLayout.normalize(spec, placed.sortedBy { order[it.id] })
        // A move or a nudge is only one when the file asked for a position:
        // an item without one is placed, and nothing was overridden.
        for (issue in result.issues) {
            val id = when (issue) {
                is LayoutIssue.Moved -> issue.id
                is LayoutIssue.NudgedOffFold -> issue.id
                else -> null
            }
            if (id != null && !layout.items[order.getValue(id)].hasPosition) continue
            issue.toDiagnostic(basePath, order, spec)?.let { diagnostics += it }
        }
        // From the read of what is stored to the flag: one critical section,
        // shared with the default row (HomeGridDefaults) through the init lock.
        homeGridInitLock.withLock {
            val existing = homeGridRepository.observe(layoutKey).first().associateBy { it.id }
            val items = result.items.mapIndexed { position, gridItem ->
                val config = layout.items[order.getValue(gridItem.id)]
                val previous = existing[gridItem.id]?.takeIf {
                    it.widget == config.widget && it.profile == config.profile?.serialName()
                }
                HomeGridItem(
                    layout = layoutKey,
                    id = gridItem.id,
                    widget = config.widget,
                    profile = config.profile?.serialName(),
                    x = gridItem.span.x,
                    y = gridItem.span.y,
                    w = gridItem.span.w,
                    h = gridItem.span.h,
                    appWidgetId = previous?.appWidgetId,
                    config = HomeGridItemConfig(
                        borderless = config.borderless ?: GridItemConfig.OptionDefaults.getValue("borderless"),
                        background = config.background ?: GridItemConfig.OptionDefaults.getValue("background"),
                        themeColors = config.themeColors ?: GridItemConfig.OptionDefaults.getValue("themeColors"),
                        mute = config.mute ?: GridItemConfig.OptionDefaults.getValue("mute"),
                    ),
                    position = position,
                )
            }
            homeGridRepository.replace(layoutKey, items)
            written[layoutKey] = GridLayoutConfig(items.map { it.toConfig() })
            // A config that names a layout is the grid's first content as much as
            // the default row is: from here on an empty layout means empty.
            homeGridInitFlag.markInitialized()
        }
        return diagnostics
    }

    /**
     * A diagnostic reports what the engine recorded; it never re-derives it
     * from the configuration. A copy of the engine's rule is subtly wrong in
     * exactly the case nobody wrote down, because it needs inputs the engine
     * does not: the store's own fold-nudge check needed a `w`, so an item the
     * file left without one - nudged by the engine at its default width - was
     * never reported (#174), and a size decided before normalize claimed an
     * effective size for an item normalize then dropped (#170). The engine
     * says what it did; this only gives it a name, a path and words.
     */
    /**
     * The overlap a push reports is where the engine found it: at the
     * requested position, or where a slide into the grid or a nudge off the
     * fold placed the item first. That place is named when it is not the
     * requested one (#174 review).
     */
    private fun movedMessage(id: String, from: Span, to: Span, pushed: LayoutIssue.Push?): String = when {
        pushed == null ->
            "'$id' asks for x=${from.x} y=${from.y}, which puts its ${to.w}x${to.h} cells " +
                "outside the grid; it was moved to x=${to.x} y=${to.y}"
        pushed.from.x == from.x && pushed.from.y == from.y ->
            "'$id' asks for x=${from.x} y=${from.y}, which overlaps '${pushed.by}'; " +
                "it was moved down to x=${to.x} y=${to.y}"
        else ->
            "'$id' asks for x=${from.x} y=${from.y}; placed at x=${pushed.from.x} y=${pushed.from.y}, " +
                "it overlaps '${pushed.by}', so it was moved down to x=${to.x} y=${to.y}"
    }

    private fun LayoutIssue.toDiagnostic(basePath: String, order: Map<String, Int>, spec: GridSpec): Diagnostic? {
        fun path(id: String) = "$basePath[${order[id] ?: -1}]"
        return when (this) {
            is LayoutIssue.BelowMinimum -> Diagnostic(
                DiagnosticCode.WidgetTooSmall,
                path(id),
                "'$id' asks for ${requested.w}x${requested.h} cells, below the widget's " +
                        "minimum; it was enlarged to ${clamped.w}x${clamped.h}",
            )

            is LayoutIssue.OutOfBounds -> Diagnostic(
                DiagnosticCode.GridOutOfBounds,
                path(id),
                "'$id' does not fit the grid at ${span.w}x${span.h} cells; the item was dropped",
            )

            is LayoutIssue.CrossesFold -> Diagnostic(
                DiagnosticCode.GridCrossesFold,
                path(id),
                "'$id' spans the fold line, which only the favorites widget may, and is too wide " +
                        "for either side; the item was dropped",
            )

            is LayoutIssue.Overflow -> Diagnostic(
                DiagnosticCode.GridOverflow,
                path(id),
                "No free cells left for '$id'; the item was dropped",
            )

            // #140: read-back serves the shrunk span, which is what is in
            // effect; the file keeps what it asked for, and this says why the
            // two differ.
            is LayoutIssue.AboveMaximum -> Diagnostic(
                DiagnosticCode.WidgetTooLarge,
                path(id),
                "'$id' asks for ${requested.w}x${requested.h} cells, " +
                    when (bound) {
                        LayoutIssue.Bound.Widget -> "above the widget's maximum"
                        LayoutIssue.Bound.Grid -> "more than the grid's ${spec.columns}x${spec.rows}"
                        LayoutIssue.Bound.Both -> "above the widget's maximum and the grid's ${spec.columns}x${spec.rows}"
                    } + "; it was shrunk to ${clamped.w}x${clamped.h}",
            )

            // #140: the file keeps where it put the item; the report says
            // where the item went and why. The move itself is unchanged.
            is LayoutIssue.Moved -> Diagnostic(
                DiagnosticCode.GridItemMoved,
                path(id),
                movedMessage(id, from, to, pushed),
            )

            // The engine nudges a crossing item to one side (the right thing
            // for a hand move too); a file that asked for the crossing is
            // told, so the host does not learn it from a read-back that
            // differs from what it pushed. Reported from what the engine did,
            // not recomputed from the file (#174 simplify).
            is LayoutIssue.NudgedOffFold -> Diagnostic(
                DiagnosticCode.GridCrossesFold,
                path(id),
                "'$id' spans the fold line, which only the favorites widget may; it was moved to one side",
            )

            // An overlap is reported through what the engine did about it:
            // the later item was moved (Moved) or dropped (Overflow).
            is LayoutIssue.Overlap -> null
        }
    }

    private fun HomeGridItem.toConfig(): GridItemConfig = GridItemConfig(
        id = id,
        widget = widget,
        x = x, y = y, w = w, h = h,
        profile = profile?.let { name -> ConfigProfile.entries.firstOrNull { it.serialName() == name } },
        borderless = config.borderless,
        background = config.background,
        themeColors = config.themeColors,
        mute = config.mute,
    )

    private fun ConfigProfile.serialName(): String = name.lowercase()

    /**
     * Resolves the configured favorites to installed apps and writes them in
     * config order. Unresolvable entries (missing profile, uninstalled app)
     * produce error diagnostics and are skipped.
     *
     * The file names apps only, so it manages the app pins and nothing else
     * (#3 D4): shortcuts, tags and contacts pinned on the device keep their
     * relative order and follow the configured apps. Automatically pinned
     * favorites are outside the config's scope and are preserved.
     */
    /** Writes the favorites; returns the diagnostics and the favorites as written. */
    private suspend fun applyFavorites(
        mutation: ConfigMutation.SetFavorites,
    ): Pair<List<Diagnostic>, List<Favorite>> {
        val diagnostics = mutableListOf<Diagnostic>()
        val resolved = mutableListOf<SavableSearchable>()

        mutation.favorites.forEachIndexed { index, favorite ->
            val path = "home.favorites[$index]"
            when (val found = resolve(favorite)) {
                is Resolved.App -> resolved += found.app
                Resolved.NoProfile -> diagnostics += Diagnostic(
                    DiagnosticCode.ProfileUnavailable,
                    path,
                    "The ${favorite.profile.name.lowercase()} profile does not exist " +
                            "on this device; favorite '${favorite.packageName}' was skipped",
                )
                Resolved.NotInstalled -> diagnostics += Diagnostic(
                    DiagnosticCode.FavoriteUnavailable,
                    path,
                    "App '${favorite.packageName}' is not installed in the " +
                            "${favorite.profile.name.lowercase()} profile; it was skipped",
                )
            }
        }

        // One transaction reads the other pins and writes the new order, so a
        // pin made while this reload runs is never replaced by a stale copy.
        searchableRepository.replaceManuallySortedAwaited(types = listOf(AppDomain), items = resolved)
        return diagnostics to resolved.mapNotNull { it.toFavorite() }
    }

    /** An app the file names, as installed here. */
    private sealed interface Resolved {
        data class App(val app: Application) : Resolved
        data object NoProfile : Resolved
        data object NotInstalled : Resolved
    }

    private suspend fun resolve(favorite: Favorite): Resolved {
        val profile = profileResolver.getProfile(favorite.profile.toProfileType()) ?: return Resolved.NoProfile
        val app = appRepository.findOne(favorite.packageName, profile.userHandle).first() ?: return Resolved.NotInstalled
        return Resolved.App(app)
    }

    /**
     * Writes the gestures (#3 slice 2): actions by name, an app by the key of
     * the installed app, saved first where the launcher looks it up. An app
     * that is not here leaves its gesture as it was, reported like a
     * favorite. Returns the diagnostics and the gestures as written.
     */
    private suspend fun applyGestures(
        mutation: ConfigMutation.SetGestures,
    ): Pair<List<Diagnostic>, Map<Gesture, GestureConfig?>> {
        val diagnostics = mutableListOf<Diagnostic>()
        val actions = mutableMapOf<Gesture, GestureActionName>()
        val launches = mutableMapOf<Gesture, String>()
        for ((gesture, value) in mutation.gestures) {
            val path = gesture.path
            when (value) {
                is GestureConfig.Action -> actions[gesture] = value.action
                is GestureConfig.App -> when (val found = resolve(value.app)) {
                    is Resolved.App -> {
                        searchableRepository.insertAwaited(listOf(found.app))
                        launches[gesture] = found.app.key
                    }
                    Resolved.NoProfile -> diagnostics += Diagnostic(
                        DiagnosticCode.ProfileUnavailable,
                        path,
                        "The ${value.app.profile.name.lowercase()} profile does not exist on this device; " +
                            "$path keeps what it did",
                    )
                    Resolved.NotInstalled -> diagnostics += Diagnostic(
                        DiagnosticCode.GestureAppUnavailable,
                        path,
                        "App '${value.app.packageName}' is not installed in the " +
                            "${value.app.profile.name.lowercase()} profile; $path keeps what it did",
                    )
                }
            }
        }
        val written = settings.applyGestures(actions, launches)
        // A launch reads as null from the settings; written, it is the file's app.
        return diagnostics to written.gestures + mutation.gestures.filterKeys(launches::containsKey)
    }

    /**
     * The settings' gestures with each launch named (#3 slice 2): the app it
     * opens, or null for what the file cannot name - a shortcut, or an app
     * that is gone.
     */
    private suspend fun gesturesOf(settingsGestures: Map<Gesture, GestureConfig?>): Map<Gesture, GestureConfig?> {
        val keys = settings.readGestureLaunchKeys()
        if (keys.isEmpty()) return settingsGestures
        val items = searchableRepository.getByKeys(keys.values.distinct()).first().associateBy { it.key }
        return settingsGestures + keys.mapValues { (_, key) -> items[key]?.toFavorite()?.let { GestureConfig.App(it) } }
    }

    /**
     * Writes `search.shortcutsExcluded` (#229) as the launcher stores it,
     * `packageName:userSerial`. An app needs no install: the list says whose
     * shortcuts to leave out, whenever they appear. A stored entry whose
     * serial is no profile here - a removed profile, whose serial is never
     * reused - is one the file cannot name, and it is kept rather than
     * deleted for being unnamed. Returns the diagnostics and the list as written.
     */
    private suspend fun applyShortcutsExcluded(
        mutation: ConfigMutation.SetShortcutsExcluded,
    ): Pair<List<Diagnostic>, List<Favorite>> {
        val diagnostics = mutableListOf<Diagnostic>()
        val written = mutableListOf<Favorite>()
        val keys = mutableSetOf<String>()
        mutation.shortcutsExcluded.forEachIndexed { index, app ->
            val profile = profileResolver.getProfile(app.profile.toProfileType())
            if (profile == null) {
                diagnostics += Diagnostic(
                    DiagnosticCode.ProfileUnavailable,
                    "search.shortcutsExcluded[$index]",
                    "The ${app.profile.name.lowercase()} profile does not exist on this device; " +
                        "'${app.packageName}' was not excluded",
                )
            } else {
                keys += "${app.packageName}:${profile.serial}"
                written += app
            }
        }
        // One update: the entries kept are the ones stored as it commits.
        settings.updateShortcutBlocklist { current -> current.filterTo(mutableSetOf()) { blockedApp(it) == null } + keys }
        return diagnostics to written
    }

    /** The stored blocklist as the file names it: by profile, the unnamed left out. */
    private fun shortcutsExcludedOf(blocklist: Set<String>): List<Favorite> =
        blocklist.mapNotNull { key -> blockedApp(key) }

    private fun blockedApp(key: String): Favorite? {
        val serial = key.substringAfterLast(':', "").toLongOrNull() ?: return null
        val profile = ConfigProfile.entries.firstOrNull { profileResolver.getProfile(it.toProfileType())?.serial == serial }
            ?: return null
        return Favorite(key.substringBeforeLast(':'), profile)
    }

    private suspend fun SavableSearchable.toFavorite(): Favorite? {
        val app = this as? Application ?: return null
        val profile = profileResolver.getProfile(app.user)?.type ?: return null
        return Favorite(
            packageName = app.componentName.packageName,
            profile = profile.toConfigProfile(),
        )
    }

    private fun ConfigMutation.applyFailed(cause: Exception): Diagnostic {
        return Diagnostic(
            DiagnosticCode.ApplyFailed,
            section,
            "Failed to apply section '$section': ${cause.message ?: cause.javaClass.simpleName}",
        )
    }

    private companion object {
        /**
         * Domain of [Application] searchables (`LauncherApp.Domain`, which is
         * internal to `:data:applications`).
         */
        const val AppDomain = "app"
    }
}

private val ConfigMutation.isSettingsBacked: Boolean
    get() = when (this) {
        is ConfigMutation.SetIcons,
        is ConfigMutation.SetSearchBarPosition,
        is ConfigMutation.SetSearchBarFixed,
        is ConfigMutation.SetSystemBars,
        is ConfigMutation.SetRotationLock,
        is ConfigMutation.SetWidgetsEnabled,
        // columns and locked live in settings; layouts are applied below too.
        is ConfigMutation.SetGrid,
        is ConfigMutation.SetGlass,
        is ConfigMutation.SetTheme,
        is ConfigMutation.SetSearch,
        -> true

        // Gestures are settings too, but their apps are resolved here first.
        is ConfigMutation.SetFavorites,
        is ConfigMutation.SetApps,
        is ConfigMutation.SetTags,
        is ConfigMutation.SetSearchActions,
        is ConfigMutation.SetWallpaper,
        is ConfigMutation.SetGestures,
        // Stored by user serial, which only this store can map.
        is ConfigMutation.SetShortcutsExcluded,
        -> false
    }

package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.SearchActionConfig
import de.mm20.launcher2.config.SearchConfig
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.config.WallpaperTarget
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.GlassContrast
import de.mm20.launcher2.config.GridItemConfig
import de.mm20.launcher2.config.GridLayoutConfig
import de.mm20.launcher2.config.GridLayouts
import de.mm20.launcher2.grid.CellSize
import de.mm20.launcher2.grid.SizeLimits
import de.mm20.launcher2.homegrid.GridRowsSource
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridItemConfig
import de.mm20.launcher2.homegrid.HomeGridInitFlag
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.searchable.VisibilityLevel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import de.mm20.launcher2.config.Profile as ConfigProfile

/** Fakes for [DefaultConfigStore]'s dependencies, shared by its tests and the round trip. */

internal class FakeLauncherConfigSettings(
    var state: ConfigState = ConfigState(),
    var applyFailure: Exception? = null,
) : LauncherConfigSettings {
    val applyCalls = mutableListOf<List<ConfigMutation>>()

    override suspend fun readState(): ConfigState = state

    override suspend fun apply(mutations: List<ConfigMutation>) {
        applyCalls += mutations
        applyFailure?.let { throw it }
        for (mutation in mutations) {
            when (mutation) {
                is ConfigMutation.SetIcons -> state = state.copy(
                    themedIcons = mutation.themed ?: state.themedIcons,
                    enforceThemedIcons = mutation.enforceThemed ?: state.enforceThemedIcons,
                    iconPack = mutation.pack ?: state.iconPack,
                )

                is ConfigMutation.SetSearchBarPosition ->
                    state = state.copy(searchBarPosition = mutation.position)

                is ConfigMutation.SetGrid ->
                    state = state.copy(
                        gridColumns = mutation.columns ?: state.gridColumns,
                        gridLocked = mutation.locked ?: state.gridLocked,
                        gridLabels = mutation.labels ?: state.gridLabels,
                    )

                is ConfigMutation.SetGlass ->
                    state = state.copy(
                        glassBlur = mutation.blur ?: state.glassBlur,
                        glassTint = mutation.tint ?: state.glassTint,
                        glassRadius = mutation.radius ?: state.glassRadius,
                        glassContrast = mutation.contrast ?: state.glassContrast,
                    )

                is ConfigMutation.SetWidgetsEnabled ->
                    state = state.copy(widgetsEnabled = mutation.enabled)

                is ConfigMutation.SetSearch ->
                    state = state.copy(
                        search = state.search.copy(favorites = mutation.search.favorites ?: state.search.favorites),
                    )

                else -> Unit
            }
        }
    }
}

internal class FakeInitFlag : HomeGridInitFlag {
    var initialized = false
    /** Answers whether the init lock is held; each read of the flag records it. */
    var lockProbe: (() -> Boolean)? = null
    val lockedDuringRead = mutableListOf<Boolean>()
    override suspend fun isInitialized(): Boolean {
        lockProbe?.let { lockedDuringRead += it() }
        return initialized
    }
    override suspend fun markInitialized() {
        initialized = true
    }
}

internal class FakeHomeGridRepository : HomeGridRepository {
    val layouts = mutableMapOf<String, List<HomeGridItem>>()
    var replaceCalls = 0

    /** Answers whether the init lock is held; each collected read records it. */
    var lockProbe: (() -> Boolean)? = null
    val lockedDuringObserve = mutableListOf<Boolean>()

    override fun observe(layout: String): Flow<List<HomeGridItem>> = flow {
        lockProbe?.let { lockedDuringObserve += it() }
        emit(layouts[layout] ?: emptyList())
    }

    var lockedDuringReplace: Boolean? = null
    var onReplace: (suspend () -> Unit)? = null

    override suspend fun replace(layout: String, items: List<HomeGridItem>) {
        replaceCalls++
        onReplace?.invoke()
        layouts[layout] = items
    }

    override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) =
        throw NotImplementedError()

    override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) =
        throw NotImplementedError()

    override suspend fun delete(layout: String, id: String) = throw NotImplementedError()
}

internal class FakeGridLimitsSource : GridLimitsSource {
    /** Providers the fake knows; the clock is installed by default. */
    val limits = mutableMapOf(
        "com.android.deskclock/.DigitalAppWidgetProvider" to
                ProviderLimits(default = CellSize(4, 2), limits = SizeLimits(2, 1, 4, 4)),
    )

    override fun lookup(widget: String, profile: ConfigProfile?, columns: Int): ProviderLimits? = limits[widget]
}

/** [own] is the layout this device renders; null answers for every layout. */
internal class FakeGridRowsSource(var rows: Int = 6, var own: String? = null) : GridRowsSource {
    override fun rows(layout: String): Int? = if (own == null || layout == own) rows else null
}

internal class FakeSavableSearchableRepository : SavableSearchableRepository {
    var manuallySorted: List<SavableSearchable> = emptyList()
    var automaticallySorted: List<SavableSearchable> = emptyList()

    override fun get(
        includeTypes: List<String>?,
        excludeTypes: List<String>?,
        minPinnedLevel: PinnedLevel,
        maxPinnedLevel: PinnedLevel,
        minVisibility: VisibilityLevel,
        maxVisibility: VisibilityLevel,
        limit: Int,
    ): Flow<List<SavableSearchable>> {
        val items = buildList {
            if (PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel) {
                addAll(manuallySorted)
            }
            if (PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel) {
                addAll(automaticallySorted)
            }
        }
        return flowOf(items.filter { includeTypes == null || it.domain in includeTypes })
    }

    /** The real one's semantics; its atomicity is the Room transaction, tested there. */
    override suspend fun replaceManuallySortedAwaited(types: List<String>, items: List<SavableSearchable>) {
        manuallySorted = items + manuallySorted.filter { it.domain !in types }
    }

    override fun insert(searchable: SavableSearchable) = throw NotImplementedError()
    override fun upsert(
        searchable: SavableSearchable,
        visibility: VisibilityLevel?,
        pinned: Boolean?,
        launchCount: Int?,
        weight: Double?,
    ) = throw NotImplementedError()

    override fun update(
        searchable: SavableSearchable,
        visibility: VisibilityLevel?,
        pinned: Boolean?,
        launchCount: Int?,
        weight: Double?,
    ) = throw NotImplementedError()

    override fun replace(key: String, newSearchable: SavableSearchable) =
        throw NotImplementedError()

    override fun touch(searchable: SavableSearchable) = throw NotImplementedError()
    override fun getKeys(
        includeTypes: List<String>?,
        excludeTypes: List<String>?,
        minPinnedLevel: PinnedLevel,
        maxPinnedLevel: PinnedLevel,
        minVisibility: VisibilityLevel,
        maxVisibility: VisibilityLevel,
        limit: Int,
    ): Flow<List<String>> = throw NotImplementedError()

    override fun isPinned(searchable: SavableSearchable): Flow<Boolean> =
        throw NotImplementedError()

    override fun getVisibility(searchable: SavableSearchable): Flow<VisibilityLevel> =
        throw NotImplementedError()

    override fun updateFavorites(
        manuallySorted: List<SavableSearchable>,
        automaticallySorted: List<SavableSearchable>,
    ) = throw NotImplementedError()

    override fun sortByRelevance(keys: List<String>): Flow<List<String>> =
        throw NotImplementedError()

    override fun sortByWeight(keys: List<String>): Flow<List<String>> =
        throw NotImplementedError()

    override fun getWeights(keys: List<String>): Flow<Map<String, Double>> =
        throw NotImplementedError()

    override fun delete(searchable: SavableSearchable) = throw NotImplementedError()
    override fun getByKeys(keys: List<String>): Flow<List<SavableSearchable>> =
        throw NotImplementedError()

    override suspend fun cleanupDatabase(): Int = throw NotImplementedError()
}

internal class FakeWallpaperStore : WallpaperStore {
    var state: WallpaperState? = null
    val applied = mutableListOf<Pair<String, WallpaperTarget>>()
    override suspend fun current(): WallpaperState? = state
    override suspend fun apply(image: String, target: WallpaperTarget): List<Diagnostic> {
        applied += image to target
        state = WallpaperState(image, target)
        return emptyList()
    }
    override suspend fun ensureRendered(): Boolean = false
    var pending: WallpaperState? = null
    override suspend fun pending(): WallpaperState? = pending
}

internal class FakeAppRepository : AppRepository {
    val apps = mutableMapOf<Pair<String, UserHandle>, Application>()

    override fun findOne(packageName: String, user: UserHandle): Flow<Application?> {
        return flowOf(apps[packageName to user])
    }

    override fun findMany() = flowOf(persistentListOf<Application>())
    override fun search(query: String): Flow<List<Application>> {
        return flowOf(emptyList())
    }
}

internal class FakeProfileResolver(
    var personal: Profile?,
    var work: Profile?,
) : ProfileResolver {
    override fun getProfile(type: Profile.Type): Profile? {
        return when (type) {
            Profile.Type.Personal -> personal
            Profile.Type.Work -> work
            else -> null
        }
    }

    override suspend fun getProfile(userHandle: UserHandle): Profile? {
        return listOfNotNull(personal, work).firstOrNull { it.userHandle == userHandle }
    }
}

internal class FakeApplication(
    override val componentName: ComponentName,
    override val user: UserHandle,
) : Application {
    override val key: String = "app://${componentName.packageName}"
    override val domain: String = "app"
    override val label: String = componentName.packageName
    override val isSuspended: Boolean = false
    override val versionName: String? = null
    override val canUninstall: Boolean = false
    override val canShareApk: Boolean = false

    override fun overrideLabel(label: String): SavableSearchable = this
    override fun launch(context: Context, options: Bundle?): Boolean = false
    override fun getPlaceholderIcon(context: Context): StaticLauncherIcon =
        throw NotImplementedError()

    override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
    override fun uninstall(context: Context) = throw NotImplementedError()
    override fun openAppDetails(context: Context) = throw NotImplementedError()
}

/** A pinned item that is not an app: a shortcut, a tag or a contact. */
internal class FakeItem(
    override val key: String,
    override val domain: String,
) : SavableSearchable {
    override val label: String = key
    override val labelOverride: String? = null
    override val preferDetailsOverLaunch: Boolean = false
    override fun overrideLabel(label: String): SavableSearchable = this
    override fun launch(context: Context, options: Bundle?): Boolean = false
    override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw NotImplementedError()
    override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
}

internal object TestUsers {
    fun userHandleFor(id: Int): UserHandle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(id)
            parcel.setDataPosition(0)
            return UserHandle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}

internal class FakeSearchActionStore : SearchActionStore {
    var actions: List<SearchActionConfig> = emptyList()
    var reports: List<Diagnostic> = emptyList()
    val replaced = mutableListOf<Pair<List<SearchActionConfig>, String>>()

    override suspend fun read(): List<SearchActionConfig> = actions

    override suspend fun replace(actions: List<SearchActionConfig>, basePath: String): List<Diagnostic> {
        replaced += actions to basePath
        this.actions = actions
        return reports
    }
}

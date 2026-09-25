package de.mm20.launcher2.ui.settings.icons

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.icons.DefaultIconPack
import de.mm20.launcher2.icons.IconPack
import de.mm20.launcher2.icons.IconPackManager
import de.mm20.launcher2.icons.IconService
import de.mm20.launcher2.icons.LauncherIcon
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.IconShape
import de.mm20.launcher2.preferences.ui.BadgeSettings
import de.mm20.launcher2.preferences.ui.IconSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.services.favorites.FavoritesService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class IconsSettingsScreenVM(
    private val uiSettings: UiSettings,
    private val iconSettings: IconSettings,
    private val badgeSettings: BadgeSettings,
    private val iconService: IconService,
    private val favoritesService: FavoritesService,
    private val permissionsManager: PermissionsManager,
    private val iconPackManager: IconPackManager,
    private val appRepository: AppRepository,
) : ViewModel() {

    val grid = uiSettings.gridSettings

    fun setColumnCount(columnCount: Int) {
        uiSettings.setGridColumnCount(columnCount)
    }

    fun setIconSize(iconSize: Int) {
        uiSettings.setGridIconSize(iconSize)
    }

    fun setShowLabels(showLabels: Boolean) {
        uiSettings.setGridShowLabels(showLabels)
    }

    fun setShowList(showList: Boolean) {
        uiSettings.setGridShowList(showList)
    }

    fun setShowListIcons(showIcons: Boolean) {
        uiSettings.setGridShowListIcons(showIcons)
    }

    val iconShape = uiSettings.iconShape
    fun setIconShape(iconShape: IconShape) {
        uiSettings.setIconShape(iconShape)
    }

    val icons = iconSettings
    fun setAdaptifyLegacyIcons(adaptify: Boolean) {
        iconSettings.setAdaptifyLegacyIcons(adaptify)
    }

    fun setThemedIcons(themedIcons: Boolean) {
        iconSettings.setThemedIcons(themedIcons)
    }

    fun setForceThemedIcons(forceThemedIcons: Boolean) {
        iconSettings.setForceThemedIcons(forceThemedIcons)
    }

    /** The pack the icons come from right now, which is not always the one stored (#139). */
    val effectiveIconPack: Flow<IconPack> = iconService.effectiveIconPack.map { it ?: SystemIconPack }

    val installedIconPacks: Flow<List<IconPack>> = iconService.getInstalledIconPacks().map {
        listOf(SystemIconPack) + it
    }

    fun setIconPack(iconPack: String) {
        iconSettings.setIconPack(iconPack)
    }

    val hasNotificationsPermission = permissionsManager.hasPermission(PermissionGroup.Notifications)

    val notificationBadges = badgeSettings.notifications
    fun setNotifications(notifications: Boolean) {
        badgeSettings.setNotifications(notifications)
    }

    fun requestNotificationsPermission(context: AppCompatActivity) {
        permissionsManager.requestPermission(context, PermissionGroup.Notifications)
    }


    val shortcutBadges = badgeSettings.shortcuts
    fun setShortcuts(shortcuts: Boolean) {
        badgeSettings.setShortcuts(shortcuts)
    }

    val suspendedAppBadges = badgeSettings.suspendedApps
    fun setSuspendedApps(suspendedApps: Boolean) {
        badgeSettings.setSuspendedApps(suspendedApps)
    }


    private val previewItems = grid.flatMapLatest { grid ->
        favoritesService.getFavorites(
            includeTypes = listOf("app"),
            limit = grid.columnCount,
        )
    }.shareIn(viewModelScope, started = SharingStarted.WhileSubscribed(), 1)

    fun getPreviewIcons(size: Int): Flow<List<LauncherIcon>> {
        return previewItems.flatMapLatest { apps ->
            combine(apps.map {
                iconService.getIcon(it, size).filterNotNull()
            }) {
                it.toList()
            }
        }
    }

    fun getIconPackPreviewIcons(
        context: Context,
        iconPack: IconPack,
        count: Int,
        size: Int,
        themed: Boolean
    ): Flow<List<LauncherIcon>> {
        if (iconPack.packageName == DefaultIconPack.None) {
            return systemPreviewApps(previewItems, appRepository.findMany(), Process.myUserHandle()).map { apps ->
                firstIcons(apps, count) { it.loadIcon(context, size, themed) }
            }
        }
        return previewItems.map { favorites ->
            val components = favorites.filterIsInstance<Application>().map { it.componentName } + fallbackIconPackIcons
            firstIcons(components.distinct(), count) {
                iconPackManager.getIcon(
                    packageName = it.packageName,
                    activityName = it.className,
                    iconPack = iconPack.packageName,
                    allowThemed = themed,
                )
            }
        }
    }

    private suspend fun <T> firstIcons(candidates: List<T>, count: Int, load: suspend (T) -> LauncherIcon?): List<LauncherIcon> {
        val icons = mutableListOf<LauncherIcon>()
        for (candidate in candidates) {
            if (icons.size >= count) break
            load(candidate)?.let { icons += it }
        }
        return icons
    }

    companion object : KoinComponent {
        val Factory = viewModelFactory {
            initializer {
                IconsSettingsScreenVM(
                    uiSettings = get(),
                    iconService = get(),
                    permissionsManager = get(),
                    favoritesService = get(),
                    badgeSettings = get(),
                    iconSettings = get(),
                    iconPackManager = get(),
                    appRepository = get(),
                )
            }
        }

        // The apps' own icons, chosen: stored as is (#3 D6), and the entry the
        // screen marks when icons.pack is "none" or the chosen pack is missing.
        private val SystemIconPack = IconPack(
            name = "System",
            packageName = DefaultIconPack.None,
            version = "",
            themed = true,
        )

        // Some very common activities that are likely included in most icon packs
        private val fallbackIconPackIcons = mutableListOf(
            ComponentName("com.android.vending", "com.android.vending.AssetBrowserActivity"),
            ComponentName("com.google.android.camera", "com.android.camera.Camera"),
            ComponentName("com.android.settings", "com.android.settings.Settings"),
            ComponentName("com.google.android.deskclock", "com.android.deskclock.DeskClock"),
            ComponentName("com.android.chrome", "com.android.chrome.Main"),
            ComponentName("com.google.android.calendar", "com.android.calendar.AllInOneActivity"),
            ComponentName("com.android.dialer", "com.android.dialer.main.impl.MainActivity"),
            ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.googlequicksearchbox.SearchActivity"
            ),
        )
    }
}

/**
 * The apps the System card previews with their own icons (#139): the
 * favorites, then the other apps of [user]. Not the icon packs' fallback
 * components, which a pack has glyphs for whether or not the app is installed.
 * [installed] is an upstream, not a snapshot: on a fresh profile the
 * repository is still empty when the screen opens.
 */
internal fun systemPreviewApps(
    favorites: Flow<List<SavableSearchable>>,
    installed: Flow<List<Application>>,
    user: UserHandle,
): Flow<List<Application>> = combine(favorites, installed) { favorites, installed ->
    (favorites.filterIsInstance<Application>() + installed.filter { it.user == user }).distinctBy { it.componentName }
}

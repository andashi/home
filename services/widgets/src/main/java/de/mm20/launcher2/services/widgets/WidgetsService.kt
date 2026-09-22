package de.mm20.launcher2.services.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import androidx.core.content.getSystemService
import de.mm20.launcher2.i18n.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * What the widget picker needs: the installed AppWidget providers of every
 * profile, and the one built-in widget. The old widget column's storage
 * went with it (PR 5b); the grid keeps its own table.
 */
class WidgetsService(
    private val context: Context,
) {
    suspend fun getAppWidgetProviders(): List<AppWidgetProviderInfo> = withContext(Dispatchers.IO) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val launcherApps =
            context.getSystemService<LauncherApps>() ?: return@withContext emptyList()
        val profiles = launcherApps.profiles
        val widgets = mutableListOf<AppWidgetProviderInfo>()
        for (profile in profiles) {
            widgets.addAll(appWidgetManager.getInstalledProvidersForProfile(profile))
        }

        // Ignore widgets that the launcher is not supposed to access
        widgets.filter {
            it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
        }
    }

    fun getAvailableBuiltInWidgets(): Flow<List<BuiltInWidgetInfo>> {
        return flowOf(getBuiltInWidgets())
    }

    fun getBuiltInWidgets(): List<BuiltInWidgetInfo> {
        return listOf(
            BuiltInWidgetInfo(
                type = BuiltInWidgets.Favorites,
                label = context.getString(R.string.widget_name_apps),
            ),
        )
    }

    companion object {
        const val AppWidgetHostId = AppWidgetHostIds.Home
    }
}

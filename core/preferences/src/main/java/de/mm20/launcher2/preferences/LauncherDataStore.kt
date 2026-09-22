package de.mm20.launcher2.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import de.mm20.launcher2.preferences.migrations.Migration4
import de.mm20.launcher2.preferences.migrations.Migration5
import de.mm20.launcher2.preferences.migrations.Migration6
import de.mm20.launcher2.settings.BaseSettings

internal class LauncherDataStore(
    private val context: Context,
): BaseSettings<LauncherSettingsData>(
    context,
    fileName = "settings.json",
    serializer = LauncherSettingsDataSerializer(context),
    migrations = listOf(
        Migration4(),
        Migration5(),
        Migration6(),
    ),
    corruptionHandler = ReplaceFileCorruptionHandler { LauncherSettingsData() }
) {

    val data
        get() = context.dataStore.data

    fun update(block: (LauncherSettingsData) -> LauncherSettingsData) {
        updateData(block)
    }

    // Fork addition (Phase 2): awaited write for config reload; the write is
    // visible via [data] immediately after this function returns.
    suspend fun updateAndAwait(block: (LauncherSettingsData) -> LauncherSettingsData): LauncherSettingsData {
        return updateDataAndAwait(block)
    }
}

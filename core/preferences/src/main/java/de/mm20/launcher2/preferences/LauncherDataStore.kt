package de.mm20.launcher2.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import de.mm20.launcher2.preferences.migrations.Migration6
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The launcher's settings, over the [DataStore] that holds them.
 *
 * The store is a constructor parameter so that tests can bind the class to a
 * store of their own: the settings goldens in `:app:ui` get one from this
 * module's test fixtures (`settingsInMemoryModule`), because the file-backed
 * store delivers its first value on an IO thread that Compose's idle check
 * does not wait for. Production uses the [Context] constructor.
 */
internal class LauncherDataStore(
    private val store: DataStore<LauncherSettingsData>,
) {

    internal constructor(context: Context) : this(
        DataStoreFactory.create(
            serializer = LauncherSettingsDataSerializer(),
            corruptionHandler = ReplaceFileCorruptionHandler { LauncherSettingsData() },
            migrations = listOf(
                Migration6(),
            ),
            produceFile = { context.applicationContext.dataStoreFile("settings.json") },
        )
    )

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    internal val data: Flow<LauncherSettingsData>
        get() = store.data

    internal fun update(block: (LauncherSettingsData) -> LauncherSettingsData) {
        scope.launch {
            store.updateData(block)
        }
    }

    // Fork addition (Phase 2): awaited write for config reload; the write is
    // visible via [data] immediately after this function returns.
    internal suspend fun updateAndAwait(block: (LauncherSettingsData) -> LauncherSettingsData): LauncherSettingsData {
        return store.updateData(block)
    }
}

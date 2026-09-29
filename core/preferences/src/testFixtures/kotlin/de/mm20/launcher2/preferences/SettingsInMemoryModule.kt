package de.mm20.launcher2.preferences

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * A Koin module binding [LauncherDataStore] to settings held in memory, every
 * one at its default. Load it after [preferencesModule] to override the
 * file-backed store.
 *
 * The file-backed store reads settings.json on an IO thread, and Compose's
 * idle check, which is all a screenshot test waits for, does not wait for that
 * thread. A screen collecting a setting with a placeholder initial value could
 * therefore be captured showing the placeholder. Here the value is there when
 * collection starts.
 */
fun settingsInMemoryModule(): Module = module {
    single { LauncherDataStore(InMemorySettingsStore(LauncherSettingsData())) }
}

private class InMemorySettingsStore(initial: LauncherSettingsData) : DataStore<LauncherSettingsData> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val data: Flow<LauncherSettingsData> = state

    override suspend fun updateData(
        transform: suspend (LauncherSettingsData) -> LauncherSettingsData,
    ): LauncherSettingsData = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}

package de.mm20.launcher2.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization tests pinning the existing fire-and-forget behavior of
 * [LauncherDataStore.update]. One test per class: a DataStore instance for
 * settings.json stays registered for the lifetime of the Robolectric sandbox
 * classloader, so a second store for the same file in the same sandbox would
 * fail with "multiple DataStores active for the same file".
 */
@RunWith(RobolectricTestRunner::class)
class LauncherDataStoreUpdateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `update is fire-and-forget and the write becomes visible eventually`() = runBlocking {
        seedSettingsFile(context, LauncherSettingsData(homeGridColumns = 5))
        val store = LauncherDataStore(context)

        assertEquals(5, store.data.first().homeGridColumns)

        store.update { it.copy(homeGridColumns = 9) }

        withTimeout(5_000) {
            while (store.data.first().homeGridColumns != 9) {
                delay(20)
            }
        }
        assertEquals(9, store.data.first().homeGridColumns)
    }
}

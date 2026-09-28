package de.mm20.launcher2.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LauncherDataStoreUpdateAndAwaitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `updateAndAwait write is immediately visible via data first after return`() = runTest {
        seedSettingsFile(context, LauncherSettingsData(homeGridColumns = 5))
        val store = LauncherDataStore(context)

        val updated = store.updateAndAwait { it.copy(homeGridColumns = 7) }

        assertEquals(7, updated.homeGridColumns)
        assertEquals(7, store.data.first().homeGridColumns)
    }
}

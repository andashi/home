package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.homegrid.HomeGridWidgets
import de.mm20.launcher2.homegrid.HomeGridWriteResult
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The adapter is the UI's only way to `GridWriteBack`: a written file is
 * [HomeGridWriteResult.Written], a skip carries the same code and reason
 * the service logged, and the repository is written either way.
 */
@RunWith(RobolectricTestRunner::class)
class HomeGridWriteBackAdapterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingRepository : HomeGridRepository {
        val layouts = MutableStateFlow<Map<String, List<HomeGridItem>>>(emptyMap())
        override fun observe(layout: String): Flow<List<HomeGridItem>> = layouts.map { it[layout].orEmpty() }
        override suspend fun replace(layout: String, items: List<HomeGridItem>) {
            layouts.value = layouts.value + (layout to items)
        }
        override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) = Unit
        override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) = Unit
        override suspend fun delete(layout: String, id: String) = Unit
    }

    private class EmptyStore : ConfigStore {
        override suspend fun readState(): ConfigState = ConfigState()
        override suspend fun apply(mutations: List<ConfigMutation>): List<Diagnostic> = emptyList()
    }

    private val dock = HomeGridItem(HomeGridLayouts.Phone, "dock", HomeGridWidgets.Favorites, x = 0, y = 5, w = 4, h = 1, position = 0)

    private fun adapter(file: File?, repository: HomeGridRepository): HomeGridWriteBackAdapter {
        val dir = File(context.filesDir, "wb-adapter-test").apply { mkdirs() }
        val writeBack = GridWriteBack(
            context = context,
            repository = repository,
            configStore = EmptyStore(),
            reportStore = ReloadReportStore(context),
            lock = ConfigFileLock(),
            fileProvider = { file ?: File(dir, "missing.json") },
        )
        return HomeGridWriteBackAdapter(writeBack)
    }

    @Test
    fun `a skip keeps the service's code and reason and still writes the repository`() = runBlocking {
        val repository = RecordingRepository()

        val result = adapter(file = null, repository = repository).write(HomeGridLayouts.Phone, listOf(dock))

        val skipped = result as HomeGridWriteResult.Skipped
        assertEquals("no-config-file", skipped.code)
        assertTrue(skipped.reason.isNotBlank())
        assertEquals(listOf(dock), repository.layouts.value[HomeGridLayouts.Phone])
    }

    /** The file applied first, so the write-back has the baseline it needs (#3 slice 4); it has `layouts`, so it manages the grid (W1). */
    @Test
    fun `a written file is reported as written`() = runBlocking {
        val real = RealConfigStore()
        try {
            val file = File(context.filesDir, "wb-adapter-test/launcher.json").apply {
                parentFile!!.mkdirs()
                writeText("""{ "schemaVersion": 2, "home": { "grid": { "columns": 4, "layouts": { "phone": { "items": [] } } } } }""")
            }
            val reportStore = ReloadReportStore(context)
            val baselines = AppliedBaselineStore(context)
            val lock = ConfigFileLock()
            ConfigReloader(real.store, reportStore, lock, baselines).reload(file)
            val writeBack = GridWriteBack(context, real.grid, real.store, reportStore, lock, fileProvider = { file }, baselineStore = baselines)

            val result = HomeGridWriteBackAdapter(writeBack).write(HomeGridLayouts.Phone, listOf(dock))

            assertEquals(HomeGridWriteResult.Written, result)
            assertTrue(file.readText().contains("\"dock\""))
        } finally {
            real.close()
        }
    }
}

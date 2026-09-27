package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ReloadReport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A grid widget whose provider this device lacks is kept as written and
 * reported. It was reported once: the next reload of the same file found the
 * stored layout matching the file, applied no grid, never looked the
 * provider up, and its report said nothing - a silence that reads as
 * "resolved" while nothing was (found on the device for #213). Favorites
 * and apps never had this: their stored state lacks the missing entry, so
 * every reload applies and reports again.
 */
@RunWith(RobolectricTestRunner::class)
class MissingProviderReportTest {

    private lateinit var real: RealConfigStore
    private lateinit var reportStore: ReloadReportStore
    private lateinit var baselineStore: AppliedBaselineStore
    private val lock = ConfigFileLock()
    private lateinit var reloader: ConfigReloader
    private val file: File get() = ConfigLocation.configFile(real.context)!!

    @Before
    fun setUp() {
        real = RealConfigStore()
        reportStore = ReloadReportStore(real.context)
        baselineStore = AppliedBaselineStore(real.context)
        reloader = ConfigReloader(real.store, reportStore, lock, baselineStore)
        File(real.context.filesDir, "config/applied-baseline.json").delete()
    }

    @After
    fun tearDown() = real.close()

    private fun grid(widget: String) = """{"schemaVersion": 2, "home": {"grid": {"layouts": {"phone": {"items": [
        {"id": "w", "widget": "$widget", "x": 0, "y": 0, "w": 4, "h": 2}]}}}}}"""

    private fun ReloadReport.missingProviders() = diagnostics.count { it.code == "unknown-widget-provider" }

    private suspend fun reloadFile(text: String): ReloadReport {
        file.parentFile?.mkdirs()
        file.writeText(text)
        return reloader.reload(file)
    }

    @Test
    fun `every reload reports a widget whose provider is missing, not only the first`() = runBlocking {
        val text = grid("com.example.gone/.Widget")

        val first = reloadFile(text)
        val second = reloadFile(text)

        assertEquals("first", 1, first.missingProviders())
        assertEquals("second", 1, second.missingProviders())
    }

    /** Control: with every provider there, the second reload has no grid to apply. */
    @Test
    fun `a layout whose providers are all there is settled after one reload`() = runBlocking {
        val text = grid("com.android.deskclock/.DigitalAppWidgetProvider")

        reloadFile(text)
        val second = reloadFile(text)

        assertEquals(emptyList<String>(), second.appliedMutations)
    }

    /**
     * Refitting a layout on every reload must not become a write-back on every
     * reload. The store's change stream is what asks for a write-back: the
     * second reload of an unchanged layout must not emit on it, and a
     * write-back after it writes nothing.
     */
    @Test
    fun `refitting a layout with a missing provider asks for no write-back`() = runBlocking {
        val text = grid("com.example.gone/.Widget")
        reloadFile(text)

        // The first emission is the state on collection; anything after it is a change.
        val changes = async { withTimeoutOrNull(2_000) { real.store.changes().drop(1).take(1).toList() } }
        kotlinx.coroutines.delay(200)
        reloadFile(text)

        assertEquals("the refit changed nothing the write-back is asked about", null, changes.await())
        val writeBack = ConfigWriteBack(real.context, real.store, reportStore, baselineStore, lock)
        assertTrue(writeBack.write() is WriteBackResult.Unchanged)
    }

    /** Control for the one above: the same watch does see a layout that really changed. */
    @Test
    fun `a reload that changes the layout is seen by the same watch`() = runBlocking {
        reloadFile(grid("com.example.gone/.Widget"))

        val changes = async { withTimeoutOrNull(2_000) { real.store.changes().drop(1).take(1).toList() } }
        kotlinx.coroutines.delay(200)
        reloadFile(grid("com.android.deskclock/.DigitalAppWidgetProvider"))

        assertEquals(1, changes.await()?.size)
    }
}

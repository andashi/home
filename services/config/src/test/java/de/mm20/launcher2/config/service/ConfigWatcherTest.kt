package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigWatcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun configFile(): File = ConfigLocation.configFile(context)!!

    private fun reportFile(): File = File(context.filesDir, "config/last-reload-report.json")

    @Before
    fun cleanup() {
        configFile().delete()
        reportFile().delete()
    }

    private fun writeConfig(text: String = """{"schemaVersion": 1, "icons": {"themed": true}}""") {
        val file = configFile()
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun TestScope.newWatcher(
        store: FakeConfigStore,
        reportStore: ReloadReportStore = ReloadReportStore(context),
    ): ConfigWatcher {
        return ConfigWatcher(
            context,
            ConfigReloader(store, reportStore),
            reportStore,
            scope = this,
            debounceMs = ConfigWatcher.DefaultDebounceMs,
        )
    }

    @Test
    fun `events within the debounce window collapse into one reload`() = runTest {
        val store = FakeConfigStore()
        val watcher = newWatcher(store)
        writeConfig()

        watcher.onConfigFileEvent()
        advanceTimeBy(100)
        runCurrent()
        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs - 1)
        runCurrent()
        assertEquals(0, store.applyCount)

        advanceTimeBy(1)
        watcher.debounceJob!!.join()
        assertEquals(1, store.applyCount)

        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()
        assertEquals(2, store.applyCount)
    }

    @Test
    fun `debounced reload carries the file watcher trigger`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()

        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()

        assertEquals(ReloadTrigger.FileWatcher, reportStore.read()!!.trigger)
    }

    @Test
    fun `startup check reloads when the file exists and no report exists`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()

        watcher.startupCheck()!!.join()

        assertEquals(1, store.applyCount)
        val report = reportStore.read()
        assertNotNull(report)
        assertEquals(ReloadTrigger.StartupCheck, report!!.trigger)
    }

    @Test
    fun `startup check skips the reload when the hash matches the last report`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()
        reportStore.save(
            ReloadReport(
                success = true,
                configSha256 = configFile().readBytes().sha256Hex(),
            )
        )

        watcher.startupCheck()!!.join()

        assertEquals(0, store.applyCount)
    }

    /**
     * #3 slice 4: a write-back needs the baseline of exactly this file. After
     * an update, or with its data cleared, the report can know the file while
     * no baseline does; without a reload then, every write-back would skip.
     */
    @Test
    fun `startup check reloads a file the report knows but no baseline does`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val baselines = AppliedBaselineStore(context).also { File(context.filesDir, "config/applied-baseline.json").delete() }
        val watcher = ConfigWatcher(context, ConfigReloader(store, reportStore), reportStore, scope = this, baselineStore = baselines)
        writeConfig()
        reportStore.save(ReloadReport(success = true, configSha256 = configFile().readBytes().sha256Hex()))

        watcher.startupCheck()!!.join()

        assertEquals(1, store.applyCount)
    }

    @Test
    fun `startup check skips the reload when the report and the baseline both know the file`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val baselines = AppliedBaselineStore(context)
        val watcher = ConfigWatcher(context, ConfigReloader(store, reportStore), reportStore, scope = this, baselineStore = baselines)
        writeConfig()
        val hash = configFile().readBytes().sha256Hex()
        reportStore.save(ReloadReport(success = true, configSha256 = hash))
        baselines.save(AppliedBaseline(hash, JsonObject(emptyMap())))

        watcher.startupCheck()!!.join()

        assertEquals(0, store.applyCount)
    }

    @Test
    fun `startup check reloads when the file changed since the last report`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()
        reportStore.save(ReloadReport(success = true, configSha256 = "outdated"))

        watcher.startupCheck()!!.join()

        assertEquals(1, store.applyCount)
    }

    @Test
    fun `startup check reloads when the last report has no hash`() =
        runTest {
            val store = FakeConfigStore()
            val reportStore = ReloadReportStore(context)
            val watcher = newWatcher(store, reportStore)
            writeConfig()
            reportStore.save(ReloadReport(success = false, configSha256 = null))

            watcher.startupCheck()!!.join()

            assertEquals(1, store.applyCount)
        }

    @Test
    fun `startup check does nothing without a config file`() = runTest {
        val store = FakeConfigStore()
        val watcher = newWatcher(store)

        watcher.startupCheck()!!.join()

        assertEquals(0, store.applyCount)
        assertNull(ReloadReportStore(context).read())
    }

    @Test
    fun `a file event for the launcher's own write is not reloaded`() = runTest {
        // Write-back renames onto launcher.json like a push does; the watcher
        // tells the two apart by the hash the self-write report recorded.
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()
        reportStore.save(
            ReloadReport(
                success = true,
                configSha256 = configFile().readBytes().sha256Hex(),
                trigger = ReloadTrigger.SelfWrite,
            )
        )

        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()

        assertEquals(0, store.applyCount)
        assertEquals(ReloadTrigger.SelfWrite, reportStore.read()!!.trigger)
    }

    @Test
    fun `a file event with a different hash than the self-write reloads`() = runTest {
        // A push that lands after a write-back wins by being the last writer.
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()
        reportStore.save(ReloadReport(success = true, configSha256 = "self-written-but-stale", trigger = ReloadTrigger.SelfWrite))

        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()

        assertEquals(1, store.applyCount)
        assertEquals(ReloadTrigger.FileWatcher, reportStore.read()!!.trigger)
    }

    @Test
    fun `a file event with an unchanged hash after a foreign reload still reloads`() = runTest {
        // Control: only the launcher's own write is recognised by hash. A
        // provisioning script that pushes the same bytes twice expects a
        // file-watcher report for the second push (e2e/l4-config.sh step 5).
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()
        reportStore.save(
            ReloadReport(
                success = true,
                configSha256 = configFile().readBytes().sha256Hex(),
                trigger = ReloadTrigger.Broadcast,
            )
        )

        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()

        assertEquals(1, store.applyCount)
    }

    @Test
    fun `report hash equals the sha256 of the reloaded file`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val watcher = newWatcher(store, reportStore)
        writeConfig()

        watcher.startupCheck()!!.join()

        val report = reportStore.read()!!
        assertEquals(configFile().readBytes().sha256Hex(), report.configSha256)
        assertTrue(report.configSha256!!.matches(Regex("[0-9a-f]{64}")))
    }

    // ----- a layout kept as written until its rows are measured (#90) -----

    private fun TestScope.measuringWatcher(
        store: FakeConfigStore,
        measurements: MutableStateFlow<Map<String, Int>>,
        reportStore: ReloadReportStore = ReloadReportStore(context),
    ) = ConfigWatcher(context, ConfigReloader(store, reportStore), reportStore, scope = this, measurements = measurements)

    /**
     * The reload reads the file on the IO pool, which virtual time does not
     * run, so this waits in real time for the store to have applied [n]
     * times - bounded, and on the condition rather than a sleep.
     */
    private suspend fun FakeConfigStore.awaitApplies(n: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (applyCount < n) delay(5) }
    }

    /**
     * Before the first draw a layout's rows are not known, so a config pushed
     * then is kept as written (GridRowsSource). Nothing else re-fits it: the
     * next reload would find the file and the store agreeing. So the
     * measurement itself reloads, and that reload fits the layout.
     */
    @Test
    fun `the first measurement of the grid reloads the config to fit it`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        writeConfig()
        val job = measuringWatcher(store, measurements, reportStore).watchMeasurements()!!
        runCurrent()
        assertEquals("nothing is measured yet", 0, store.applyCount)

        measurements.value = mapOf("fold" to 7)
        runCurrent()
        store.awaitApplies(1)

        assertEquals(1, store.applyCount)
        assertEquals(ReloadTrigger.GridMeasured, reportStore.read()!!.trigger)
        job.cancel()
    }

    @Test
    fun `a change of the measured rows reloads again`() = runTest {
        val store = FakeConfigStore()
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        writeConfig()
        val job = measuringWatcher(store, measurements).watchMeasurements()!!

        measurements.value = mapOf("fold" to 7)
        runCurrent()
        store.awaitApplies(1)
        // The same rows again are no new measurement (a StateFlow does not emit them).
        measurements.value = mapOf("fold" to 7)
        runCurrent()
        measurements.value = mapOf("fold" to 6)
        runCurrent()
        store.awaitApplies(2)

        assertEquals(2, store.applyCount)
        job.cancel()
    }

    @Test
    fun `a measurement without a config file reloads nothing`() = runTest {
        val store = FakeConfigStore()
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        val job = measuringWatcher(store, measurements).watchMeasurements()!!

        measurements.value = mapOf("fold" to 7)
        runCurrent()
        // Nothing to wait for; give a reload that should not happen the time one would take.
        withContext(Dispatchers.Default) { delay(200) }

        assertEquals(0, store.applyCount)
        job.cancel()
    }
}

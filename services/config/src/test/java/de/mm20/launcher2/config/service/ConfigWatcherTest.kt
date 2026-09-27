package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.consumeAsFlow
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

    /**
     * A build that predates the record of how the file wrote its apps finds
     * none after an update, with the file, the report and the baseline all
     * unchanged: nothing would reload, and a rename on the phone before the
     * next push would come back as a second entry (review on #207). So the
     * startup check reloads once, and marks the record as made even where
     * that reload had no apps to apply.
     */
    @Test
    fun `startup check reloads once when no record of the apps' form exists`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val baselines = AppliedBaselineStore(context)
        val naming = FakeAppNaming(recorded = false)
        val watcher = ConfigWatcher(
            context, ConfigReloader(store, reportStore, appNaming = naming), reportStore, scope = this,
            baselineStore = baselines, appNaming = naming,
        )
        writeConfig()
        val hash = configFile().readBytes().sha256Hex()
        reportStore.save(ReloadReport(success = true, configSha256 = hash))
        baselines.save(AppliedBaseline(hash, JsonObject(emptyMap())))

        watcher.startupCheck()!!.join()
        assertEquals("the first start of this build reloads", 1, store.applyCount)
        assertTrue("and the record exists afterwards", naming.recorded())

        watcher.startupCheck()!!.join()
        assertEquals("the next start does not", 1, store.applyCount)
    }

    /**
     * A startup reload that failed made no record: the marker would say the
     * form was recorded when it was not, and the next start would skip the
     * reload for good (review on #214). It is retried at the next start.
     */
    @Test
    fun `a failed startup reload leaves the record unmade, and the next start retries`() = runTest {
        val store = FakeConfigStore(applyDiagnostics = listOf(Diagnostic(Severity.Error, "apply-failed", "", "datastore gone")))
        val reportStore = ReloadReportStore(context)
        val baselines = AppliedBaselineStore(context)
        val naming = FakeAppNaming(recorded = false)
        val watcher = ConfigWatcher(
            context, ConfigReloader(store, reportStore, appNaming = naming), reportStore, scope = this,
            baselineStore = baselines, appNaming = naming,
        )
        writeConfig()
        val hash = configFile().readBytes().sha256Hex()
        reportStore.save(ReloadReport(success = true, configSha256 = hash))
        baselines.save(AppliedBaseline(hash, JsonObject(emptyMap())))

        watcher.startupCheck()!!.join()
        assertTrue("no marker after a failed reload", !naming.recorded())

        store.applyDiagnostics = emptyList()
        watcher.startupCheck()!!.join()
        assertEquals("the next start tried again", 2, store.applyCount)
        assertTrue(naming.recorded())
    }

    /** Control: a record that exists, with everything else known, reloads nothing. */
    @Test
    fun `startup check skips the reload when the apps' form is recorded`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val baselines = AppliedBaselineStore(context)
        val watcher = ConfigWatcher(
            context, ConfigReloader(store, reportStore), reportStore, scope = this,
            baselineStore = baselines, appNaming = FakeAppNaming(recorded = true),
        )
        writeConfig()
        val hash = configFile().readBytes().sha256Hex()
        reportStore.save(ReloadReport(success = true, configSha256 = hash))
        baselines.save(AppliedBaseline(hash, JsonObject(emptyMap())))

        watcher.startupCheck()!!.join()

        assertEquals(0, store.applyCount)
    }

    private class FakeAppNaming(private var recorded: Boolean) : AppNaming {
        private val written = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String?>>(emptyMap())
        override fun observe() = written
        override suspend fun recorded() = recorded
        override suspend fun replace(naming: Map<String, String?>) {
            written.value = naming
            recorded = true
        }
        override suspend fun awaitRecorded(): Unit = error("not used here")
        override suspend fun forget() {
            recorded = false
        }
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
        // A fit with a correction to report, so the reload writes its report.
        val store = FakeConfigStore(
            applyDiagnostics = listOf(Diagnostic(Severity.Warning, "grid-overflow", "home.grid.layouts.fold.items[0]", "dropped")),
        )
        val reportStore = ReloadReportStore(context)
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        writeConfig()
        val job = measuringWatcher(store, measurements, reportStore).watchMeasurements()!!
        runCurrent()
        assertEquals("nothing is measured yet", 0, store.applyCount)

        measurements.value = mapOf("fold" to 7)
        runCurrent()
        store.awaitApplies(1)
        // The report is saved after the apply, on the IO pool: wait for it too.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (reportStore.read()?.trigger != ReloadTrigger.GridMeasured) delay(5) }
        }

        assertEquals(1, store.applyCount)
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

    // A measurement stays pending until a reload has fitted the grid: one that
    // could not run, or failed, would otherwise lose it, since a later reload
    // finds the file and the store agreeing (#178 review).

    @Test
    fun `a measurement that arrives before the file exists is fitted after the file's first reload`() = runTest {
        val store = FakeConfigStore()
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        val watcher = measuringWatcher(store, measurements)
        val job = watcher.watchMeasurements()!!
        measurements.value = mapOf("fold" to 7)
        runCurrent()
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals("no file, nothing to reload yet", 0, store.applyCount)

        writeConfig()
        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()
        store.awaitApplies(2)

        assertEquals("the file-watcher reload, then the pending measurement's", 2, store.applyCount)
        job.cancel()
    }

    @Test
    fun `a measurement whose reload failed is fitted once the file is valid`() = runTest {
        val store = FakeConfigStore()
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        val watcher = measuringWatcher(store, measurements)
        writeConfig("""{"schemaVersion": 2, "icons": """)
        val job = watcher.watchMeasurements()!!
        measurements.value = mapOf("fold" to 7)
        runCurrent()
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals("a malformed file applies nothing", 0, store.applyCount)

        writeConfig()
        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        watcher.debounceJob!!.join()
        store.awaitApplies(2)

        assertEquals(2, store.applyCount)
        job.cancel()
    }

    /**
     * A measurement that arrives while a retry is fitting an older one must
     * not be cleared by that retry: the older reload fitted the older rows, or
     * read the newer ones by chance, and cannot tell which (#178 review).
     */
    @Test
    fun `a measurement that arrives during a retry's reload is fitted after it`() = runTest {
        val store = FakeConfigStore()
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        val watcher = measuringWatcher(store, measurements)
        val job = watcher.watchMeasurements()!!
        measurements.value = mapOf("fold" to 7)
        runCurrent()
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals("no file, so the measurement is pending", 0, store.applyCount)

        // The file appears: its reload, then the retry, which is held mid-apply.
        store.gateFromApply = 2
        writeConfig()
        watcher.onConfigFileEvent()
        advanceTimeBy(ConfigWatcher.DefaultDebounceMs)
        runCurrent()
        store.awaitApplies(2)
        measurements.value = mapOf("fold" to 6)
        runCurrent()
        store.gate.complete(Unit)
        watcher.debounceJob!!.join()
        store.awaitApplies(3)

        assertEquals("file reload, retry, then the newer measurement's", 3, store.applyCount)
        job.cancel()
    }

    @Test
    fun `a pending measurement is fitted after a startup check that skipped the reload`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val measurements = MutableStateFlow<Map<String, Int>>(emptyMap())
        val watcher = measuringWatcher(store, measurements, reportStore)
        val job = watcher.watchMeasurements()!!
        measurements.value = mapOf("fold" to 7)
        runCurrent()
        withContext(Dispatchers.Default) { delay(200) }
        // The file appears with a report that already knows it, so the startup
        // check itself has nothing to reload.
        writeConfig()
        val hash = configFile().readBytes().sha256Hex()
        reportStore.save(ReloadReport(success = true, configSha256 = hash))

        watcher.startupCheck()!!.join()
        store.awaitApplies(1)

        assertEquals("only the pending measurement's reload", 1, store.applyCount)
        job.cancel()
    }

    // ----- an app the file names arrives after the file (#207 review) -----

    private fun TestScope.arrivalWatcher(store: FakeConfigStore, arrivals: kotlinx.coroutines.flow.Flow<String>? = null) =
        ConfigWatcher(
            context, ConfigReloader(store, ReloadReportStore(context)), ReloadReportStore(context),
            scope = this, arrivals = arrivals ?: kotlinx.coroutines.flow.emptyFlow(),
        )

    private suspend fun lastReportWaitsOn(code: String?) = ReloadReportStore(context).save(
        ReloadReport(
            success = true,
            diagnostics = listOfNotNull(code?.let { Diagnostic(Severity.Warning, it, "x", "absent") }),
        ),
    )

    /**
     * A favorite, a widget, an app's label or visibility, or a profile's entry
     * that names something absent is skipped and reported, and nothing else
     * reloads the file when it arrives: the file does not change, and the store
     * reads the same for an app with no customization yet. So an arrival while
     * the last reload waits on something absent reloads the file.
     */
    @Test
    fun `an arrival while the last reload waits on something absent reloads the file`() = runTest {
        val store = FakeConfigStore()
        writeConfig()
        val watcher = arrivalWatcher(store)

        for ((i, code) in ConfigWatcher.WaitingCodes.withIndex()) {
            lastReportWaitsOn(code)
            watcher.onArrival("com.example.new$i")
            assertEquals(code, i + 1, store.applyCount)
        }

        assertEquals(
            listOf("app-unavailable", "favorite-unavailable", "profile-unavailable", "unknown-widget-provider"),
            ConfigWatcher.WaitingCodes.sorted(),
        )
    }

    /** Controls: nothing absent, or only something no install brings, reloads nothing. */
    @Test
    fun `an arrival with nothing waiting reloads nothing`() = runTest {
        val store = FakeConfigStore()
        writeConfig()
        val watcher = arrivalWatcher(store)

        lastReportWaitsOn(null)
        watcher.onArrival("com.example.a")
        lastReportWaitsOn("grid-overflow")
        watcher.onArrival("com.example.b")
        lastReportWaitsOn("search-action-unavailable")
        watcher.onArrival("com.example.c")

        assertEquals(0, store.applyCount)
    }

    @Test
    fun `an arrival without a config file reloads nothing`() = runTest {
        val store = FakeConfigStore()
        lastReportWaitsOn("app-unavailable")

        arrivalWatcher(store).onArrival("com.example.a")

        assertEquals(0, store.applyCount)
    }

    /**
     * An app that arrived while the launcher was not running is reported by
     * no signal again, and the startup check skips a file it knows. So the
     * watcher decides once when it starts (review on #213). The first version
     * asserted the opposite - that the first look reloads nothing - which is
     * worse than no control: it certified the defect.
     */
    @Test
    fun `at start, a report that waits on something absent reloads once`() = runTest {
        val store = FakeConfigStore()
        writeConfig()
        lastReportWaitsOn("favorite-unavailable")

        val job = arrivalWatcher(store).watchArrivals()!!
        runCurrent()
        store.awaitApplies(1)
        job.cancel()

        assertEquals(1, store.applyCount)
    }

    @Test
    fun `at start, a report that waits on nothing reloads nothing`() = runTest {
        val store = FakeConfigStore()
        writeConfig()
        lastReportWaitsOn(null)
        val signals = kotlinx.coroutines.channels.Channel<String>()

        val job = arrivalWatcher(store, signals.consumeAsFlow()).watchArrivals()!!
        // The start is decided before the first signal is taken: once this
        // send returns, the start has been decided.
        signals.send("com.example.a")
        job.cancel()

        assertEquals(0, store.applyCount)
    }

    /** The wiring: each signal the flow delivers is decided. */
    @Test
    fun `a signal from the flow reloads while something waits`() = runTest {
        // Every reload reports the absence again, so something waits throughout.
        val store = FakeConfigStore(applyDiagnostics = listOf(Diagnostic(Severity.Warning, "app-unavailable", "apps[0]", "absent")))
        writeConfig()
        lastReportWaitsOn("app-unavailable")
        val signals = kotlinx.coroutines.channels.Channel<String>()
        val job = arrivalWatcher(store, signals.consumeAsFlow()).watchArrivals()!!

        signals.send("com.example.a") // taken once the start has been decided (a reload)
        signals.send("com.example.b") // taken once "a" has been decided (a reload)
        job.cancel()

        assertTrue("the start and the signal each reloaded: ${store.applyCount}", store.applyCount >= 2)
    }

    /**
     * The app list's growth is one of the signals: how a profile's apps
     * appear when the profile becomes available. What was there at the first
     * look is not an arrival, and a key leaving is none either.
     */
    @Test
    fun `the app list's growth signals the new keys only`() = runTest {
        val keys = kotlinx.coroutines.flow.flowOf(
            setOf("app://a"),
            setOf("app://a", "app://b"),
            setOf("app://b"),
            setOf("app://b", "app://c", "app://d"),
        )

        assertEquals(listOf("app://b", "app://c", "app://d"), appKeyGrowth(keys).toList())
    }
}

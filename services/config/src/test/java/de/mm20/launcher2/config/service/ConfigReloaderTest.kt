package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.SearchState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class ConfigReloaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeConfigStore(
        var state: ConfigState = ConfigState(),
        var applyDiagnostics: List<Diagnostic> = emptyList(),
        var applyDelayMs: Long = 0,
        var readFailure: Exception? = null,
        var applyFailure: Exception? = null,
        /** The state an apply leaves behind; null keeps [state]. */
        var stateAfterApply: ConfigState? = null,
    ) : ConfigStore {
        val events = Collections.synchronizedList(mutableListOf<String>())
        var applyCount = 0

        override suspend fun readState(): ConfigState {
            events += "read"
            readFailure?.let { throw it }
            return state
        }

        override suspend fun apply(mutations: List<ConfigMutation>): List<Diagnostic> {
            applyCount++
            events += "apply:${mutations.map { it.section }}"
            if (applyDelayMs > 0) delay(applyDelayMs)
            applyFailure?.let { throw it }
            stateAfterApply?.let { state = it }
            return applyDiagnostics
        }
    }

    private fun newReloader(store: FakeConfigStore): Pair<ConfigReloader, ReloadReportStore> {
        val reportStore = ReloadReportStore(context)
        return ConfigReloader(store, reportStore) to reportStore
    }

    @Test
    fun `valid config applies diff and persists successful report`() = runTest {
        val store = FakeConfigStore()
        val (reloader, reportStore) = newReloader(store)

        val report = reloader.reload("""{"schemaVersion": 1, "icons": {"themed": false}}""")

        assertTrue(report.success)
        // The report names the schema version the document has after the
        // migration, i.e. the one the effective state speaks.
        assertEquals(2, report.schemaVersion)
        assertEquals(listOf("icons"), report.appliedMutations)
        assertEquals(listOf("read", "apply:[icons]"), store.events)
        assertEquals(report, reportStore.read())
    }

    @Test
    fun `a state that cannot be read fails the reload and applies nothing`() = runTest {
        val store = FakeConfigStore(readFailure = IllegalStateException("datastore gone"))
        val (reloader, reportStore) = newReloader(store)

        val report = reloader.reload("""{"schemaVersion": 2, "appearance": {"transparency": {"background": 0.2}}}""")

        assertFalse(report.success)
        // The parse diagnostics survive next to the failure, so the host still
        // learns that transparency has no effect any more.
        assertEquals(listOf("inert-key", "read-state-failed"), report.diagnostics.map { it.code })
        assertTrue(report.errorMessage!!.contains("datastore gone"))
        assertEquals(0, store.applyCount)
        assertEquals(report, reportStore.read())
    }

    @Test
    fun `a store that throws while applying fails the reload with apply-failed`() = runTest {
        val store = FakeConfigStore(applyFailure = IllegalStateException("disk full"))
        val (reloader, _) = newReloader(store)

        val report = reloader.reload("""{"schemaVersion": 2, "icons": {"themed": false}}""")

        assertFalse(report.success)
        val failure = report.diagnostics.single { it.code == "apply-failed" }
        assertEquals(Severity.Error, failure.severity)
        assertTrue(failure.message.contains("disk full"))
    }

    @Test
    fun `malformed input does not apply`() = runTest {
        val store = FakeConfigStore()
        val (reloader, _) = newReloader(store)

        val report = reloader.reload("""{"schemaVersion": 1, "icons": """)

        assertFalse(report.success)
        assertTrue(report.diagnostics.any { it.code == "malformed-json" && it.severity == Severity.Error })
        assertEquals(0, store.applyCount)
    }

    @Test
    fun `validation errors do not apply`() = runTest {
        val store = FakeConfigStore()
        val (reloader, _) = newReloader(store)

        val report = reloader.reload(
            """{"schemaVersion": 2, "appearance": {"glass": {"tint": 2.0}}}"""
        )

        assertFalse(report.success)
        assertTrue(report.diagnostics.any { it.code == "invalid-glass" && it.severity == Severity.Error })
        assertEquals(0, store.applyCount)
    }

    @Test
    fun `unknown key warning does not fail the reload`() = runTest {
        val store = FakeConfigStore()
        val (reloader, _) = newReloader(store)

        val report = reloader.reload(
            """{"schemaVersion": 1, "icons": {"themed": false}, "nonsense": 42}"""
        )

        assertTrue(report.success)
        assertTrue(report.diagnostics.any { it.code == "unknown-key" && it.severity == Severity.Warning })
        assertEquals(listOf("icons"), report.appliedMutations)
        assertEquals(1, store.applyCount)
    }

    @Test
    fun `no-op diff writes a successful empty report`() = runTest {
        val store = FakeConfigStore(state = ConfigState(themedIcons = true))
        val (reloader, _) = newReloader(store)

        val report = reloader.reload("""{"schemaVersion": 1, "icons": {"themed": true}}""")

        assertTrue(report.success)
        assertEquals(emptyList<String>(), report.appliedMutations)
        assertEquals(emptyList<Diagnostic>(), report.diagnostics)
        assertEquals(listOf("read", "apply:[]"), store.events)
    }

    @Test
    fun `apply error diagnostics make the report unsuccessful and exclude the section`() = runTest {
        val store = FakeConfigStore(
            applyDiagnostics = listOf(
                Diagnostic(DiagnosticCode.ApplyFailed, "home.favorites", "datastore gone"),
            )
        )
        val (reloader, _) = newReloader(store)

        val report = reloader.reload(
            """{"schemaVersion": 2, "icons": {"themed": false}, "home": {"favorites": [{"packageName": "com.example.app"}]}}"""
        )

        assertFalse(report.success)
        assertEquals(listOf("icons"), report.appliedMutations)
        assertTrue(report.diagnostics.any { it.code == "apply-failed" })
    }

    @Test
    fun `apply warning diagnostics keep the report successful`() = runTest {
        val store = FakeConfigStore(
            applyDiagnostics = listOf(
                Diagnostic(DiagnosticCode.UnknownWidgetProvider, "home.grid.layouts.phone.items[0]", "kept"),
            )
        )
        val (reloader, _) = newReloader(store)

        val report = reloader.reload(
            """{"schemaVersion": 2, "home": {"grid": {"layouts": {"phone": {"items": [{"id": "a", "widget": "com.x/.W"}]}}}}}"""
        )

        assertTrue(report.success)
        assertEquals(listOf("home.grid"), report.appliedMutations)
    }

    /**
     * A layout kept as written because its rows were not measured yet is
     * fitted once they are: the reload for the measurement applies the grid
     * even though the file and what is stored agree, which is exactly the
     * state a layout kept as written is in.
     */
    @Test
    fun `a reload for a new grid measurement applies the grid the file and the store agree on`() = runTest {
        val layout = """{"items": [{"id": "dock", "widget": "favorites", "x": 4, "y": 6, "w": 4, "h": 1}]}"""
        val text = """{"schemaVersion": 2, "home": {"grid": {"layouts": {"fold": $layout}}}}"""
        val stored = ConfigState(
            gridLayouts = mapOf(
                "fold" to de.mm20.launcher2.config.GridLayoutConfig(
                    listOf(de.mm20.launcher2.config.GridItemConfig(id = "dock", widget = "favorites", x = 4, y = 6, w = 4, h = 1)),
                ),
            ),
        )

        val control = FakeConfigStore(state = stored)
        newReloader(control).first.reload(text, ReloadTrigger.Broadcast)
        val measured = FakeConfigStore(state = stored)
        newReloader(measured).first.reload(text, ReloadTrigger.GridMeasured)

        assertEquals(listOf("read", "apply:[]"), control.events)
        // The second read is the check whether the fit changed anything.
        assertEquals(listOf("read", "apply:[home.grid]", "read"), measured.events)
    }

    /**
     * A widget whose provider was missing is kept in the layout as written, so
     * after its provider arrives the file and the store still agree and a
     * plain reload fits nothing: the provider would never be looked up again,
     * and the report would drop unknown-widget-provider with the layout still
     * on the fallback size (review on #213). An arrival reload applies the grid.
     */
    @Test
    fun `a reload for an app arrival applies the grid the file and the store agree on`() = runTest {
        val layout = """{"items": [{"id": "clock", "widget": "com.example/.Clock", "x": 0, "y": 0, "w": 4, "h": 2}]}"""
        val text = """{"schemaVersion": 2, "home": {"grid": {"layouts": {"phone": $layout}}}}"""
        val stored = ConfigState(
            gridLayouts = mapOf(
                "phone" to de.mm20.launcher2.config.GridLayoutConfig(
                    listOf(de.mm20.launcher2.config.GridItemConfig(id = "clock", widget = "com.example/.Clock", x = 0, y = 0, w = 4, h = 2)),
                ),
            ),
        )

        val arrival = FakeConfigStore(state = stored)
        newReloader(arrival).first.reload(text, ReloadTrigger.AppsChanged)

        assertEquals("apply:[home.grid]", arrival.events[1])
    }

    // A measurement reload must not supersede the report a push is waited on
    // by, unless it changed something the reader has to know (#178 review).

    private val foldText =
        """{"schemaVersion": 2, "home": {"grid": {"layouts": {"fold": {"items": [{"id": "dock", "widget": "favorites", "x": 4, "y": 6, "w": 4, "h": 1}]}}}}}"""

    private fun foldState(y: Int) = ConfigState(
        gridLayouts = mapOf(
            "fold" to de.mm20.launcher2.config.GridLayoutConfig(
                listOf(de.mm20.launcher2.config.GridItemConfig(id = "dock", widget = "favorites", x = 4, y = y, w = 4, h = 1)),
            ),
        ),
    )

    // An arrival reload that changed nothing must not move the sequence: it ran
    // after every app update while a favourite was absent (+1 per update, +3
    // per start on emulator-5556), and a consumer refuses to push when it moves.

    private val absentText =
        """{"schemaVersion": 2, "home": {"favorites": ["com.android.dialer", "org.example.absent"]}}"""
    private val absentState = ConfigState(favorites = listOf(Favorite("com.android.dialer")))
    private val absentWaits = listOf(
        Diagnostic(DiagnosticCode.FavoriteUnavailable, "home.favorites[1]", "App 'org.example.absent' is not installed in the personal profile; it was skipped"),
    )

    @Test
    fun `repeated arrival reloads that change nothing leave the last report and its number`() = runTest {
        val store = FakeConfigStore(state = absentState, applyDiagnostics = absentWaits)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(absentText, ReloadTrigger.Broadcast)
        val pushed = reportStore.read()!!

        reloader.reload(absentText, ReloadTrigger.AppsChanged)
        reloader.reload(absentText, ReloadTrigger.AppsChanged)

        val stored = reportStore.read()!!
        assertEquals(pushed.sequence, stored.sequence)
        assertEquals(ReloadTrigger.Broadcast, stored.trigger)
    }

    @Test
    fun `an arrival reload that installed what was missing replaces the last report`() = runTest {
        // Control: an arrival that changes something still says so - never
        // saving would flatten every table the same way.
        val store = FakeConfigStore(state = absentState, applyDiagnostics = absentWaits)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(absentText, ReloadTrigger.Broadcast)
        val pushed = reportStore.read()!!
        store.applyDiagnostics = emptyList()
        store.stateAfterApply = absentState.copy(favorites = absentState.favorites + Favorite("org.example.absent"))

        reloader.reload(absentText, ReloadTrigger.AppsChanged)

        val stored = reportStore.read()!!
        assertEquals(pushed.sequence!! + 1, stored.sequence)
        assertEquals(ReloadTrigger.AppsChanged, stored.trigger)
        assertEquals(emptyList<Diagnostic>(), stored.diagnostics)
    }

    @Test
    fun `an arrival reload that changed the device but not the diagnostics replaces the last report`() = runTest {
        // A widget whose provider arrived is bound and refitted without a
        // diagnostic changing: the state is what tells.
        val store = FakeConfigStore(state = absentState, applyDiagnostics = absentWaits)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(absentText, ReloadTrigger.Broadcast)
        val pushed = reportStore.read()!!
        store.stateAfterApply = absentState.copy(themedIcons = !absentState.themedIcons)

        reloader.reload(absentText, ReloadTrigger.AppsChanged)

        val stored = reportStore.read()!!
        assertEquals(pushed.sequence!! + 1, stored.sequence)
        assertEquals(ReloadTrigger.AppsChanged, stored.trigger)
    }

    @Test
    fun `an arrival reload of a file the last report does not describe replaces it`() = runTest {
        val store = FakeConfigStore(state = absentState, applyDiagnostics = absentWaits)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(absentText, ReloadTrigger.Broadcast)
        val newer = "$absentText\n"

        reloader.reload(newer, ReloadTrigger.AppsChanged)

        val stored = reportStore.read()!!
        assertEquals(ReloadTrigger.AppsChanged, stored.trigger)
        assertEquals(newer.toByteArray(Charsets.UTF_8).sha256Hex(), stored.configSha256)
    }

    // A reload that keeps failing records no apps form, so every start runs a
    // startup check that fails again, and write-back appends its skip to that
    // report: +2 per start for ever, measured on emulator-5562 (#261). The
    // retry is what heals the device once the cause goes (a missing upload
    // restored), so it stays; an unchanged failure is simply not news.

    private val wallpaperText = """{"schemaVersion": 2, "appearance": {"wallpaper": {"image": "home.jpg"}}}"""
    private val wallpaperMissing = listOf(
        Diagnostic(DiagnosticCode.WallpaperMissing, "appearance.wallpaper.image", "No uploaded wallpaper named 'home.jpg'"),
    )

    /**
     * What write-back does to the last report while it is held back: its skip,
     * appended - and, as ConfigWriteBack.editDiagnostics does, saved only when
     * that changes the list (a skip already there is left alone).
     */
    private suspend fun writeBackSkips(reportStore: ReloadReportStore) {
        val last = reportStore.read()!!
        val skip = Diagnostic(DiagnosticCode.WriteBackSkipped, "apps-form-unrecorded", "", "held back")
        if (last.diagnostics.any { it.code == skip.code && it.message == skip.message }) return
        reportStore.save(last.copy(diagnostics = last.diagnostics + skip))
    }

    @Test
    fun `a startup check that fails the same way again leaves the last report and its number`() = runTest {
        val store = FakeConfigStore(applyDiagnostics = wallpaperMissing)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)
        writeBackSkips(reportStore)
        val looped = reportStore.read()!!

        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)
        writeBackSkips(reportStore)

        assertEquals(looped.sequence, reportStore.read()!!.sequence)
    }

    @Test
    fun `a startup check of a file that still does not parse leaves the last report`() = runTest {
        val broken = "{ not json"
        val (reloader, reportStore) = newReloader(FakeConfigStore())
        reloader.reload(broken, ReloadTrigger.StartupCheck)
        writeBackSkips(reportStore)
        val looped = reportStore.read()!!

        reloader.reload(broken, ReloadTrigger.StartupCheck)

        assertEquals(looped.sequence, reportStore.read()!!.sequence)
    }

    @Test
    fun `a startup check that now succeeds replaces the report and records the apps form`() = runTest {
        // Control, the self-heal: the missing upload is back, and the next
        // start's reload goes through, says so and makes the record.
        val store = FakeConfigStore(applyDiagnostics = wallpaperMissing)
        val naming = Naming(record = null)
        val reportStore = ReloadReportStore(context)
        val reloader = ConfigReloader(store, reportStore, namings = listOf(naming))
        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)
        writeBackSkips(reportStore)
        val looped = reportStore.read()!!
        assertFalse(naming.recorded())
        store.applyDiagnostics = emptyList()

        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)

        val stored = reportStore.read()!!
        assertEquals(looped.sequence!! + 1, stored.sequence)
        assertTrue(stored.success)
        assertTrue(naming.recorded())
    }

    @Test
    fun `a startup check with no report before it saves one`() = runTest {
        // Control: the retry is not skipped - a store with nothing to compare
        // against always gets its report.
        val (reloader, reportStore) = newReloader(FakeConfigStore(applyDiagnostics = wallpaperMissing))

        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)

        assertEquals(ReloadTrigger.StartupCheck, reportStore.read()!!.trigger)
    }

    @Test
    fun `a startup check whose failure gained a finding other than write-back's replaces the report`() = runTest {
        // Control for the comparison: only write-back's own annotations are
        // left out of it, never a finding of the reload.
        val store = FakeConfigStore(applyDiagnostics = wallpaperMissing)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)
        writeBackSkips(reportStore)
        val looped = reportStore.read()!!
        store.applyDiagnostics = wallpaperMissing + Diagnostic(DiagnosticCode.ApplyFailed, "home.favorites", "datastore gone")

        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)

        assertEquals(looped.sequence!! + 1, reportStore.read()!!.sequence)
    }

    @Test
    fun `a startup check that changed the device replaces the report`() = runTest {
        val store = FakeConfigStore(applyDiagnostics = wallpaperMissing)
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)
        val last = reportStore.read()!!
        store.stateAfterApply = ConfigState(themedIcons = false)

        reloader.reload(wallpaperText, ReloadTrigger.StartupCheck)

        assertEquals(last.sequence!! + 1, reportStore.read()!!.sequence)
    }

    @Test
    fun `a measurement reload that changes nothing leaves the last report as it was`() = runTest {
        val store = FakeConfigStore(state = foldState(6))
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(foldText, ReloadTrigger.Broadcast)

        reloader.reload(foldText, ReloadTrigger.GridMeasured)

        assertEquals(listOf("read", "apply:[]", "read", "apply:[home.grid]", "read"), store.events)
        assertEquals(ReloadTrigger.Broadcast, reportStore.read()!!.trigger)
    }

    // Silent only for a true no-op: a measurement reload that met a file the
    // last report does not describe, or applied anything besides the forced
    // grid, has something to say (#178 review). Each test below changes one
    // of the two, so each condition is guarded on its own.

    @Test
    fun `a measurement reload of a file the last report does not describe replaces it`() = runTest {
        val store = FakeConfigStore(state = foldState(6))
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(foldText, ReloadTrigger.Broadcast)
        // The same settings in a new file: only the hash tells them apart.
        val newer = "$foldText\n"

        val report = reloader.reload(newer, ReloadTrigger.GridMeasured)

        assertEquals(listOf("home.grid"), report.appliedMutations)
        assertEquals(report.configSha256, reportStore.read()!!.configSha256)
        assertEquals(ReloadTrigger.GridMeasured, reportStore.read()!!.trigger)
    }

    @Test
    fun `a measurement reload that applied more than the grid replaces the last report`() = runTest {
        val text = foldText.replace("\"schemaVersion\": 2,", "\"schemaVersion\": 2, \"icons\": {\"themed\": false},")
        val store = FakeConfigStore(state = foldState(6).copy(themedIcons = false))
        val (reloader, reportStore) = newReloader(store)
        val pushed = reloader.reload(text, ReloadTrigger.Broadcast)
        // Changed on the device since the push; the same file puts it back.
        store.state = store.state.copy(themedIcons = true)

        val report = reloader.reload(text, ReloadTrigger.GridMeasured)

        assertEquals(pushed.configSha256, report.configSha256)
        assertEquals(listOf("icons", "home.grid"), report.appliedMutations)
        assertEquals(ReloadTrigger.GridMeasured, reportStore.read()!!.trigger)
    }

    @Test
    fun `a measurement reload that restored a grid setting replaces the last report`() = runTest {
        val text = foldText.replace("\"grid\": {", "\"grid\": {\"columns\": 4, ")
        val store = FakeConfigStore(state = foldState(6))
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(text, ReloadTrigger.Broadcast)
        // Changed on the device since the push; the file's forced SetGrid puts
        // it back without the layouts changing (#178 review).
        store.state = store.state.copy(gridColumns = 5)

        reloader.reload(text, ReloadTrigger.GridMeasured)

        assertEquals(ReloadTrigger.GridMeasured, reportStore.read()!!.trigger)
    }

    @Test
    fun `a measurement reload whose capability warning changed replaces the last report`() = runTest {
        val text = foldText.replace("\"schemaVersion\": 2,", "\"schemaVersion\": 2, \"search\": {\"contacts\": true},")
        val store = FakeConfigStore(state = foldState(6).copy(search = SearchState(contacts = true)))
        val reportStore = ReloadReportStore(context)
        var granted = true
        val reloader = ConfigReloader(store, reportStore, capabilities = CapabilityDiagnostics(contactsGranted = { granted }, callGranted = { true }, transliteratorAvailable = { true }, accessibilityOn = { true }, shortcutHostGranted = { true }, notificationListenerOn = { true }))
        reloader.reload(text, ReloadTrigger.Broadcast)
        // Revoked since the push: the file and the grid are as they were, the
        // warning is new (#178 review).
        granted = false

        reloader.reload(text, ReloadTrigger.GridMeasured)

        val stored = reportStore.read()!!
        assertEquals(ReloadTrigger.GridMeasured, stored.trigger)
        assertEquals(listOf("permission-missing"), stored.diagnostics.map { it.code })
    }

    @Test
    fun `a successful measurement reload replaces a failed report of the same file`() = runTest {
        val store = FakeConfigStore(state = foldState(6), readFailure = IllegalStateException("datastore gone"))
        val (reloader, reportStore) = newReloader(store)
        val failed = reloader.reload(foldText, ReloadTrigger.Broadcast)
        store.readFailure = null

        val report = reloader.reload(foldText, ReloadTrigger.GridMeasured)

        assertEquals(failed.configSha256, report.configSha256)
        assertTrue(report.success)
        assertTrue(reportStore.read()!!.success)
    }

    @Test
    fun `a measurement reload that fitted the grid differently replaces the last report`() = runTest {
        val store = FakeConfigStore(state = foldState(6))
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(foldText, ReloadTrigger.Broadcast)
        store.stateAfterApply = foldState(5)

        reloader.reload(foldText, ReloadTrigger.GridMeasured)

        assertEquals(ReloadTrigger.GridMeasured, reportStore.read()!!.trigger)
    }

    @Test
    fun `a measurement reload with a correction to report replaces the last report`() = runTest {
        val store = FakeConfigStore(state = foldState(6))
        val (reloader, reportStore) = newReloader(store)
        reloader.reload(foldText, ReloadTrigger.Broadcast)
        store.applyDiagnostics = listOf(Diagnostic(DiagnosticCode.GridOverflow, "home.grid.layouts.fold.items[0]", "dropped"))

        reloader.reload(foldText, ReloadTrigger.GridMeasured)

        assertEquals(ReloadTrigger.GridMeasured, reportStore.read()!!.trigger)
    }

    @Test
    fun `concurrent reloads do not interleave`() = runBlocking {
        val store = FakeConfigStore(applyDelayMs = 100)
        val (reloader, _) = newReloader(store)

        val icons = """{"schemaVersion": 1, "icons": {"themed": false}}"""
        val dock = """{"schemaVersion": 2, "home": {"grid": {"locked": true}}}"""

        val reports = listOf(icons, dock).map { text ->
            async(Dispatchers.Default) { reloader.reload(text) }
        }.awaitAll()

        assertTrue(reports.all { it.success })
        val events = store.events.toList()
        assertEquals(4, events.size)
        // Each reload must complete read+apply before the next one starts.
        assertEquals("read", events[0])
        assertTrue(events[1].startsWith("apply:"))
        assertEquals("read", events[2])
        assertTrue(events[3].startsWith("apply:"))
        // Both reloads were applied exactly once.
        assertEquals(setOf("apply:[icons]", "apply:[home.grid]"), setOf(events[1], events[3]))
    }

    /**
     * An app arrival decides from the last report whether to reload. Decided
     * outside the reload lock, it could read the report of before a reload
     * that is already running - one that read the app list before the app
     * arrived and is about to report it missing - skip, and the arrival would
     * be spent (review on #213). Under the lock, the decision waits for that
     * reload's report.
     */
    @Test
    fun `a conditional reload decides after a running reload has reported`() = runBlocking {
        val store = FakeConfigStore(
            applyDelayMs = 300,
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.AppUnavailable, "apps[0]", "not installed")),
        )
        val (reloader, reportStore) = newReloader(store)
        val file = java.io.File(context.cacheDir, "conditional.json").apply {
            writeText("""{"schemaVersion": 2, "icons": {"themed": false}}""")
        }

        val running = async(Dispatchers.Default) { reloader.reload(file, ReloadTrigger.Broadcast) }
        withTimeout(5_000) { while (store.events.isEmpty()) delay(5) }
        val decided = reloader.reloadIf(file, ReloadTrigger.AppsChanged) {
            reportStore.read()?.diagnostics.orEmpty().any { it.code == "app-unavailable" }
        }
        running.await()

        assertNotNull("the decision saw the running reload's report", decided)
        assertEquals(2, store.applyCount)
    }

    /** Control: a condition that does not hold reloads nothing. */
    @Test
    fun `a conditional reload whose condition does not hold reloads nothing`() = runBlocking {
        val store = FakeConfigStore()
        val (reloader, _) = newReloader(store)
        val file = java.io.File(context.cacheDir, "conditional.json").apply {
            writeText("""{"schemaVersion": 2, "icons": {"themed": false}}""")
        }

        assertNull(reloader.reloadIf(file, ReloadTrigger.AppsChanged) { false })
        assertEquals(0, store.applyCount)
    }

    @Test
    fun `reload from file reads and applies`() = runTest {
        val store = FakeConfigStore()
        val (reloader, _) = newReloader(store)
        val file = File(context.filesDir, "test-config.json")
        file.writeText("""{"schemaVersion": 1, "icons": {"themed": false}}""")
        try {
            val report = reloader.reload(file)
            assertTrue(report.success)
            assertEquals(listOf("icons"), report.appliedMutations)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `reload from missing file fails without applying`() = runTest {
        val store = FakeConfigStore()
        val (reloader, _) = newReloader(store)

        val report = reloader.reload(File(context.filesDir, "does-not-exist.json"))

        assertFalse(report.success)
        assertTrue(report.diagnostics.any { it.code == "read-failed" && it.severity == Severity.Error })
        assertEquals(0, store.applyCount)
    }

    // ----- what the file asks for that this profile cannot do (#140) -----

    private fun reloaderWith(
        store: FakeConfigStore,
        contactsGranted: Boolean = true,
        callGranted: Boolean = true,
        availableTransliterators: Set<String>? = null,
        accessibilityOn: Boolean = true,
        shortcutHostGranted: Boolean = true,
        notificationListenerOn: Boolean = true,
    ) =
        ConfigReloader(
            store, ReloadReportStore(context),
            capabilities = CapabilityDiagnostics(
                contactsGranted = { contactsGranted },
                callGranted = { callGranted },
                transliteratorAvailable = { id -> availableTransliterators?.contains(id) ?: true },
                accessibilityOn = { accessibilityOn },
                shortcutHostGranted = { shortcutHostGranted },
                notificationListenerOn = { notificationListenerOn },
            ),
        )

    // #140: the rest of the class. App shortcuts come only to the home app,
    // and notification badges only to an enabled notification listener; the
    // file can ask for both and the device grants neither.

    @Test
    fun `app shortcuts in search without the home role are applied as written and reported`() = runTest {
        // Off on the device, so the file's `true` is a change and is applied (the default is on).
        val store = FakeConfigStore(state = ConfigState(search = SearchState(shortcuts = false)))

        val report = reloaderWith(store, shortcutHostGranted = false).reload("""{"schemaVersion": 2, "search": {"shortcuts": true}}""")

        assertTrue(report.success)
        assertEquals(listOf("read", "apply:[search]"), store.events)
        val missing = report.diagnostics.single { it.code == "permission-missing" }
        assertEquals("search.shortcuts", missing.path)
        assertTrue("says what clears it: ${missing.message}", missing.message.endsWith("until the launcher is made the home app"))
        assertEquals(Severity.Warning, missing.severity)
    }

    @Test
    fun `notification badges without listener access are applied as written and reported`() = runTest {
        val store = FakeConfigStore()

        val report = reloaderWith(store, notificationListenerOn = false)
            .reload("""{"schemaVersion": 2, "icons": {"badges": {"notifications": true}}}""")

        assertTrue(report.success)
        val missing = report.diagnostics.single { it.code == "permission-missing" }
        assertEquals("icons.badges.notifications", missing.path)
        assertTrue("says what clears it: ${missing.message}", missing.message.endsWith("enabled in the system's notification access settings"))
        assertEquals(Severity.Warning, missing.severity)
    }

    /** Controls: granted, switched off, or left out of the file - nothing to report. */
    @Test
    fun `shortcuts and badges that are granted, off, or not in the file are not reported`() = runTest {
        val cases = listOf(
            reloaderWith(FakeConfigStore()) to """{"schemaVersion": 2, "search": {"shortcuts": true}, "icons": {"badges": {"notifications": true}}}""",
            reloaderWith(FakeConfigStore(), shortcutHostGranted = false, notificationListenerOn = false) to
                """{"schemaVersion": 2, "search": {"shortcuts": false}, "icons": {"badges": {"notifications": false}}}""",
            reloaderWith(FakeConfigStore(), shortcutHostGranted = false, notificationListenerOn = false) to
                """{"schemaVersion": 2, "search": {"layout": "grid"}, "icons": {"themed": true}}""",
        )
        for ((reloader, text) in cases) {
            val report = reloader.reload(text)
            assertTrue(text, report.diagnostics.none { it.code == "permission-missing" })
        }
    }

    /** As for contacts (#172 review): a failed section left the key as it was, and an off key needs nothing. */
    @Test
    fun `shortcuts and badges in a section that failed to apply are reported by what is in effect`() = runTest {
        val failing = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "search", "boom"), Diagnostic(DiagnosticCode.ApplyFailed, "icons", "boom"))
        val off = FakeConfigStore(state = ConfigState(search = SearchState(shortcuts = false), badgeNotifications = false), applyDiagnostics = failing)
        val on = FakeConfigStore(state = ConfigState(search = SearchState(shortcuts = true), badgeNotifications = true), applyDiagnostics = failing)
        val text = """{"schemaVersion": 2, "search": {"shortcuts": true}, "icons": {"badges": {"notifications": true}}}"""

        val left = reloaderWith(off, shortcutHostGranted = false, notificationListenerOn = false).reload(text)
        val kept = reloaderWith(on, shortcutHostGranted = false, notificationListenerOn = false).reload(text)

        assertTrue("left off: nothing is missing", left.diagnostics.none { it.code == "permission-missing" })
        assertEquals(
            listOf("icons.badges.notifications", "search.shortcuts"),
            kept.diagnostics.filter { it.code == "permission-missing" }.map { it.path }.sorted(),
        )
    }

    // #3 slice 2: screen lock, the power menu and recents go through the
    // launcher's accessibility service, which only the person can turn on.

    private val serviceGestures =
        """{"schemaVersion": 2, "gestures": {"swipeUp": "recents", "swipeLeft": "power-menu", "longPress": "screen-lock"}}"""

    @Test
    fun `a gesture that needs the accessibility service is applied as written and reported while it is off`() = runTest {
        val store = FakeConfigStore()

        val report = reloaderWith(store, accessibilityOn = false).reload(serviceGestures)

        assertTrue(report.success)
        assertEquals(listOf("read", "apply:[gestures]"), store.events)
        val missing = report.diagnostics.filter { it.code == "permission-missing" }
        assertEquals(listOf("gestures.swipeUp", "gestures.swipeLeft", "gestures.longPress"), missing.map { it.path })
        assertTrue(missing.all { it.severity == Severity.Warning })
        assertEquals(
            "gestures.swipeUp is recents, which needs the launcher's accessibility service; " +
                "it is off, so the gesture asks for it when used",
            missing.first().message,
        )
    }

    @Test
    fun `gestures that need the accessibility service are not reported while it is on`() = runTest {
        val report = reloaderWith(FakeConfigStore(), accessibilityOn = true).reload(serviceGestures)

        assertTrue(report.diagnostics.none { it.code == "permission-missing" })
    }

    /** Control: the default double tap locks the screen, but a file that does not set it asks for nothing. */
    @Test
    fun `gestures that need no service, or that the file leaves out, are not reported`() = runTest {
        val report = reloaderWith(FakeConfigStore(), accessibilityOn = false).reload(
            """{"schemaVersion": 2, "gestures": {"swipeUp": "search", "swipeDown": "notifications", "swipeLeft": {"packageName": "com.android.dialer"}}}""",
        )

        assertTrue(report.diagnostics.none { it.code == "permission-missing" })
    }

    /** Like contacts: a failed section left the gestures as they were, a swipe up that searches. */
    @Test
    fun `a service gesture in a gestures section that failed to apply is not reported`() = runTest {
        val store = FakeConfigStore(
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "gestures", "datastore gone")),
        )

        val report = reloaderWith(store, accessibilityOn = false)
            .reload("""{"schemaVersion": 2, "gestures": {"swipeUp": "recents"}}""")

        assertEquals(listOf("apply-failed"), report.diagnostics.map { it.code })
    }

    /**
     * #3 slice 1: a transliterator this device's ICU lacks is a device
     * condition, like a missing permission. The file is not rejected for it -
     * one file serves devices with different ICU versions - it is applied as
     * written, and the report says search falls back to stripping accents.
     */
    @Test
    fun `a transliterator the device does not have is applied as written and reported`() = runTest {
        val store = FakeConfigStore()

        val report = reloaderWith(store, availableTransliterators = setOf("Any-Latin"))
            .reload("""{"schemaVersion": 2, "search": {"transliterator": "No-Such"}}""")

        assertTrue(report.success)
        assertEquals(listOf("read", "apply:[search]"), store.events)
        val diagnostic = report.diagnostics.single { it.code == "transliterator-unavailable" }
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals("search.transliterator", diagnostic.path)
        assertEquals(
            "search.transliterator is \"No-Such\", which this device's ICU does not have; " +
                "search matching falls back to stripping accents",
            diagnostic.message,
        )
    }

    /** Control: an id the device has, and the two words, are not reported. */
    @Test
    fun `an available transliterator, auto and off are not reported`() = runTest {
        for (id in listOf("Any-Latin", "auto", "off")) {
            val report = reloaderWith(FakeConfigStore(), availableTransliterators = setOf("Any-Latin"))
                .reload("""{"schemaVersion": 2, "search": {"transliterator": "$id"}}""")

            assertTrue(id, report.diagnostics.none { it.code == "transliterator-unavailable" })
        }
    }

    /** Like contacts: a failed search section left the transliterator as it was, so nothing unavailable is in effect. */
    @Test
    fun `a transliterator in a search section that failed to apply is not reported`() = runTest {
        val store = FakeConfigStore(
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "search", "datastore gone")),
        )

        val report = reloaderWith(store, availableTransliterators = emptySet())
            .reload("""{"schemaVersion": 2, "search": {"transliterator": "No-Such"}}""")

        assertEquals(listOf("apply-failed"), report.diagnostics.map { it.code })
    }

    /**
     * #3 slice 1: without CALL_PHONE a tap on a number dials instead of
     * calling. The key stays as written, like contacts; the report says why.
     */
    @Test
    fun `call on tap asked for without CALL_PHONE is applied as written and reported`() = runTest {
        val store = FakeConfigStore(state = ConfigState(search = SearchState(contactsCallOnTap = false)))

        val report = reloaderWith(store, callGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contactsCallOnTap": true}}""")

        assertTrue(report.success)
        assertEquals(listOf("read", "apply:[search]"), store.events)
        val diagnostic = report.diagnostics.single { it.code == "permission-missing" }
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals("search.contactsCallOnTap", diagnostic.path)
        assertEquals(
            "search.contactsCallOnTap is true, but this profile does not hold CALL_PHONE; " +
                "a tap on a number opens the dialer instead of calling",
            diagnostic.message,
        )
    }

    @Test
    fun `call on tap with CALL_PHONE held is not reported`() = runTest {
        val report = reloaderWith(FakeConfigStore(), callGranted = true)
            .reload("""{"schemaVersion": 2, "search": {"contactsCallOnTap": true}}""")

        assertTrue(report.diagnostics.none { it.code == "permission-missing" })
    }

    @Test
    fun `a file that does not ask to call on tap is not reported, whatever the permission`() = runTest {
        for (text in listOf(
            """{"schemaVersion": 2, "search": {"contactsCallOnTap": false}}""",
            """{"schemaVersion": 2, "search": {"layout": "grid"}}""",
        )) {
            val report = reloaderWith(FakeConfigStore(), callGranted = false).reload(text)
            assertTrue(text, report.diagnostics.none { it.code == "permission-missing" })
        }
    }

    /** Like contacts: a failed search section left call on tap as it was, off here, so nothing is missing. */
    @Test
    fun `call on tap in a search section that failed to apply is not reported as a permission problem`() = runTest {
        val store = FakeConfigStore(
            state = ConfigState(search = SearchState(contactsCallOnTap = false)),
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "search", "datastore gone")),
        )

        val report = reloaderWith(store, callGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contactsCallOnTap": true}}""")

        assertEquals(listOf("apply-failed"), report.diagnostics.map { it.code })
    }

    /**
     * The key stays as written and is applied: the read-back feeds write-back
     * and `--pull`, so serving `false` for a missing permission would write
     * that revocable condition into the file as a choice. The report says why
     * the effect differs.
     */
    @Test
    fun `contacts asked for without READ_CONTACTS is applied as written and reported`() = runTest {
        // Off on the device, so the reload has to switch it on to match the file.
        val store = FakeConfigStore(state = ConfigState(search = SearchState(contacts = false)))

        val report = reloaderWith(store, contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true}}""")

        assertTrue(report.success)
        assertEquals(listOf("read", "apply:[search]"), store.events)
        val diagnostic = report.diagnostics.single { it.code == "permission-missing" }
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals("search.contacts", diagnostic.path)
        assertEquals(
            "search.contacts is true, but this profile does not hold READ_CONTACTS; " +
                "contact search finds nothing until it is granted",
            diagnostic.message,
        )
    }

    @Test
    fun `contacts with READ_CONTACTS held is not reported`() = runTest {
        val report = reloaderWith(FakeConfigStore(), contactsGranted = true)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true}}""")

        assertTrue(report.diagnostics.none { it.code == "permission-missing" })
    }

    @Test
    fun `a file that does not ask for contacts is not reported, whatever the permission`() = runTest {
        for (text in listOf(
            """{"schemaVersion": 2, "search": {"contacts": false}}""",
            """{"schemaVersion": 2, "search": {"layout": "grid"}}""",
        )) {
            val report = reloaderWith(FakeConfigStore(), contactsGranted = false).reload(text)
            assertTrue(text, report.diagnostics.none { it.code == "permission-missing" })
        }
    }

    /**
     * A section that failed to apply is not in effect, so no permission is
     * missing for it: the failure is what the report says (#172 review).
     */
    @Test
    fun `contacts in a search section that failed to apply is not reported as a permission problem`() = runTest {
        val store = FakeConfigStore(
            state = ConfigState(search = SearchState(contacts = false)),
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "search", "datastore gone")),
        )

        val report = reloaderWith(store, contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true}}""")

        assertFalse(report.success)
        assertEquals(listOf("apply-failed"), report.diagnostics.map { it.code })
    }

    /**
     * What counts is whether contact search is on after the reload: a failed
     * search section leaves it as it was, and it was on (#172 review).
     */
    @Test
    fun `contacts already on stays reported when its search section fails for another key`() = runTest {
        val store = FakeConfigStore(
            state = ConfigState(search = SearchState(contacts = true)),
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "search", "datastore gone")),
        )

        val report = reloaderWith(store, contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true, "labels": false}}""")

        assertEquals(listOf("apply-failed", "permission-missing"), report.diagnostics.map { it.code })
    }

    /**
     * A failure is matched against the key's own path: the search actions
     * failing leaves search.contacts as applied, and it is reported
     * (#172 review).
     */
    @Test
    fun `contacts is reported when only the search actions failed`() = runTest {
        val store = FakeConfigStore(
            state = ConfigState(search = SearchState(contacts = false)),
            applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "search.actions", "database locked")),
        )

        val report = reloaderWith(store, contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true}}""")

        assertEquals(listOf("apply-failed", "permission-missing"), report.diagnostics.map { it.code })
    }

    /** The same when the whole apply threw: its failure names no section. */
    @Test
    fun `contacts in a reload whose apply threw is not reported as a permission problem`() = runTest {
        val store = FakeConfigStore(
            state = ConfigState(search = SearchState(contacts = false)),
            applyFailure = IllegalStateException("disk full"),
        )

        val report = reloaderWith(store, contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true}}""")

        assertEquals(listOf("apply-failed"), report.diagnostics.map { it.code })
    }

    /**
     * Already in effect, so no mutation and no section applied - and still
     * reported: the setting is on, the permission is not there.
     */
    @Test
    fun `contacts the device already has is reported without anything applied`() = runTest {
        val report = reloaderWith(FakeConfigStore(state = ConfigState(search = SearchState(contacts = true))), contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true}}""")

        assertEquals(emptyList<String>(), report.appliedMutations)
        assertEquals(listOf("permission-missing"), report.diagnostics.map { it.code })
    }

    /** Nothing was applied, and nothing depends on a key that did not land. */
    @Test
    fun `an invalid file is not reported for contacts`() = runTest {
        val report = reloaderWith(FakeConfigStore(), contactsGranted = false)
            .reload("""{"schemaVersion": 2, "search": {"contacts": true, "layout": "diagonal"}}""")

        assertFalse(report.success)
        assertTrue(report.diagnostics.none { it.code == "permission-missing" })
    }

    private class Naming(var record: Map<String, String?>?) : AppNaming {
        var replaces = 0
        override fun observe() = kotlinx.coroutines.flow.flowOf(record ?: emptyMap())
        override suspend fun replace(naming: Map<String, String?>) { replaces++; record = naming }
        override suspend fun recorded() = record != null
        override suspend fun awaitRecorded(): Unit = error("not used here")
        override suspend fun forget() { record = null }
    }

    /**
     * Write-back waits for the record of how the file writes its apps, so
     * every reload that went through makes one where none exists, not only
     * the startup one: a fresh install has no file at startup, and its first
     * push may carry no apps to apply (review on #214). A reload that went
     * through without an apps mutation leaves the device reading the file's
     * apps the same in both forms, so the empty record is the true one.
     */
    @Test
    fun `any reload that went through records the apps' form where none exists`() = runTest {
        val naming = Naming(record = null)
        val reloader = ConfigReloader(FakeConfigStore(), ReloadReportStore(context), namings = listOf(naming))

        val report = reloader.reload("""{"schemaVersion": 2, "icons": {"themed": false}}""")

        assertTrue(report.success)
        assertEquals(emptyMap<String, String?>(), naming.record)
    }

    /** Control: a failed apply leaves no record, so a later reload still makes one. */
    @Test
    fun `a failed reload records nothing`() = runTest {
        val naming = Naming(record = null)
        val store = FakeConfigStore(applyDiagnostics = listOf(Diagnostic(DiagnosticCode.ApplyFailed, "", "datastore gone")))
        val reloader = ConfigReloader(store, ReloadReportStore(context), namings = listOf(naming))

        reloader.reload("""{"schemaVersion": 2, "icons": {"themed": false}}""")

        assertEquals(null, naming.record)
    }

    /** Control: a file that does not parse was not applied, so it records nothing. */
    @Test
    fun `a file that does not parse records nothing`() = runTest {
        val naming = Naming(record = null)
        val reloader = ConfigReloader(FakeConfigStore(), ReloadReportStore(context), namings = listOf(naming))

        reloader.reload("""{"schemaVersion": 2, "icons": """)

        assertEquals(null, naming.record)
    }

    /**
     * Two records, `apps`' and `tags`' (review on #224): each missing one is
     * made, and one that exists is left as it is. With only the apps' record
     * guarded, a missing tags record stayed missing, and a tag's app read back
     * in the other form.
     */
    @Test
    fun `each missing record is made, an existing one is left`() = runTest {
        val apps = Naming(record = mapOf("app://a:A" to "a.A"))
        val tags = Naming(record = null)
        val reloader = ConfigReloader(FakeConfigStore(), ReloadReportStore(context), namings = listOf(apps, tags))

        reloader.reload("""{"schemaVersion": 2, "icons": {"themed": false}}""")

        assertEquals(0, apps.replaces)
        assertEquals(emptyMap<String, String?>(), tags.record)
    }

    /** Control: the record the apply made, or an earlier one, is not overwritten with the empty one. */
    @Test
    fun `an existing record is left as it is`() = runTest {
        val naming = Naming(record = mapOf("app://a:A" to "a.A"))
        val reloader = ConfigReloader(FakeConfigStore(), ReloadReportStore(context), namings = listOf(naming))

        reloader.reload("""{"schemaVersion": 2, "icons": {"themed": false}}""")

        assertEquals(0, naming.replaces)
        assertEquals(mapOf("app://a:A" to "a.A"), naming.record)
    }
}

package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.SearchState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
                Diagnostic(Severity.Error, "favorite-unavailable", "home.favorites[0]", "not installed"),
            )
        )
        val (reloader, _) = newReloader(store)

        val report = reloader.reload(
            """{"schemaVersion": 2, "icons": {"themed": false}, "home": {"favorites": [{"packageName": "com.example.app"}]}}"""
        )

        assertFalse(report.success)
        assertEquals(listOf("icons"), report.appliedMutations)
        assertTrue(report.diagnostics.any { it.code == "favorite-unavailable" })
    }

    @Test
    fun `apply warning diagnostics keep the report successful`() = runTest {
        val store = FakeConfigStore(
            applyDiagnostics = listOf(
                Diagnostic(Severity.Warning, "unknown-widget-provider", "home.grid.layouts.phone.items[0]", "kept"),
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
        val reloader = ConfigReloader(store, reportStore, capabilities = CapabilityDiagnostics(contactsGranted = { granted }, callGranted = { true }, transliteratorAvailable = { true }))
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
        store.applyDiagnostics = listOf(Diagnostic(Severity.Warning, "grid-overflow", "home.grid.layouts.fold.items[0]", "dropped"))

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
    ) =
        ConfigReloader(
            store, ReloadReportStore(context),
            capabilities = CapabilityDiagnostics(
                contactsGranted = { contactsGranted },
                callGranted = { callGranted },
                transliteratorAvailable = { id -> availableTransliterators?.contains(id) ?: true },
            ),
        )

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
            applyDiagnostics = listOf(Diagnostic(Severity.Error, "apply-failed", "search", "datastore gone")),
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
            applyDiagnostics = listOf(Diagnostic(Severity.Error, "apply-failed", "search", "datastore gone")),
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
            applyDiagnostics = listOf(Diagnostic(Severity.Error, "apply-failed", "search", "datastore gone")),
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
            applyDiagnostics = listOf(Diagnostic(Severity.Error, "apply-failed", "search", "datastore gone")),
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
            applyDiagnostics = listOf(Diagnostic(Severity.Error, "apply-failed", "search.actions", "database locked")),
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
}

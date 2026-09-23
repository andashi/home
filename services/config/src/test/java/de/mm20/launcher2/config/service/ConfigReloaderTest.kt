package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
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
}

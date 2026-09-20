package de.mm20.launcher2.config.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.BuiltinWidget
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.toLauncherConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigStateProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val state = ConfigState(
        themedIcons = true,
        dockEnabled = true,
        widgetsEnabled = true,
        widgets = listOf(BuiltinWidget.Apps),
    )

    private lateinit var store: FakeConfigStore
    private lateinit var reportStore: ReloadReportStore
    private lateinit var provider: ConfigStateProvider

    private fun uri(path: String): Uri =
        Uri.parse("content://${context.packageName}.state/$path")

    @Before
    fun setup() {
        store = FakeConfigStore(state)
        reportStore = ReloadReportStore(context)
        startKoin {
            modules(
                module {
                    single<ConfigStore> { store }
                    single { reportStore }
                }
            )
        }
        provider = Robolectric
            .buildContentProvider(ConfigStateProvider::class.java)
            .create("${context.packageName}.state")
            .get()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `config returns effective state in the LauncherConfig schema`() {
        val cursor = provider.query(uri("config"), null, null, null, null)

        assertEquals(1, cursor.count)
        assertEquals(listOf(ConfigStateProvider.JsonColumn), cursor.columnNames.toList())
        cursor.moveToFirst()
        val payload = cursor.getString(0)
        val config = ConfigParser.json.decodeFromString(LauncherConfig.serializer(), payload)
        assertEquals(state.toLauncherConfig(), config)
        cursor.close()
    }

    @Test
    fun `diagnostics returns json null when no reload happened`() {
        val cursor = provider.query(uri("diagnostics"), null, null, null, null)

        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("null", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `diagnostics returns the latest persisted report`() = runTest {
        val report = ReloadReport(
            success = true,
            schemaVersion = 1,
            diagnostics = listOf(Diagnostic(Severity.Warning, "unknown-key", "foo", "ignored")),
            appliedMutations = listOf("icons"),
            configSha256 = "deadbeef",
            trigger = ReloadTrigger.Broadcast,
        )
        reportStore.save(report)

        val cursor = provider.query(uri("diagnostics"), null, null, null, null)

        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        val decoded = ConfigParser.json.decodeFromString(
            ReloadReport.serializer(),
            cursor.getString(0),
        )
        assertEquals(report, decoded)
        cursor.close()
    }

    @Test
    fun `query waits for Koin when it cold-starts the process`() {
        stopKoin()
        val starter = Thread {
            Thread.sleep(150)
            startKoin {
                modules(
                    module {
                        single<ConfigStore> { store }
                        single { reportStore }
                    }
                )
            }
        }.apply { start() }

        val cursor = provider.query(uri("diagnostics"), null, null, null, null)

        starter.join()
        assertEquals(1, cursor.count)
        cursor.close()
    }

    @Test(expected = IllegalStateException::class)
    fun `query gives up when Koin never starts`() {
        stopKoin()
        provider.koinStartupTimeoutMs = 50
        try {
            provider.query(uri("diagnostics"), null, null, null, null)
        } finally {
            // Leave Koin running so tearDown's stopKoin has something to stop.
            startKoin { modules(module { single<ConfigStore> { store }; single { reportStore } }) }
        }
    }

    @Test
    fun `mime type is application json for both routes`() {
        assertEquals("application/json", provider.getType(uri("config")))
        assertEquals("application/json", provider.getType(uri("diagnostics")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown uri is rejected in query`() {
        provider.query(uri("nonsense"), null, null, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown uri is rejected in getType`() {
        provider.getType(uri("nonsense"))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `insert is rejected`() {
        provider.insert(uri("config"), null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `update is rejected`() {
        provider.update(uri("config"), null, null, null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `delete is rejected`() {
        provider.delete(uri("config"), null, null)
    }
}

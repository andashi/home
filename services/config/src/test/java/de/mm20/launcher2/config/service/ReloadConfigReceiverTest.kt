package de.mm20.launcher2.config.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ReloadTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReloadConfigReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val receiver = ReloadConfigReceiver()

    private fun configFile(): File = ConfigLocation.configFile(context)!!

    @Test
    fun `broadcast action reloads from the config file with broadcast trigger`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val reloader = ConfigReloader(store, reportStore)
        val file = configFile()
        file.parentFile?.mkdirs()
        file.writeText("""{"schemaVersion": 1, "icons": {"themed": false}}""")
        try {
            val intent = Intent(context.packageName + ReloadConfigReceiver.ActionSuffix)
            receiver.handle(context, intent, reloader)

            assertEquals(listOf("read", "apply:[icons]"), store.events)
            val report = reportStore.read()
            assertNotNull(report)
            assertTrue(report!!.success)
            assertEquals(ReloadTrigger.Broadcast, report.trigger)
            assertNotNull(report.configSha256)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing config file produces a failed report instead of throwing`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val reloader = ConfigReloader(store, reportStore)
        val file = configFile()
        file.delete()

        val intent = Intent(context.packageName + ReloadConfigReceiver.ActionSuffix)
        receiver.handle(context, intent, reloader)

        assertEquals(0, store.applyCount)
        val report = reportStore.read()
        assertNotNull(report)
        assertFalse(report!!.success)
        assertTrue(report.diagnostics.any { it.code == "read-failed" })
        assertNull(report.configSha256)
    }

    @Test
    fun `corrupt config file produces a failed report instead of throwing`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        val reloader = ConfigReloader(store, reportStore)
        val file = configFile()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json ")
        try {
            val intent = Intent(context.packageName + ReloadConfigReceiver.ActionSuffix)
            receiver.handle(context, intent, reloader)

            assertEquals(0, store.applyCount)
            val report = reportStore.read()
            assertNotNull(report)
            assertFalse(report!!.success)
            assertTrue(report.diagnostics.any { it.code == "malformed-json" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `foreign actions are ignored`() = runTest {
        val store = FakeConfigStore()
        val reportStore = ReloadReportStore(context)
        reportStore.save(de.mm20.launcher2.config.ReloadReport(success = true))
        val reloader = ConfigReloader(store, reportStore)
        val file = configFile()
        file.parentFile?.mkdirs()
        file.writeText("""{"schemaVersion": 1, "icons": {"themed": false}}""")
        try {
            receiver.handle(context, Intent("com.example.SOME_OTHER_ACTION"), reloader)

            assertEquals(emptyList<String>(), store.events)
            assertNull(reportStore.read()!!.trigger)
        } finally {
            file.delete()
        }
    }
}

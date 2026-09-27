package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.ReloadTrigger
import de.mm20.launcher2.config.Severity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReloadReportStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun reportFile(): File {
        return File(context.filesDir, "config/last-reload-report.json")
    }

    @Test
    fun `save and read round-trips the report`() = runTest {
        val store = ReloadReportStore(context)
        val report = ReloadReport(
            success = false,
            schemaVersion = 1,
            diagnostics = listOf(
                Diagnostic(DiagnosticCode.UnknownKey, "foo", "Unknown key 'foo' is ignored"),
                Diagnostic(DiagnosticCode.ApplyFailed, "home.favorites", "datastore gone"),
            ),
            appliedMutations = listOf("icons", "home.clock"),
            errorMessage = null,
        )

        store.save(report)

        assertEquals(report, store.read())
    }

    @Test
    fun `save overwrites the previous report`() = runTest {
        val store = ReloadReportStore(context)
        store.save(ReloadReport(success = true, schemaVersion = 1))
        val second = ReloadReport(success = false, errorMessage = "boom")
        store.save(second)

        assertEquals(second, store.read())
    }

    @Test
    fun `hash and trigger round-trip`() = runTest {
        val store = ReloadReportStore(context)
        val report = ReloadReport(
            success = true,
            schemaVersion = 1,
            configSha256 = "a".repeat(64),
            trigger = ReloadTrigger.FileWatcher,
        )

        store.save(report)

        assertEquals(report, store.read())
    }

    @Test
    fun `read returns null when no report exists`() = runTest {
        reportFile().delete()
        assertNull(ReloadReportStore(context).read())
    }

    /**
     * A report survives an update, and the startup check keeps an unchanged
     * file's report: an older build's report said favorite-unavailable was an
     * error, and so did this build until the next push (review on #215). A
     * report whose severities disagree with this build's table was written
     * under an older contract and is no report of this build's - read as
     * none, the startup check reloads once and writes one.
     */
    @Test
    fun `a report written under an older severity table reads as none`() = runTest {
        val file = reportFile()
        file.parentFile?.mkdirs()
        val diagnostic = """{"severity":"%s","code":"favorite-unavailable","path":"home.favorites[0]","message":"absent"}"""
        try {
            file.writeText("""{"success":false,"diagnostics":[${diagnostic.format("error")}],"configSha256":"abc"}""")
            assertNull("an older table's report", ReloadReportStore(context).read())

            // Control: the same report as this build writes it is read.
            file.writeText("""{"success":true,"diagnostics":[${diagnostic.format("warning")}],"configSha256":"abc"}""")
            assertEquals("abc", ReloadReportStore(context).read()?.configSha256)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `read returns null for a corrupt report`() = runTest {
        val file = reportFile()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json ")
        try {
            assertNull(ReloadReportStore(context).read())
        } finally {
            file.delete()
        }
    }
}

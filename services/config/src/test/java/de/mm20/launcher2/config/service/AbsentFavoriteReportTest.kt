package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.Severity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A favorite that names something absent, reloaded through the real store:
 * the report is successful, and the warning is still there naming the
 * package. Two assertions on purpose. The provisioning host fails a profile
 * on `success != true`, so an absent favorite used to fail every run until a
 * person installed the app by hand; and it prints every diagnostic, so the
 * warning is what tells a person which app. A change that turned success true
 * by dropping the diagnostic would pass the first and fail the second.
 */
@RunWith(RobolectricTestRunner::class)
class AbsentFavoriteReportTest {

    private val real = RealConfigStore()

    @After
    fun close() = real.close()

    private suspend fun reload(json: String) =
        ConfigReloader(real.store, ReloadReportStore(real.context)).reload(json)

    @Test
    fun `a favorite that is not installed leaves the report successful and still names it`() = runTest {
        real.install("com.example.here")

        val report = reload("""{"schemaVersion": 2, "home": {"favorites": ["com.example.here", "com.example.missing"]}}""")

        assertTrue(report.toString(), report.success)
        val absent = report.diagnostics.filter { it.code == "favorite-unavailable" }
        assertEquals(report.toString(), 1, absent.size)
        assertEquals(Severity.Warning, absent[0].severity)
        assertTrue(absent[0].message, "com.example.missing" in absent[0].message)
    }

    @Test
    fun `a favorite in a profile the device lacks leaves the report successful and still names it`() = runTest {
        val report = reload("""{"schemaVersion": 2, "home": {"favorites": [{"packageName": "com.example.b", "profile": "private"}]}}""")

        assertTrue(report.toString(), report.success)
        val absent = report.diagnostics.filter { it.code == "profile-unavailable" }
        assertEquals(report.toString(), 1, absent.size)
        assertEquals(Severity.Warning, absent[0].severity)
        assertTrue(absent[0].message, "com.example.b" in absent[0].message)
    }
}

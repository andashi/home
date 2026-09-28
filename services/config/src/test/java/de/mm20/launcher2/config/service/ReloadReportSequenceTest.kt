package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ReloadReport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Each report carries a sequence number and the id of the store that
 * numbered it. The number tells two saved reports of one store apart, and
 * orders them: a reload that changed nothing still moves it, which the provisioning
 * host's pull-before-push guard needs, since the config hash cannot see such
 * a reload. The store id is what makes the order safe to use. `pm clear`, a
 * reinstall or a debug-over-release swap wipes the app's files and starts
 * counting again, and a consumer then sees a new id instead of a number that
 * went backwards.
 */
@RunWith(RobolectricTestRunner::class)
class ReloadReportSequenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val configDir get() = File(context.filesDir, "config")

    @Before
    fun clean() {
        configDir.deleteRecursively()
    }

    private val report = ReloadReport(success = true, configSha256 = "abc")

    @Test
    fun `every save numbers the report one up, under one store id`() = runTest {
        val store = ReloadReportStore(context)

        val first = store.save(report)
        val second = store.save(report)

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertNotNull(first.storeId)
        assertEquals(first.storeId, second.storeId)
        assertEquals(second, store.read())
    }

    /** The path the store id exists for, and the one nothing exercises by accident. */
    @Test
    fun `a reset starts a new store id, not a lower number under the old one`() = runTest {
        val before = ReloadReportStore(context).let { it.save(report); it.save(report) }

        configDir.deleteRecursively() // what pm clear or a reinstall leaves
        val after = ReloadReportStore(context).save(report)

        assertEquals(1L, after.sequence)
        assertNotEquals(before.storeId, after.storeId)
    }

    /** Losing the report is not a reset: only the store's own record decides that. */
    @Test
    fun `a lost or unreadable report does not restart the counter`() = runTest {
        val store = ReloadReportStore(context)
        val first = store.save(report)

        File(configDir, "last-reload-report.json").writeText("{ not json")
        val next = store.save(report)

        assertEquals(2L, next.sequence)
        assertEquals(first.storeId, next.storeId)
    }

    /** An unreadable record cannot be continued; a new id says so, where reusing the old one with a fresh count would lie. */
    @Test
    fun `an unreadable counter starts a new store id`() = runTest {
        val store = ReloadReportStore(context)
        val first = store.save(report)

        File(configDir, "report-numbering.json").writeText("{ not json")
        val next = store.save(report)

        assertEquals(1L, next.sequence)
        assertNotEquals(first.storeId, next.storeId)
    }

    /**
     * A save whose report cannot be written still spends its number: the next
     * report skips it rather than reusing it. Reused, one number would name
     * two different reports; skipped, the order stays true (review on #226).
     */
    @Test
    fun `a failed save leaves a gap, never a reused number`() = runTest {
        val store = ReloadReportStore(context)
        val first = store.save(report)
        // A non-empty directory where the report goes: its rename fails.
        val reportPath = File(configDir, "last-reload-report.json")
        reportPath.delete()
        File(reportPath, "blocker").apply { parentFile!!.mkdirs(); writeText("x") }

        val failed = runCatching { store.save(report) }
        reportPath.deleteRecursively()
        val next = store.save(report)

        assertTrue("the blocked save threw", failed.isFailure)
        assertEquals(first.storeId, next.storeId)
        assertEquals(3L, next.sequence)
    }

    /** An older build's report is still on the device after the update: unknown, not zero. */
    @Test
    fun `a report an older build wrote has no sequence and no store id`() = runTest {
        configDir.mkdirs()
        File(configDir, "last-reload-report.json").writeText("""{"success":true,"configSha256":"abc"}""")

        val read = ReloadReportStore(context).read()

        assertNotNull(read)
        assertNull(read!!.sequence)
        assertNull(read.storeId)
    }

    /** ConfigWriteBack saves `last.copy(...)`, which carries the old number: the store numbers, never the caller. */
    @Test
    fun `the number is the store's, whatever the saved report carries`() = runTest {
        val store = ReloadReportStore(context)
        store.save(report)

        val saved = store.save(report.copy(sequence = 99, storeId = "forged"))

        assertEquals(2L, saved.sequence)
        assertNotEquals("forged", saved.storeId)
    }

    /**
     * Two stores share one numbering file, so they share one count, whatever
     * lock their callers hold. Production has one store (a Koin single); the
     * count must not depend on that (review on #226).
     */
    @Test
    fun `concurrent saves each get their own number, across store instances`() = runBlocking {
        val stores = listOf(ReloadReportStore(context), ReloadReportStore(context))

        val numbers = (0 until 20).map { i -> async(Dispatchers.IO) { stores[i % 2].save(report).sequence } }.awaitAll()

        assertEquals((1L..20L).toList(), numbers.map { it!! }.sorted())
    }
}

package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Where the store keeps how the file wrote each app (review on #207). It has
 * to outlive the process, since a write-back can run after a restart, and
 * "left out" (null) has to stay distinct from "not recorded".
 */
@RunWith(RobolectricTestRunner::class)
class FileAppNamingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.filesDir, "config/app-naming.json")

    @Before
    fun clean() {
        file.delete()
    }

    @Test
    fun `what one instance records, a new one reads, left-out activities included`() = runBlocking {
        FileAppNaming(context).replace(mapOf("app://a:A" to "a.A", "app://b:B" to null))

        val read = FileAppNaming(context).observe().first()

        assertEquals(mapOf("app://a:A" to "a.A", "app://b:B" to null), read)
        assertEquals("a left-out activity is recorded, not dropped", true, "app://b:B" in read)
    }

    /**
     * A first run has no record: every app reads back by the device's rule,
     * exactly as before the record existed. The first apply then records it.
     */
    @Test
    fun `no record yet reads as empty`() = runBlocking {
        assertEquals(emptyMap<String, String?>(), FileAppNaming(context).observe().first())
    }

    @Test
    fun `a corrupt record reads as empty, and the next apply replaces it`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("{ not json")
        val naming = FileAppNaming(context)

        assertEquals(emptyMap<String, String?>(), naming.observe().first())

        naming.replace(mapOf("app://a:A" to null))
        assertEquals(mapOf("app://a:A" to null), FileAppNaming(context).observe().first())
    }

    /**
     * A record that does not decode reads as empty, so it is no record: the
     * startup check must regenerate it, not skip because a file exists
     * (review on #214).
     */
    @Test
    fun `a corrupt record is not a record`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("{ not json")

        assertEquals(false, FileAppNaming(context).recorded())
    }

    /** Control: a record that decodes, even an empty one, is a record. */
    @Test
    fun `an empty record that decodes is a record`() = runBlocking {
        FileAppNaming(context).replace(emptyMap())

        assertEquals(true, FileAppNaming(context).recorded())
    }

    @Test
    fun `a replace is seen by an observer of the same instance`() = runBlocking {
        val naming = FileAppNaming(context)
        naming.observe().first()

        naming.replace(mapOf("app://a:A" to "a.A"))

        assertEquals(mapOf("app://a:A" to "a.A"), naming.observe().first())
    }
}

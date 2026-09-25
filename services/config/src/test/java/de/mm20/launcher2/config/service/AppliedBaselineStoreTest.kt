package de.mm20.launcher2.config.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #3 slice 4 (D): what the file produced once applied, recorded after each
 * reload and self-write, is what a write-back compares the device with.
 */
@RunWith(RobolectricTestRunner::class)
class AppliedBaselineStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AppliedBaselineStore(context)
    private val file = File(context.filesDir, "config/applied-baseline.json")
    private val effective = ConfigParser.json.parseToJsonElement("""{"schemaVersion":2,"home":{"grid":{"columns":4}}}""").jsonObject

    @Before
    fun cleanup() {
        file.delete()
    }

    @Test
    fun `a saved baseline reads back with its file hash`() = runBlocking {
        store.save(AppliedBaseline(configSha256 = "abc", effective = effective))

        assertEquals(AppliedBaseline("abc", effective), store.read())
    }

    @Test
    fun `no baseline reads back as none`() = runBlocking {
        assertNull(store.read())
    }

    @Test
    fun `a corrupt baseline reads back as none`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("{ not json")

        assertNull(store.read())
    }
}

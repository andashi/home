package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import de.mm20.launcher2.preferences.seedSettingsFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `search.shortcutsExcluded` (#229) at the bridge: the stored blocklist passed
 * through as it is, `packageName:userSerial`, for the store to map.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutBlocklistConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var store: LauncherDataStore

    private fun createGateway(seed: LauncherSettingsData = LauncherSettingsData()): LauncherConfigSettingsImpl {
        seedSettingsFile(context, seed)
        store = LauncherDataStore(context)
        return LauncherConfigSettingsImpl(store)
    }

    @Test
    fun `the blocklist reads and writes as stored`() = runBlocking {
        val gateway = createGateway(LauncherSettingsData(shortcutSearchBlocklist = setOf("org.a:0")))
        assertEquals(setOf("org.a:0"), gateway.readShortcutBlocklist())

        gateway.applyShortcutBlocklist(setOf("org.b:0", "org.c:11"))

        assertEquals(setOf("org.b:0", "org.c:11"), store.data.first().shortcutSearchBlocklist)
    }

    /** The state does not carry the blocklist: write-back must still hear it change. */
    @Test
    fun `changes emits when only the blocklist changes`() = runBlocking {
        val gateway = createGateway()

        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch { gateway.changes().collect { emissions.send(Unit) } }
        withTimeout(10_000) {
            emissions.receive() // on collection
            gateway.applyShortcutBlocklist(setOf("org.a:0"))
            emissions.receive()
        }
        collector.cancel()
    }
}

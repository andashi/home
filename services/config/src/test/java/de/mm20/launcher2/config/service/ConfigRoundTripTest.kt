package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.toLauncherConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The whole contract end to end (#3 slice 3): the complete example, which
 * sets every applied key off its default, goes through parse and migration,
 * the differ, the store's apply and back out through the read-back, and must
 * come out as it went in. Each step has its own tests; this is the one that
 * fails when a key is dropped between them.
 *
 * The settings side is the real one - `LauncherConfigSettings` on a real
 * DataStore. The repository-backed sections (favorites, grid, wallpaper,
 * search actions) use the shared fakes, so for those this proves the store's
 * mapping, not the repositories, which have their own tests.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigRoundTripTest {

    private lateinit var real: RealConfigStore
    private val store get() = real.store

    private val example: String =
        File(System.getProperty("repoRoot"), "docs/configuration/complete-example.json").readText()

    @Before
    fun setUp() {
        real = RealConfigStore()
        real.install("com.example.dialer")
        real.install("com.example.work.mail", real.work)
    }

    @After
    fun tearDown() {
        real.close()
    }

    @Test
    fun `the complete example comes back from the read-back as it went in`() = runTest {
        val parsed = ConfigParser.parse(example)
        assertEquals(emptyList<Diagnostic>(), parsed.diagnostics)
        val config = parsed.config
        assertNotNull(config)

        val diagnostics = store.apply(ConfigDiffer.diff(config!!, store.readState()))
        assertEquals(emptyList<Diagnostic>(), diagnostics)

        val state = store.readState()
        val served = state.toLauncherConfig()
        assertEquals(config, served)
        // As provisioning sees it: the text the read-back provider serves.
        assertEquals(
            ConfigParser.json.parseToJsonElement(example),
            ConfigParser.json.parseToJsonElement(ConfigParser.json.encodeToString(LauncherConfig.serializer(), served)),
        )
        // And what it serves, pushed again, changes nothing.
        assertEquals(emptyList<Any>(), ConfigDiffer.diff(served, state))
    }
}

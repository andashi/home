package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.toLauncherConfig
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.profiles.Profile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
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

    private lateinit var store: DefaultConfigStore
    private val personal: UserHandle = Process.myUserHandle()
    private val work: UserHandle = TestUsers.userHandleFor(10)

    private val example: String =
        File(System.getProperty("repoRoot"), "docs/configuration/complete-example.json").readText()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A settings file before DataStore reads one: its Context-based default
        // needs a resource this module cannot resolve under Robolectric.
        File(context.filesDir, "datastore").apply { mkdirs() }
            .resolve("settings.json").writeText("""{"schemaVersion":6}""")
        stopKoin()
        startKoin {
            androidContext(context)
            modules(preferencesModule)
        }
        val apps = FakeAppRepository().apply {
            this.apps["com.example.dialer" to personal] = app("com.example.dialer", personal)
            this.apps["com.example.work.mail" to work] = app("com.example.work.mail", work)
        }
        store = DefaultConfigStore(
            GlobalContext.get().get<LauncherConfigSettings>(),
            FakeHomeGridRepository(),
            FakeInitFlag(),
            HomeGridInitLock(),
            FakeGridLimitsSource(),
            FakeGridRowsSource(),
            FakeSavableSearchableRepository(),
            apps,
            FakeProfileResolver(
                personal = Profile(Profile.Type.Personal, personal, 0),
                work = Profile(Profile.Type.Work, work, 10),
            ),
            FakeWallpaperStore(),
            FakeSearchActionStore(),
        )
    }

    @After
    fun tearDown() {
        stopKoin()
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

    private fun app(packageName: String, user: UserHandle) =
        FakeApplication(ComponentName(packageName, "$packageName.MainActivity"), user)
}

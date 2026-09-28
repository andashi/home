package de.mm20.launcher2.config.service

import android.os.Process
import android.os.UserHandle
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.profiles.Profile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import de.mm20.launcher2.config.Profile as ConfigProfile

/**
 * `search.shortcutsExcluded` (#229) through [DefaultConfigStore]: the launcher
 * stores an excluded app as `packageName:userSerial`, the file names its
 * profile, and only the store knows which serial a profile has here.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutsExcludedStoreTest {

    private lateinit var settings: FakeLauncherConfigSettings
    private lateinit var profiles: FakeProfileResolver
    private lateinit var store: DefaultConfigStore

    private val personal: UserHandle = Process.myUserHandle()
    private val work: UserHandle = TestUsers.userHandleFor(10)

    @Before
    fun setUp() {
        settings = FakeLauncherConfigSettings()
        // Serials unlike the user ids on purpose: the stored form carries the serial.
        profiles = FakeProfileResolver(
            personal = Profile(Profile.Type.Personal, personal, 0),
            work = Profile(Profile.Type.Work, work, 17),
        )
        store = DefaultConfigStore(
            settings,
            FakeHomeGridRepository(),
            FakeInitFlag(),
            HomeGridInitLock(),
            FakeGridLimitsSource(),
            FakeGridRowsSource(),
            FakeSavableSearchableRepository(),
            FakeAppRepository(),
            profiles,
            FakeWallpaperStore(),
            FakeSearchActionStore(),
            FakeAppCustomizationStore(),
            FakeTagStore(),
        )
    }

    private suspend fun apply(vararg excluded: Favorite) =
        store.applyAndCapture(listOf(ConfigMutation.SetShortcutsExcluded(excluded.toList())))

    @Test
    fun `each app is stored under its profile's serial`() = runTest {
        val applied = apply(Favorite("org.a"), Favorite("org.b", ConfigProfile.Work))

        assertEquals(emptyList<Diagnostic>(), applied.diagnostics)
        assertEquals(setOf("org.a:0", "org.b:17"), settings.shortcutBlocklist)
        assertEquals(setOf(Favorite("org.a"), Favorite("org.b", ConfigProfile.Work)), applied.written.shortcutsExcluded.toSet())
        assertTrue(applied.sections.toString(), "search.shortcutsExcluded" in applied.sections)
    }

    @Test
    fun `it reads back by profile, without what the file cannot name`() = runTest {
        settings.shortcutBlocklist = setOf("org.a:0", "org.b:17", "org.gone:99", "no-serial")

        assertEquals(
            setOf(Favorite("org.a"), Favorite("org.b", ConfigProfile.Work)),
            store.readState().shortcutsExcluded.toSet(),
        )
    }

    /**
     * An entry for a profile that is gone - a removed work profile's serial
     * is never reused - is outside what the file can say, so a write leaves
     * it where it is rather than deleting what the file never named.
     */
    @Test
    fun `entries the file cannot name survive a write`() = runTest {
        settings.shortcutBlocklist = setOf("org.a:0", "org.gone:99", "no-serial")

        apply(Favorite("org.b"))

        assertEquals(setOf("org.b:0", "org.gone:99", "no-serial"), settings.shortcutBlocklist)
    }

    /**
     * Review on #235: the kept entries are taken from the list as the write
     * commits, not from a read before it. One stored while the reload was
     * between the two survives.
     */
    @Test
    fun `an entry stored while the write runs is kept, not replaced by a stale copy`() = runTest {
        settings.shortcutBlocklist = setOf("org.a:0")
        settings.beforeBlocklistCommit = { settings.shortcutBlocklist += "org.gone:99" }

        apply(Favorite("org.b"))

        assertEquals(setOf("org.b:0", "org.gone:99"), settings.shortcutBlocklist)
    }

    @Test
    fun `an app in a profile that is not here is reported, the rest applies`() = runTest {
        profiles.work = null

        val applied = apply(Favorite("org.a"), Favorite("org.b", ConfigProfile.Work))

        val diagnostic = applied.diagnostics.single()
        assertEquals("profile-unavailable", diagnostic.code)
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals("search.shortcutsExcluded[1]", diagnostic.path)
        assertEquals(
            "The work profile does not exist on this device; 'org.b' was not excluded",
            diagnostic.message,
        )
        assertEquals(setOf("org.a:0"), settings.shortcutBlocklist)
        assertEquals(listOf(Favorite("org.a")), applied.written.shortcutsExcluded)
    }

    @Test
    fun `a failed write is reported and the section not counted as applied`() = runTest {
        settings.applyFailure = IllegalStateException("disk full")

        val applied = apply(Favorite("org.a"))

        assertEquals("apply-failed", applied.diagnostics.single().code)
        assertTrue(applied.sections.toString(), "search.shortcutsExcluded" !in applied.sections)
    }
}

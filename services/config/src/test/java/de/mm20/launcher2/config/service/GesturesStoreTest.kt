package de.mm20.launcher2.config.service

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.Favorite
import de.mm20.launcher2.config.Gesture
import de.mm20.launcher2.config.GestureActionName
import de.mm20.launcher2.config.GestureConfig
import de.mm20.launcher2.config.GestureDefaults
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
 * `gestures` (#3 slice 2) through [DefaultConfigStore]: the file's apps are
 * resolved like favorites, saved where the launcher looks them up, and read
 * back as apps when that is what the device runs.
 */
@RunWith(RobolectricTestRunner::class)
class GesturesStoreTest {

    private lateinit var settings: FakeLauncherConfigSettings
    private lateinit var searchables: FakeSavableSearchableRepository
    private lateinit var apps: FakeAppRepository
    private lateinit var store: DefaultConfigStore

    private val personal: UserHandle = Process.myUserHandle()
    private val work: UserHandle = TestUsers.userHandleFor(10)

    @Before
    fun setUp() {
        settings = FakeLauncherConfigSettings()
        searchables = FakeSavableSearchableRepository()
        apps = FakeAppRepository()
        store = DefaultConfigStore(
            settings,
            FakeHomeGridRepository(),
            FakeInitFlag(),
            HomeGridInitLock(),
            FakeGridLimitsSource(),
            FakeGridRowsSource(),
            searchables,
            apps,
            FakeProfileResolver(
                personal = Profile(Profile.Type.Personal, personal, 0),
                work = Profile(Profile.Type.Work, work, 10),
            ),
            FakeWallpaperStore(),
            FakeSearchActionStore(),
            FakeAppCustomizationStore(),
            FakeTagStore(),
        )
    }

    private fun install(packageName: String, user: UserHandle = personal): FakeApplication =
        FakeApplication(ComponentName(packageName, "$packageName.MainActivity"), user)
            .also { apps.apps[packageName to user] = it }

    private fun action(name: GestureActionName) = GestureConfig.Action(name)

    @Test
    fun `actions are written by name, an installed app by its key, saved before the setting names it`() = runTest {
        val mail = install("com.example.mail", work)
        var savedFirst = false
        settings.beforeGestureWrite = { savedFirst = mail.key in searchables.saved }

        val applied = store.applyAndCapture(
            listOf(
                ConfigMutation.SetGestures(
                    mapOf(
                        Gesture.SwipeDown to action(GestureActionName.Notifications),
                        Gesture.SwipeLeft to GestureConfig.App(Favorite("com.example.mail", ConfigProfile.Work)),
                    )
                )
            )
        )

        assertEquals(emptyList<Diagnostic>(), applied.diagnostics)
        assertEquals(
            listOf(mapOf(Gesture.SwipeDown to GestureActionName.Notifications) to mapOf(Gesture.SwipeLeft to mail.key)),
            settings.gestureCalls,
        )
        // The launcher resolves a launch through the saved searchable: it must exist when the key does.
        assertTrue("the app is saved before the gesture names it", savedFirst)
        assertEquals(GestureConfig.App(Favorite("com.example.mail", ConfigProfile.Work)), applied.written.gestures[Gesture.SwipeLeft])
        assertEquals(action(GestureActionName.Notifications), applied.written.gestures[Gesture.SwipeDown])
        assertTrue(applied.sections.toString(), "gestures" in applied.sections)
    }

    /**
     * Reported, the gesture keeps what it did, the rest applies - and a
     * warning: the file is fine, this device cannot honour it yet, and it
     * heals itself when the app arrives (the arrival reload, #213). An error
     * would call a correct file wrong (#215).
     */
    @Test
    fun `an app that is not installed is reported and its gesture left alone`() = runTest {
        val applied = store.applyAndCapture(
            listOf(
                ConfigMutation.SetGestures(
                    mapOf(
                        Gesture.SwipeUp to action(GestureActionName.Recents),
                        Gesture.SwipeLeft to GestureConfig.App(Favorite("com.example.missing")),
                    )
                )
            )
        )

        val diagnostic = applied.diagnostics.single()
        assertEquals("gesture-app-unavailable", diagnostic.code)
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals("gestures.swipeLeft", diagnostic.path)
        assertEquals(
            "App 'com.example.missing' is not installed in the personal profile; gestures.swipeLeft keeps what it did",
            diagnostic.message,
        )
        assertEquals(listOf(mapOf(Gesture.SwipeUp to GestureActionName.Recents) to emptyMap<Gesture, String>()), settings.gestureCalls)
        assertEquals(GestureDefaults.All[Gesture.SwipeLeft], applied.written.gestures[Gesture.SwipeLeft])
    }

    /**
     * The file keeps the app "for the day it is installed" (gestures.md), and
     * that day is an arrival: the code the store reports for the missing app
     * has to be one the watcher reloads for. Read from what the store emits,
     * so a code added here later cannot silently fall outside the set
     * (review on #213).
     */
    @Test
    fun `a gesture's missing app is something an arrival reloads for`() = runTest {
        val applied = store.applyAndCapture(
            listOf(ConfigMutation.SetGestures(mapOf(Gesture.SwipeLeft to GestureConfig.App(Favorite("com.example.missing"))))),
        )

        val code = applied.diagnostics.single().code
        assertTrue("$code is not in ${ConfigWatcher.WaitingCodes}", code in ConfigWatcher.WaitingCodes)
    }

    @Test
    fun `an app in a profile the device does not have is reported`() = runTest {
        val applied = store.applyAndCapture(
            listOf(ConfigMutation.SetGestures(mapOf(Gesture.SwipeLeft to GestureConfig.App(Favorite("com.example.a", ConfigProfile.Private))))),
        )

        val diagnostic = applied.diagnostics.single()
        assertEquals("profile-unavailable", diagnostic.code)
        assertEquals("gestures.swipeLeft", diagnostic.path)
    }

    @Test
    fun `readState names an app a gesture opens and has a shortcut or a vanished key as null`() = runTest {
        val dialer = install("com.android.dialer")
        searchables.saved[dialer.key] = dialer
        val shortcut = FakeItem("shortcut://com.android.dialer/voicemail", "shortcut")
        searchables.saved[shortcut.key] = shortcut
        settings.state = settings.state.copy(
            gestures = GestureDefaults.All + mapOf(Gesture.SwipeLeft to null, Gesture.SwipeRight to null, Gesture.LongPress to null),
        )
        settings.launchKeys = mapOf(
            Gesture.SwipeLeft to dialer.key,
            Gesture.SwipeRight to shortcut.key,
            Gesture.LongPress to "app://com.example.uninstalled",
        )

        val gestures = store.readState().gestures

        assertEquals(GestureConfig.App(Favorite("com.android.dialer")), gestures[Gesture.SwipeLeft])
        assertEquals(null, gestures[Gesture.SwipeRight])
        assertEquals(null, gestures[Gesture.LongPress])
        assertEquals(action(GestureActionName.Search), gestures[Gesture.SwipeDown])
    }
}

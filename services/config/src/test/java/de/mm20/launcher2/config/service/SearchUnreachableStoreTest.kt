package de.mm20.launcher2.config.service

import android.os.Process
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Gesture
import de.mm20.launcher2.config.GestureActionName
import de.mm20.launcher2.config.GestureConfig
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.config.SystemBarIcons
import de.mm20.launcher2.homegrid.HomeGridInitLock
import de.mm20.launcher2.profiles.Profile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `search-unreachable` (#229): a reload that leaves the search bar hidden and
 * no gesture to search or the launcher's settings says so. It is a warning,
 * not a refusal - the device can be in that state through its own settings -
 * and it is judged on the settings as the apply left them, so a hidden bar the
 * device already had counts as much as one the file set.
 */
@RunWith(RobolectricTestRunner::class)
class SearchUnreachableStoreTest {

    private lateinit var settings: FakeLauncherConfigSettings
    private lateinit var store: DefaultConfigStore

    @Before
    fun setUp() {
        settings = FakeLauncherConfigSettings()
        store = DefaultConfigStore(
            settings,
            FakeHomeGridRepository(),
            FakeInitFlag(),
            HomeGridInitLock(),
            FakeGridLimitsSource(),
            FakeGridRowsSource(),
            FakeSavableSearchableRepository(),
            FakeAppRepository(),
            FakeProfileResolver(personal = Profile(Profile.Type.Personal, Process.myUserHandle(), 0), work = null),
            FakeWallpaperStore(),
            FakeSearchActionStore(),
            FakeAppCustomizationStore(),
            FakeTagStore(),
        )
    }

    private fun noWayOut() = Gesture.entries.associateWith { GestureConfig.Action(GestureActionName.None) as GestureConfig? }

    private suspend fun reported() = store.applyAndCapture(listOf(ConfigMutation.SetSearchBarColors(SystemBarIcons.Dark)))
        .diagnostics.filter { it.code == "search-unreachable" }

    @Test
    fun `a hidden bar with nothing that reaches search or settings is reported`() = runTest {
        settings.state = ConfigState(searchBarHidden = true, gestures = noWayOut())

        val diagnostic = reported().single()

        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals("home.searchBar.hidden", diagnostic.path)
    }

    @Test
    fun `a gesture to the launcher's settings is a way out, and nothing is reported`() = runTest {
        settings.state = ConfigState(
            searchBarHidden = true,
            gestures = noWayOut() + (Gesture.LongPress to GestureConfig.Action(GestureActionName.LauncherSettings)),
        )

        assertEquals(emptyList<Any>(), reported())
    }

    @Test
    fun `a shown bar is never reported`() = runTest {
        settings.state = ConfigState(searchBarHidden = false, gestures = noWayOut())

        assertEquals(emptyList<Any>(), reported())
    }
}

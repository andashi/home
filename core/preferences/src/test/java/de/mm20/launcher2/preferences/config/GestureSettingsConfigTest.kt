package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.Gesture
import de.mm20.launcher2.config.GestureActionName
import de.mm20.launcher2.config.GestureConfig
import de.mm20.launcher2.config.GestureDefaults
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import de.mm20.launcher2.preferences.seedSettingsFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `gestures` (#3 slice 2) against the stored gesture settings. */
@RunWith(RobolectricTestRunner::class)
class GestureSettingsConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var store: LauncherDataStore

    private fun createGateway(seed: LauncherSettingsData = LauncherSettingsData()): LauncherConfigSettingsImpl {
        seedSettingsFile(context, seed)
        store = LauncherDataStore(context)
        return LauncherConfigSettingsImpl(store)
    }

    private fun action(name: GestureActionName) = GestureConfig.Action(name)

    @Test
    fun `fresh settings read back the gesture defaults`() = runTest {
        assertEquals(GestureDefaults.All, createGateway().readState().gestures)
    }

    @Test
    fun `every action the file can name reads back by its name`() = runTest {
        val gateway = createGateway(
            LauncherSettingsData(
                gesturesSwipeDown = GestureAction.Notifications,
                gesturesSwipeUp = GestureAction.QuickSettings,
                gesturesSwipeLeft = GestureAction.Recents,
                gesturesSwipeRight = GestureAction.PowerMenu,
                gesturesDoubleTap = GestureAction.LauncherSettings,
                gesturesLongPress = GestureAction.Search,
                gesturesHomeButton = GestureAction.NoAction,
            )
        )

        assertEquals(
            mapOf(
                Gesture.SwipeDown to action(GestureActionName.Notifications),
                Gesture.SwipeUp to action(GestureActionName.QuickSettings),
                Gesture.SwipeLeft to action(GestureActionName.Recents),
                Gesture.SwipeRight to action(GestureActionName.PowerMenu),
                Gesture.DoubleTap to action(GestureActionName.LauncherSettings),
                Gesture.LongPress to action(GestureActionName.Search),
                Gesture.HomeButton to action(GestureActionName.None),
            ),
            gateway.readState().gestures,
        )
    }

    /**
     * Only the store can tell an app the file can name from a shortcut, so a
     * launch reads as null here and its key comes separately. The feed is no
     * action a file can set, so it reads as null too.
     */
    @Test
    fun `a launch and the feed read back as null, the launch keys separately`() = runTest {
        val gateway = createGateway(
            LauncherSettingsData(
                gesturesSwipeLeft = GestureAction.Launch("app://com.android.dialer"),
                gesturesSwipeRight = GestureAction.Feed,
            )
        )

        val gestures = gateway.readState().gestures
        assertEquals(null, gestures[Gesture.SwipeLeft])
        assertEquals(null, gestures[Gesture.SwipeRight])
        assertEquals(action(GestureActionName.Search), gestures[Gesture.SwipeDown])
        assertEquals(mapOf(Gesture.SwipeLeft to "app://com.android.dialer"), gateway.readGestureLaunchKeys())
    }

    @Test
    fun `applyGestures writes the actions and launches it carries and nothing else`() = runTest {
        val seed = LauncherSettingsData(gesturesLongPress = GestureAction.Recents)
        val gateway = createGateway(seed)

        val written = gateway.applyGestures(
            actions = mapOf(Gesture.SwipeDown to GestureActionName.Notifications, Gesture.DoubleTap to GestureActionName.None),
            launches = mapOf(Gesture.SwipeRight to "app://com.example.mail"),
        )

        assertEquals(
            seed.copy(
                gesturesSwipeDown = GestureAction.Notifications,
                gesturesDoubleTap = GestureAction.NoAction,
                gesturesSwipeRight = GestureAction.Launch("app://com.example.mail"),
            ),
            store.data.first(),
        )
        // As written, a launch as null the way readState has it.
        assertEquals(
            GestureDefaults.All + mapOf(
                Gesture.SwipeDown to action(GestureActionName.Notifications),
                Gesture.DoubleTap to action(GestureActionName.None),
                Gesture.SwipeRight to null,
                Gesture.LongPress to action(GestureActionName.Recents),
            ),
            written.gestures,
        )
    }

    /** Two apps both read as null here: write-back must still hear that the key changed. */
    @Test
    fun `changes emits when a gesture moves from one app to another`() = runBlocking {
        val gateway = createGateway(LauncherSettingsData(gesturesSwipeLeft = GestureAction.Launch("app://a")))

        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch { gateway.changes().collect { emissions.send(Unit) } }
        withTimeout(10_000) {
            emissions.receive() // on collection
            gateway.applyGestures(actions = emptyMap(), launches = mapOf(Gesture.SwipeLeft to "app://b"))
            emissions.receive()
        }
        collector.cancel()
    }
}

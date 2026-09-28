package de.mm20.launcher2.ui.launcher.scaffold

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.SearchBarStyle
import android.content.Context
import android.os.Bundle
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.ui.launcher.scaffold.components.NotificationsComponent
import de.mm20.launcher2.ui.launcher.scaffold.components.SearchComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The lockout page (#229). With the search bar hidden, the launcher swaps its
 * home screen for a fallback when nothing is left to reach search or the
 * launcher's settings. The check counted only search, so a gesture opening
 * settings - the way out its own comment names - did not count.
 *
 * The cases are shared with LockedOutTest (core:config), which reads the same
 * table against the file's version of this rule: two implementations kept
 * together by one list, which is a declared input of both test tasks. Each
 * action goes through the launcher's own [scaffoldGesture], so the component
 * it opens is the one the activity gives it, not a copy of that choice.
 */
@RunWith(RobolectricTestRunner::class)
class LockoutTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** An app a gesture opens; LaunchComponent only needs something to launch. */
    private class App : SavableSearchable {
        override val key = "app://org.example.app"
        override val domain = "test"
        override val label = "app"
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw UnsupportedOperationException()
        override fun getSerializer(): SearchableSerializer = throw UnsupportedOperationException()
    }

    /**
     * The file's word for an action to the action the launcher stores, the
     * pairing GestureSettingsConfigTest pins word by word. Which component an
     * action opens is the launcher's own [scaffoldGesture], not restated here.
     */
    private fun action(value: kotlinx.serialization.json.JsonElement): GestureAction =
        if (value is JsonObject) GestureAction.Launch(App().key) else when (val name = value.jsonPrimitive.content) {
            "none" -> GestureAction.NoAction
            "search" -> GestureAction.Search
            "launcher-settings" -> GestureAction.LauncherSettings
            "notifications" -> GestureAction.Notifications
            "quick-settings" -> GestureAction.QuickSettings
            "recents" -> GestureAction.Recents
            "power-menu" -> GestureAction.PowerMenu
            "screen-lock" -> GestureAction.ScreenLock
            else -> error("no action for '$name'")
        }

    private val gestureNames = mapOf(
        "swipeUp" to Gesture.SwipeUp, "swipeDown" to Gesture.SwipeDown, "swipeLeft" to Gesture.SwipeLeft,
        "swipeRight" to Gesture.SwipeRight, "doubleTap" to Gesture.DoubleTap, "longPress" to Gesture.LongPress,
        "homeButton" to Gesture.HomeButton,
    )

    @Test
    fun `every shared case agrees with the launcher's check`() {
        val root = File(System.getProperty("repoRoot") ?: error("repoRoot is not set (app/ui/build.gradle.kts)"))
        val cases = Json.parseToJsonElement(File(root, "docs/configuration/search-unreachable-cases.json").readText())
            .jsonObject.getValue("cases").jsonArray
        check(cases.size > 1) { "no cases read" }

        val disagreements = cases.map { it.jsonObject }.mapNotNull { case ->
            val searchComponent = SearchComponent()
            val gestures = case.getValue("gestures").jsonObject.mapValues { (key, value) ->
                scaffoldGesture(action(value), App(), gestureNames.getValue(key), searchComponent, activity)
            }
            val config = ScaffoldConfiguration(
                homeComponent = NotificationsComponent,
                searchComponent = searchComponent,
                swipeUp = gestures["swipeUp"],
                swipeDown = gestures["swipeDown"],
                swipeLeft = gestures["swipeLeft"],
                swipeRight = gestures["swipeRight"],
                doubleTap = gestures["doubleTap"],
                longPress = gestures["longPress"],
                homeButton = gestures["homeButton"],
                searchBarStyle = if (case.getValue("hidden").jsonPrimitive.boolean) SearchBarStyle.Hidden else SearchBarStyle.Transparent,
                backgroundColor = Color.Black,
                darkSearchBar = false,
            )
            val expected = case.getValue("lockedOut").jsonPrimitive.boolean
            if (config.isUseless() == expected) null else "${case.getValue("case").jsonPrimitive.content}: expected $expected"
        }

        assertEquals(emptyList<String>(), disagreements)
    }
}

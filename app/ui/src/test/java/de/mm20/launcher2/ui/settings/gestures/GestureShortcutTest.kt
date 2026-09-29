package de.mm20.launcher2.ui.settings.gestures

import android.content.Context
import android.os.Bundle
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gesture settings name a configured target by the key the gesture stores
 * (review on #254). While Alice and Bob are merged, a gesture on Bob's stored
 * key resolves to the merged contact, which carries the merged key: matched by
 * that key the screen showed no target for a gesture that works, and choosing
 * the option again wrote the merged key - a new row that follows the
 * provider's tie after a split, the one thing a split cannot give back.
 */
class GestureShortcutTest {

    private val bobStored = "contact://0r2-B"
    private val bobMerged = GestureShortcut(bobStored, Item("contact://0r1-A.0r2-B"))
    private val app = GestureShortcut("app://com.example.mail", Item("app://com.example.mail"))

    @Test
    fun aGestureOnAMergedContactShowsItsTarget() {
        assertEquals(bobMerged, shortcutFor(GestureAction.Launch(bobStored), listOf(app, bobMerged)))
    }

    @Test
    fun choosingAConfiguredTargetKeepsTheKeyTheGestureStores() {
        assertEquals(GestureAction.Launch(bobStored), launchFor(bobMerged))
    }

    /** Control, green in both states: an item that resolves to its own key. */
    @Test
    fun anAppShowsAndKeepsItsKey() {
        assertEquals(app, shortcutFor(GestureAction.Launch("app://com.example.mail"), listOf(app, bobMerged)))
        assertEquals(GestureAction.Launch("app://com.example.mail"), launchFor(app))
    }

    private class Item(override val key: String) : SavableSearchable {
        override val domain = "test"
        override val label = key
        override val preferDetailsOverLaunch = false
        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?) = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon = throw NotImplementedError()
        override fun getSerializer(): SearchableSerializer = throw NotImplementedError()
    }
}

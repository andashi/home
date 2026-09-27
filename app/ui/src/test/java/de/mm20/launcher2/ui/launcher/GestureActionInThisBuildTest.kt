package de.mm20.launcher2.ui.launcher

import de.mm20.launcher2.preferences.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A feature flag that gates the settings screen but not the gesture that
 * opens the feature is a bypass (#3 slice 2): a stored value reaches what the
 * build hides.
 */
class GestureActionInThisBuildTest {

    @Test
    fun `a stored feed gesture does nothing in a build without the feed`() {
        assertEquals(GestureAction.NoAction, GestureAction.Feed.inThisBuild(feedEnabled = false))
    }

    /** Control: where the feed exists, the gesture opens it. */
    @Test
    fun `a stored feed gesture opens the feed where the build has it`() {
        assertEquals(GestureAction.Feed, GestureAction.Feed.inThisBuild(feedEnabled = true))
    }

    @Test
    fun `every other action is left as it is`() {
        for (action in listOf(GestureAction.Search, GestureAction.ScreenLock, GestureAction.Launch("app://a"), GestureAction.NoAction)) {
            assertEquals(action, action.inThisBuild(feedEnabled = false))
        }
    }
}

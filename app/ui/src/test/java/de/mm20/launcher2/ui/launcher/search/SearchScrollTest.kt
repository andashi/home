package de.mm20.launcher2.ui.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** Search scrolls as one, whichever panes are shown (#91). */
class SearchScrollTest {

    private val still = PaneScroll(forward = false, backward = false)
    private val scrolled = PaneScroll(forward = true, backward = true)

    @Test
    fun `with two panes either pane scrolls search`() {
        assertEquals(scrolled, searchScroll(apps = still, results = scrolled, twoPane = true))
    }

    /** Review on #99: a results pane that is gone must not hold the bar. */
    @Test
    fun `with one column a stale results pane does not count`() {
        assertEquals(still, searchScroll(apps = still, results = scrolled, twoPane = false))
    }

    /** Control: the apps list counts in both layouts. */
    @Test
    fun `the apps list always counts`() {
        assertEquals(scrolled, searchScroll(apps = scrolled, results = still, twoPane = false))
    }
}

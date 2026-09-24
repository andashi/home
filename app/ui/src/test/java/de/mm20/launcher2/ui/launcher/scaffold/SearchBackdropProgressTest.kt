package de.mm20.launcher2.ui.launcher.scaffold

import org.junit.Assert.assertEquals
import org.junit.Test

/** The backdrop behind search follows search, wherever search is (#91). */
class SearchBackdropProgressTest {

    @Test
    fun `search as a secondary page follows its progress`() {
        assertEquals(0.4f, searchBackdropProgress(homeIsSearch = false, currentIsSearch = true, progress = 0.4f))
    }

    /** Control: another secondary page or none leaves the home setting. */
    @Test
    fun `no search open is the home setting`() {
        assertEquals(0f, searchBackdropProgress(homeIsSearch = false, currentIsSearch = false, progress = 0.7f))
    }

    @Test
    fun `search as the home component (assistant mode) is fully open`() {
        assertEquals(1f, searchBackdropProgress(homeIsSearch = true, currentIsSearch = false, progress = 0f))
    }
}

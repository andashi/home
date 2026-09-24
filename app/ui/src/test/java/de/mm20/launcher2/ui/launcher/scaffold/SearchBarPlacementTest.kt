package de.mm20.launcher2.ui.launcher.scaffold

import de.mm20.launcher2.ui.launcher.scaffold.SearchBarPosition.Bottom
import de.mm20.launcher2.ui.launcher.scaffold.SearchBarPosition.Top
import org.junit.Assert.assertEquals
import org.junit.Test

/** #107: the bar moves from its home position to its search position with the transition. */
class SearchBarPlacementTest {

    @Test
    fun `bottom at home, top in search moves the bar up with the progress`() {
        assertEquals(1f, SearchBarPlacement.bias(Bottom, Top, 0f), 1e-6f)
        assertEquals(0f, SearchBarPlacement.bias(Bottom, Top, 0.5f), 1e-6f)
        assertEquals(-1f, SearchBarPlacement.bias(Bottom, Top, 1f), 1e-6f)
    }

    @Test
    fun `top at home, bottom in search moves it down`() {
        assertEquals(-1f, SearchBarPlacement.bias(Top, Bottom, 0f), 1e-6f)
        assertEquals(0f, SearchBarPlacement.bias(Top, Bottom, 0.5f), 1e-6f)
        assertEquals(1f, SearchBarPlacement.bias(Top, Bottom, 1f), 1e-6f)
    }

    /** Control: the same position on both pages never moves, today's behavior. */
    @Test
    fun `the same position on both pages stays put`() {
        for (p in listOf(0f, 0.5f, 1f)) {
            assertEquals(-1f, SearchBarPlacement.bias(Top, Top, p), 1e-6f)
            assertEquals(1f, SearchBarPlacement.bias(Bottom, Bottom, p), 1e-6f)
        }
    }

    @Test
    fun `progress outside zero to one is clamped`() {
        assertEquals(1f, SearchBarPlacement.bias(Bottom, Top, -0.3f), 1e-6f)
        assertEquals(-1f, SearchBarPlacement.bias(Bottom, Top, 1.4f), 1e-6f)
    }

    @Test
    fun `the bar counts as at the search position from halfway`() {
        assertEquals(Bottom, SearchBarPlacement.positionAt(Bottom, Top, 0f))
        assertEquals(Bottom, SearchBarPlacement.positionAt(Bottom, Top, 0.49f))
        assertEquals(Top, SearchBarPlacement.positionAt(Bottom, Top, 0.5f))
        assertEquals(Top, SearchBarPlacement.positionAt(Bottom, Top, 1f))
    }
}

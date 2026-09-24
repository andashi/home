package de.mm20.launcher2.ui.launcher.scaffold

import de.mm20.launcher2.ui.launcher.scaffold.SearchBarPosition.Bottom
import de.mm20.launcher2.ui.launcher.scaffold.SearchBarPosition.Top
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** At the two positions the offset is what the Hidden style always slid by. */
    @Test
    fun `the hidden offset slides toward the bar's edge`() {
        val p = 0.25f
        assertEquals(-(1 - p) * (1 - p) * (128f + 24f), SearchBarPlacement.hiddenOffset(-1f, p, 24f, 48f), 1e-4f)
        assertEquals((1 - p) * (1 - p) * (128f + 48f), SearchBarPlacement.hiddenOffset(1f, p, 24f, 48f), 1e-4f)
        assertEquals(0f, SearchBarPlacement.hiddenOffset(1f, 1f, 24f, 48f), 1e-4f)
    }

    /**
     * #115 review: with the bar moving from the bottom to the top, predictive
     * back crosses the middle while the hidden offset is still large; the
     * offset must not jump from one edge to the other there.
     */
    @Test
    fun `the hidden offset is continuous while the bar moves`() {
        var previous = SearchBarPlacement.hiddenOffset(SearchBarPlacement.bias(Bottom, Top, 0f), 0f, 24f, 48f)
        for (step in 1..100) {
            val progress = step / 100f
            val offset = SearchBarPlacement.hiddenOffset(SearchBarPlacement.bias(Bottom, Top, progress), progress, 24f, 48f)
            assertTrue("jump of ${offset - previous} dp at $progress", kotlin.math.abs(offset - previous) < 8f)
            previous = offset
        }
    }
}

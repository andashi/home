package de.mm20.launcher2.ui.launcher.grid

import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridGeometry
import de.mm20.launcher2.homegrid.HomeGridLayouts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #114 review: on the cover a cell that reaches past the window is drawn
 * clipped, so moving or resizing it there would work on the clipped span and
 * misplace the stored item. Its geometry is edited on the inner display.
 */
class GridEditWindowTest {

    private val cover = HomeGridGeometry.derive(FormFactor.Fold, 4, widthDp = 396f, heightDp = 622f)
    private val inner = HomeGridGeometry.derive(FormFactor.Fold, 4, widthDp = 790f, heightDp = 780f)

    @Test
    fun `an eight-wide dock reaches past the cover`() {
        assertTrue(extendsPastWindow(dockItem(0, 5, 8, 1, HomeGridLayouts.Fold), cover))
    }

    @Test
    fun `an item inside the cover's half does not`() {
        assertFalse(extendsPastWindow(gridItem("right", 5, 0, 2, 2, HomeGridLayouts.Fold), cover))
        assertFalse(extendsPastWindow(dockItem(7, 0, 1, 7, HomeGridLayouts.Fold), cover))
    }

    /** Control: the inner display draws everything, nothing is clipped. */
    @Test
    fun `nothing reaches past the inner display`() {
        assertFalse(extendsPastWindow(dockItem(0, 5, 8, 1, HomeGridLayouts.Fold), inner))
    }
}

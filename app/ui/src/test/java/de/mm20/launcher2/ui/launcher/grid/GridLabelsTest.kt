package de.mm20.launcher2.ui.launcher.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `home.grid.labels`: what the label under an item says (#75). */
class GridLabelsTest {

    private val widget = gridItem("clock", 0, 0, 2, 1)
    private val dock = dockItem(0, 5, 4, 1)

    @Test
    fun `an AppWidget is labelled with its provider's label`() {
        assertEquals("Digital clock", gridItemLabel(widget, { "Digital clock" }, { "Clock" }))
    }

    @Test
    fun `without a provider label the providing app's name is used`() {
        assertEquals("Clock", gridItemLabel(widget, { null }, { "Clock" }))
        assertEquals("Clock", gridItemLabel(widget, { "  " }, { "Clock" }))
    }

    @Test
    fun `nothing known means no label, not an empty one`() {
        assertNull(gridItemLabel(widget, { null }, { null }))
        assertNull(gridItemLabel(widget, { "" }, { " " }))
    }

    @Test
    fun `the favorites widget never has a label`() {
        var asked = false
        assertNull(gridItemLabel(dock, { asked = true; "Favorites" }, { asked = true; "Andashi" }))
        assertEquals("the dock does not even look a label up", false, asked)
    }
}

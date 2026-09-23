package de.mm20.launcher2.ui.launcher.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `home.grid.labels`: what the label under an item says (#75). */
class GridLabelsTest {

    private val widget = gridItem("clock", 0, 0, 2, 1)
    private val dock = dockItem(0, 5, 4, 1)

    /**
     * The reference labels a widget with its app - "Kalender", "Netatmo",
     * "Tesla" - never with the widget's own name; seven clock widgets read
     * "Clock", not "Digital clock" seven times.
     */
    @Test
    fun `an AppWidget is labelled with its app's name`() {
        assertEquals("Clock", gridItemLabel(widget, { "Digital clock" }, { "Clock" }))
    }

    @Test
    fun `without an app name the provider's label is used`() {
        assertEquals("Digital clock", gridItemLabel(widget, { "Digital clock" }, { null }))
        assertEquals("Digital clock", gridItemLabel(widget, { "Digital clock" }, { "  " }))
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

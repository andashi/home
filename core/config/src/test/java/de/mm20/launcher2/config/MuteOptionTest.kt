package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `home.grid.layouts.<layout>.items[].mute` (#78): an external widget's own
 * colours turned grey. An option like its three siblings, so an absent key
 * means its default, `false`, rather than unmanaged; the launcher's own
 * widget (the favorites dock) is glass-native and ignores it, which the
 * report says.
 */
class MuteOptionTest {

    private fun parse(items: String) =
        ConfigParser.parse("""{ "schemaVersion": 2, "home": { "grid": { "layouts": { "phone": { "items": $items } } } } }""")

    private fun itemsOf(items: String) = parse(items).config!!.home!!.grid!!.layouts!!.getValue("phone").items

    private val clock = """{ "id": "clock", "widget": "com.android.deskclock/.DigitalAppWidgetProvider", "x": 0, "y": 0, "w": 4, "h": 2 }"""

    @Test
    fun `mute parses, and an absent one is its default`() {
        assertEquals(true, itemsOf("""[${clock.dropLast(1)}, "mute": true }]""").single().mute)
        assertEquals(null, itemsOf("[$clock]").single().mute)
        assertEquals(false, GridItemConfig.OptionDefaults["mute"])
    }

    @Test
    fun `mute on an external widget is no diagnostic`() {
        assertEquals(emptyList<Diagnostic>(), parse("""[${clock.dropLast(1)}, "mute": true }]""").diagnostics)
    }

    @Test
    fun `a different mute is a mutation, the same one is none`() {
        val stored = GridItemConfig("clock", "com.android.deskclock/.DigitalAppWidgetProvider", 0, 0, 4, 2, mute = false)
        assertTrue(!stored.copy(mute = true).matches(stored))
        assertTrue(stored.copy(mute = false).matches(stored))
        // Absent in the file: its default, which the store keeps as false.
        assertTrue(stored.copy(mute = null).matches(stored))
    }

    @Test
    fun `mute is served back as the store keeps it`() {
        val item = GridItemConfig("clock", "com.android.deskclock/.DigitalAppWidgetProvider", 0, 0, 4, 2, mute = true)
        val state = ConfigState(gridLayouts = mapOf("phone" to GridLayoutConfig(listOf(item))))

        assertEquals(true, state.toLauncherConfig().home?.grid?.layouts?.getValue("phone")?.items?.single()?.mute)
    }

    /**
     * The favorites dock is the launcher's own, drawn on glass: mute has no
     * effect there. Said once, as a warning, rather than accepted in silence -
     * a flag that does nothing where it was written is the #140 class.
     */
    @Test
    fun `mute on the favorites dock is reported as ignored`() {
        val diagnostics = parse("""[{ "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1, "mute": true }]""").diagnostics

        val ignored = diagnostics.single()
        assertEquals("grid-option-ignored", ignored.code)
        assertEquals(Severity.Warning, ignored.severity)
        assertEquals("home.grid.layouts.phone.items[0].mute", ignored.path)
    }

    /** Controls: `mute: false` on the dock asks for nothing, and so says nothing. */
    @Test
    fun `mute false or absent on the favorites dock says nothing`() {
        for (mute in listOf(""", "mute": false""", "")) {
            val text = """[{ "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1$mute }]"""
            assertEquals(text, emptyList<Diagnostic>(), parse(text).diagnostics)
        }
    }
}

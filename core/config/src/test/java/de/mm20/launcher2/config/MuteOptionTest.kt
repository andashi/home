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

    /**
     * Review on #231: an option the file leaves out is its default (ADR 0002,
     * OptionDefaults), so a stored option away from its default and absent
     * from the file is a difference - the next reload resets it. It was
     * compared as "unmanaged", and reset only when some other change made the
     * grid apply. The stored side is what the store emits: the read-back
     * always carries all four options (DefaultConfigStore.toConfig).
     */
    @Test
    fun `an option left out is its default, so a stored one away from it is a difference`() {
        val defaults = GridItemConfig("clock", "a.b/.C", 0, 0, 4, 2, borderless = false, background = true, themeColors = true, mute = false)
        val absent = GridItemConfig("clock", "a.b/.C", 0, 0, 4, 2)
        for ((option, stored) in listOf(
            "borderless" to defaults.copy(borderless = true),
            "background" to defaults.copy(background = false),
            "themeColors" to defaults.copy(themeColors = false),
            "mute" to defaults.copy(mute = true),
        )) {
            assertTrue("$option away from its default, absent in the file", !absent.matches(stored))
        }
        // Controls: absent against the defaults, and the geometry rule unchanged -
        // a file without geometry still matches whatever the launcher placed (D5).
        assertTrue(absent.matches(defaults))
        assertTrue(GridItemConfig("clock", "a.b/.C").matches(defaults))
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

    /**
     * Not mute alone: the dock reads none of the four options (GridCell draws
     * it as a plain glass card), so each one set away from its default is
     * reported, by the same rule, which follows OptionDefaults - a fifth
     * option is covered without anyone remembering it.
     */
    @Test
    fun `every option away from its default on the favorites dock is reported`() {
        val options = """"borderless": true, "background": false, "themeColors": false, "mute": true"""
        val diagnostics = parse("""[{ "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1, $options }]""").diagnostics

        assertEquals(
            GridItemConfig.OptionDefaults.keys.map { "home.grid.layouts.phone.items[0].$it" }.sorted(),
            diagnostics.filter { it.code == "grid-option-ignored" && it.severity == Severity.Warning }.map { it.path }.sorted(),
        )
    }

    /**
     * The rule above reads each option through [GridItemConfig.options]: one
     * that OptionDefaults names and options does not would read as absent and
     * be skipped in silence. The two must name the same keys.
     */
    @Test
    fun `options names exactly the keys OptionDefaults does`() {
        assertEquals(GridItemConfig.OptionDefaults.keys, GridItemConfig("x", "a.b/.C").options.keys)
    }

    /** Controls: an option at its default on the dock asks for nothing, and so says nothing. */
    @Test
    fun `every option at its default on the favorites dock says nothing`() {
        val options = """"borderless": false, "background": true, "themeColors": true, "mute": false"""
        val text = """[{ "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1, $options }]"""
        assertEquals(emptyList<Diagnostic>(), parse(text).diagnostics)
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

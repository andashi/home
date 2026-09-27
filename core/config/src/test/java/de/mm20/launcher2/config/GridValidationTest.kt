package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `home.grid` rules of [ConfigValidator], each one an Error at the item's path. */
class GridValidationTest {

    private fun parse(grid: String): ConfigParseResult = ConfigParser.parse(
        """{ "schemaVersion": 2, "home": { "grid": $grid } }"""
    )

    private fun errors(grid: String, code: String): List<Diagnostic> =
        parse(grid).diagnostics.filter { it.severity == Severity.Error && it.code == code }

    @Test
    fun `a well-formed grid produces no diagnostics`() {
        val result = parse(
            """{ "columns": 4, "locked": false, "layouts": { "phone": { "items": [
                 { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
                 { "id": "clock-1", "widget": "com.android.deskclock/.DigitalAppWidgetProvider", "w": 4, "h": 2 }
               ] } } }"""
        )

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertNotNull(result.config)
    }

    @Test
    fun `columns must be between 2 and 8`() {
        assertEquals("home.grid.columns", errors("""{ "columns": 1 }""", "invalid-grid-columns").single().path)
        assertEquals(1, errors("""{ "columns": 9 }""", "invalid-grid-columns").size)
        assertEquals(0, errors("""{ "columns": 2 }""", "invalid-grid-columns").size)
        assertEquals(0, errors("""{ "columns": 8 }""", "invalid-grid-columns").size)
    }

    @Test
    fun `an item id must match the pattern`() {
        for (bad in listOf("", "Dock", "-dock", "dock.1", "a".repeat(33), "dock 1")) {
            val found = errors(
                """{ "layouts": { "phone": { "items": [ { "id": "$bad", "widget": "favorites" } ] } } }""",
                "invalid-grid-item-id",
            )
            assertEquals("'$bad' must be rejected", 1, found.size)
            assertEquals("home.grid.layouts.phone.items[0]", found.single().path)
        }
        for (good in listOf("dock", "clock-1", "a", "0", "a".repeat(32))) {
            assertEquals(
                "'$good' must be accepted",
                0,
                errors("""{ "layouts": { "phone": { "items": [ { "id": "$good", "widget": "favorites" } ] } } }""", "invalid-grid-item-id").size,
            )
        }
    }

    @Test
    fun `item ids are unique per layout, not across layouts`() {
        val duplicate = errors(
            """{ "layouts": { "phone": { "items": [
                 { "id": "a", "widget": "favorites" }, { "id": "a", "widget": "favorites" } ] } } }""",
            "duplicate-grid-item-id",
        )
        assertEquals("home.grid.layouts.phone.items[1]", duplicate.single().path)

        val acrossLayouts = errors(
            """{ "layouts": { "phone": { "items": [ { "id": "a", "widget": "favorites" } ] },
                              "fold": { "items": [ { "id": "a", "widget": "favorites" } ] } } }""",
            "duplicate-grid-item-id",
        )
        assertEquals(emptyList<Diagnostic>(), acrossLayouts)
    }

    @Test
    fun `a widget is favorites or a component name`() {
        for (bad in listOf("", "favourites", "com.example", "com.example/", "/Cls", "not a component")) {
            val found = errors(
                """{ "layouts": { "phone": { "items": [ { "id": "a", "widget": "$bad" } ] } } }""",
                "invalid-grid-widget",
            )
            assertEquals("'$bad' must be rejected", 1, found.size)
            assertEquals("home.grid.layouts.phone.items[0]", found.single().path)
        }
        for (good in listOf("favorites", "com.example/.Widget", "com.example.app/com.example.app.WidgetProvider")) {
            assertEquals(
                "'$good' must be accepted",
                0,
                errors("""{ "layouts": { "phone": { "items": [ { "id": "a", "widget": "$good" } ] } } }""", "invalid-grid-widget").size,
            )
        }
    }

    @Test
    fun `geometry must be positions from 0 and sizes from 1, up to the bound`() {
        fun geometry(x: Int?, y: Int?, w: Int?, h: Int?): List<Diagnostic> {
            val fields = listOfNotNull(
                x?.let { "\"x\": $it" }, y?.let { "\"y\": $it" }, w?.let { "\"w\": $it" }, h?.let { "\"h\": $it" },
            ).joinToString(", ")
            return errors(
                """{ "layouts": { "phone": { "items": [ { "id": "a", "widget": "favorites", $fields } ] } } }""",
                "invalid-grid-geometry",
            )
        }

        assertEquals(1, geometry(-1, 0, 1, 1).size)
        assertEquals(1, geometry(0, -1, 1, 1).size)
        assertEquals(1, geometry(0, 0, 0, 1).size)
        assertEquals(1, geometry(0, 0, 1, 0).size)
        assertEquals("home.grid.layouts.phone.items[0]", geometry(0, 0, 1, 0).single().path)
        assertEquals(0, geometry(0, 0, 1, 1).size)
        assertEquals(1, geometry(65, 0, 1, 1).size)
        assertEquals(1, geometry(0, 65, 1, 1).size)
        assertEquals(1, geometry(0, 0, 65, 1).size)
        assertEquals(1, geometry(0, 0, 1, 65).size)
        assertEquals("no Int overflow past the bound", 1, geometry(0, Int.MAX_VALUE, 1, Int.MAX_VALUE).size)
        assertEquals(0, geometry(64, 64, 64, 64).size)
        assertEquals("absent fields are fine", 0, geometry(null, null, 2, null).size)
    }

    /**
     * A lone coordinate cannot be honoured on any device, and placing the item
     * at the first free cells discards the one thing the author wrote. So it
     * is an error, like every other malformed grid geometry, and the file is
     * not applied. It was a warning until the severity table made it
     * readable next to invalid-grid-geometry; no zone file had one - the
     * provisioning generator always writes x and y together.
     */
    @Test
    fun `a lone coordinate is an error and the file does not apply`() {
        for (lone in listOf(""""x": 2""", """"y": 3""")) {
            val result = parse(
                """{ "layouts": { "phone": { "items": [
                     { "id": "clock", "widget": "com.android.deskclock/.DigitalAppWidgetProvider", $lone }
                   ] } } }"""
            )

            val error = result.diagnostics.single()
            assertEquals(lone, Severity.Error, error.severity)
            assertEquals(lone, "partial-grid-position", error.code)
            assertEquals(lone, "home.grid.layouts.phone.items[0]", error.path)
            assertFalse(lone, result.isSuccess)
        }
    }

    /** Control: both coordinates, or neither, is a position or none. */
    @Test
    fun `x and y together, or neither, is no partial position`() {
        for (position in listOf(""""x": 2, "y": 3""", """"w": 2""")) {
            val result = parse(
                """{ "layouts": { "phone": { "items": [
                     { "id": "clock", "widget": "com.android.deskclock/.DigitalAppWidgetProvider", $position }
                   ] } } }"""
            )

            assertTrue(position + " " + result.diagnostics, result.isSuccess)
        }
    }

    @Test
    fun `a layout holds at most 32 items`() {
        val items = (0 until 33).joinToString(",") { """{ "id": "i$it", "widget": "favorites" }""" }

        val found = errors("""{ "layouts": { "phone": { "items": [ $items ] } } }""", "too-many-grid-items")

        assertEquals("home.grid.layouts.phone.items", found.single().path)
        assertEquals(0, errors("""{ "layouts": { "phone": { "items": [ ${items.substringBeforeLast(",")} ] } } }""", "too-many-grid-items").size)
    }

    @Test
    fun `every error carries the item path so a host can point at the line`() {
        val result = parse(
            """{ "layouts": { "fold": { "items": [ { "id": "ok", "widget": "favorites" }, { "id": "BAD", "widget": "x" } ] } } }"""
        )

        val paths = result.diagnostics.filter { it.severity == Severity.Error }.map { it.path }.toSet()
        assertEquals(setOf("home.grid.layouts.fold.items[1]"), paths)
        assertTrue(result.diagnostics.map { it.code }.containsAll(listOf("invalid-grid-item-id", "invalid-grid-widget")))
    }
}

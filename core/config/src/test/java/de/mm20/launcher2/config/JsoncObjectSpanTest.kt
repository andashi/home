package de.mm20.launcher2.config

import de.mm20.launcher2.config.JsoncSpanResult.Found
import de.mm20.launcher2.config.JsoncSpanResult.Insert
import de.mm20.launcher2.config.JsoncSpanResult.Malformed
import de.mm20.launcher2.config.JsoncSpanResult.MissingParent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner behind write-back (ADR 0003, D3): it must find `home.grid`
 * in a hand-written JSONC file and nothing else, so that replacing that
 * span leaves comments and formatting elsewhere byte for byte.
 */
class JsoncObjectSpanTest {

    private val home = listOf("home", "grid")

    private fun spanOf(text: String, path: List<String> = home): String {
        val found = JsoncObjectSpan.find(text, path) as Found
        return text.substring(found.start, found.endExclusive)
    }

    @Test
    fun `finds the value of a nested key`() {
        val text = """
            {
              "schemaVersion": 2,
              "home": {
                "searchBar": { "position": "bottom" },
                "grid": { "columns": 4, "layouts": { "phone": { "items": [] } } },
                "favorites": []
              }
            }
        """.trimIndent()

        assertEquals("""{ "columns": 4, "layouts": { "phone": { "items": [] } } }""", spanOf(text))
    }

    @Test
    fun `braces and quotes inside comments do not count`() {
        val text = """
            {
              // "home": { "grid": {} } is not a member, it is this comment
              /* neither is this: "grid": { } } } */
              "home": {
                "grid": { "columns": 4 } // trailing note with a } brace
              }
            }
        """.trimIndent()

        assertEquals("""{ "columns": 4 }""", spanOf(text))
    }

    @Test
    fun `braces and quotes inside strings do not count`() {
        val text = """
            {
              "icons": { "pack": "weird { \"grid\": } name" },
              "home": { "note": "\"grid\": {}", "grid": { "columns": 4 } }
            }
        """.trimIndent()

        assertEquals("""{ "columns": 4 }""", spanOf(text))
    }

    @Test
    fun `a key with the same name at another depth is not the one`() {
        val text = """
            {
              "grid": { "columns": 99 },
              "home": {
                "searchBar": { "grid": { "columns": 98 } },
                "grid": { "columns": 4 }
              }
            }
        """.trimIndent()

        assertEquals("""{ "columns": 4 }""", spanOf(text))
        assertEquals("""{ "columns": 99 }""", spanOf(text, listOf("grid")))
    }

    @Test
    fun `a trailing comma before the closing brace is fine`() {
        val text = """
            {
              "home": {
                "grid": { "columns": 4, },
              },
            }
        """.trimIndent()

        assertEquals("""{ "columns": 4, }""", spanOf(text))
    }

    @Test
    fun `crlf line endings are walked like lf`() {
        val text = "{\r\n  \"home\": {\r\n    \"grid\": {\r\n      \"columns\": 4\r\n    }\r\n  }\r\n}\r\n"

        assertEquals("{\r\n      \"columns\": 4\r\n    }", spanOf(text))
    }

    @Test
    fun `a missing last key yields the insertion point after the last member`() {
        val text = """
            {
              "home": {
                "searchBar": { "position": "bottom" }
              }
            }
        """.trimIndent()

        val insert = JsoncObjectSpan.find(text, home) as Insert
        assertEquals(text.indexOf("}") + 1, insert.at) // right after the searchBar value
        assertTrue(insert.needsComma)
        assertEquals("    ", insert.indent)
        assertEquals("  ", insert.closingIndent)
    }

    @Test
    fun `a missing last key after a trailing comma needs no comma`() {
        val text = """
            {
              "home": {
                "searchBar": { "position": "bottom" },
              }
            }
        """.trimIndent()

        val insert = JsoncObjectSpan.find(text, home) as Insert
        assertEquals(text.indexOf("},") + 2, insert.at)
        assertEquals(false, insert.needsComma)
    }

    @Test
    fun `an empty parent object inserts right after its brace`() {
        val text = """
            {
              "home": {}
            }
        """.trimIndent()

        val insert = JsoncObjectSpan.find(text, home) as Insert
        assertEquals(text.indexOf("{}") + 1, insert.at)
        assertEquals(false, insert.needsComma)
        assertEquals("    ", insert.indent)
        assertEquals("  ", insert.closingIndent)
    }

    @Test
    fun `a missing parent is reported with its depth`() {
        val text = """{ "schemaVersion": 2 }"""

        assertEquals(MissingParent(0), JsoncObjectSpan.find(text, home))
        assertEquals(MissingParent(1), JsoncObjectSpan.find("""{ "home": { "a": 1 } }""", listOf("home", "x", "y")))
        assertEquals(MissingParent(0), JsoncObjectSpan.find("""{ "home": 5 }""", home))
    }

    @Test
    fun `the root itself can be the insertion target`() {
        val text = """{ "schemaVersion": 2 }"""

        val insert = JsoncObjectSpan.find(text, listOf("home")) as Insert
        assertEquals(text.indexOf("2") + 1, insert.at)
        assertTrue(insert.needsComma)
        assertEquals("", insert.closingIndent)
    }

    @Test
    fun `the first of two keys at the same depth wins`() {
        // JSON leaves duplicate keys undefined; kotlinx keeps the last one
        // when parsing, but write-back has to pick a span to replace and
        // picks the first, so the document does not grow a third copy.
        val text = """{ "home": { "grid": { "columns": 1 }, "grid": { "columns": 2 } } }"""

        assertEquals("""{ "columns": 1 }""", spanOf(text))
    }

    @Test
    fun `malformed input is reported, not thrown`() {
        assertEquals(Malformed, JsoncObjectSpan.find("", home))
        assertEquals(Malformed, JsoncObjectSpan.find("[1, 2]", home))
        assertEquals(Malformed, JsoncObjectSpan.find("""{ "home": { "grid": { """, home))
        assertEquals(Malformed, JsoncObjectSpan.find("""{ "home" { } }""", home))
        assertEquals(Malformed, JsoncObjectSpan.find("""{ "home": { "grid": "unterminated } }""", home))
        assertEquals(Malformed, JsoncObjectSpan.find("""{ "home": { /* open comment """, home))
    }

    @Test
    fun `scalar and array values are spanned too`() {
        val text = """{ "home": { "columns": 4, "list": [ { "a": "]" }, 2 ], "s": "x" } }"""

        assertEquals("4", spanOf(text, listOf("home", "columns")))
        assertEquals("""[ { "a": "]" }, 2 ]""", spanOf(text, listOf("home", "list")))
        assertEquals("\"x\"", spanOf(text, listOf("home", "s")))
    }
}

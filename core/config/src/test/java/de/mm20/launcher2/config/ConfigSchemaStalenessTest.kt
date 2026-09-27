package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the stale-schema failure says. The freshness test fires when a merge
 * or a refactor drops a key from ConfigParser.keyEffects, and its old message
 * - "regenerate it" - was the one instruction that erases that evidence: the
 * file is rewritten to match the loss and the test goes green. So the message
 * leads with the key paths that changed, and says nothing about regenerating
 * when one was removed.
 */
class ConfigSchemaStalenessTest {

    private fun schema(text: String): JsonObject = ConfigParser.json.parseToJsonElement(text).jsonObject

    private val before = """
        {"type": "object", "properties": {
          "apps": {"type": "array", "items": {"type": "object", "properties": {"label": {"type": "string"}, "visibility": {"enum": ["hidden"]}}}},
          "gestures": {"type": "object", "properties": {
            "swipeLeft": {"oneOf": [{"enum": ["none"]}, {"type": "object", "properties": {"packageName": {"type": "string"}}}]}
          }}
        }}
    """.trimIndent()

    @Test
    fun `keyPaths names every property, inside lists and alternatives`() {
        assertEquals(
            setOf("apps", "apps[].label", "apps[].visibility", "gestures", "gestures.swipeLeft", "gestures.swipeLeft.packageName"),
            ConfigSchema.keyPaths(schema(before)),
        )
    }

    @Test
    fun `a removed key path leads the message, and nothing says regenerate`() {
        val lost = before.replace(""", "visibility": {"enum": ["hidden"]}""", "")

        val message = ConfigSchema.staleness(generated = lost, committed = before)

        assertTrue(message, message.startsWith("key paths removed"))
        assertTrue(message, "apps[].visibility" in message)
        assertFalse(message, "updateSchema" in message)
        assertFalse(message, "regenerate it" in message)
    }

    @Test
    fun `an added key path leads the message, and regenerating is offered after it`() {
        val grown = before.replace(""""label": {"type": "string"}""", """"label": {"type": "string"}, "activity": {"type": "string"}""")

        val message = ConfigSchema.staleness(generated = grown, committed = before)

        assertTrue(message, message.startsWith("key paths added: apps[].activity"))
        assertTrue(message, message.indexOf("apps[].activity") < message.indexOf("-PupdateSchema"))
    }

    /** Control: a limit or a description changed, no key path did - regenerating is the whole fix. */
    @Test
    fun `with the same key paths the message is the plain regenerate hint`() {
        val relimited = before.replace(""""label": {"type": "string"}""", """"label": {"type": "string", "maxLength": 8}""")

        val message = ConfigSchema.staleness(generated = relimited, committed = before)

        assertTrue(message, message.startsWith("no key path added or removed"))
        assertTrue(message, "-PupdateSchema" in message)
    }

    @Test
    fun `a missing file says so`() {
        val message = ConfigSchema.staleness(generated = before, committed = null)

        assertTrue(message, "does not exist" in message)
    }
}

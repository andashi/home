package de.mm20.launcher2.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the stale-schema failure says. The freshness test fires when a merge
 * or a refactor drops a key from ConfigParser.keyEffects, and its old message
 * - "regenerate it" - was the one instruction that erases that evidence: the
 * file is rewritten to match the loss and the test goes green. So the message
 * leads with the key paths that changed, and says nothing about regenerating
 * when one was removed - and regenerating itself refuses to write a removal
 * the command line did not name.
 */
class ConfigSchemaStalenessTest {

    private val before = """
        {"type": "object", "properties": {
          "apps": {"type": "array", "items": {"type": "object", "properties": {"label": {"type": "string"}, "visibility": {"enum": ["hidden"]}}}},
          "gestures": {"type": "object", "properties": {
            "swipeLeft": {"oneOf": [{"enum": ["none"]}, {"type": "object", "properties": {"packageName": {"type": "string"}}}]}
          }}
        }}
    """.trimIndent()

    /** [before] with `apps[].visibility` lost. */
    private val lost = before.replace(""", "visibility": {"enum": ["hidden"]}""", "")

    @Test
    fun `keyPaths names every property, inside lists and alternatives`() {
        assertEquals(
            setOf("apps", "apps[].label", "apps[].visibility", "gestures", "gestures.swipeLeft", "gestures.swipeLeft.packageName"),
            ConfigSchema.keyPaths(Json.parseToJsonElement(before).jsonObject),
        )
    }

    @Test
    fun `a removed key path leads the message, and nothing says regenerate`() {
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

    /** The remedy must not be able to erase the evidence: -PupdateSchema alone never writes a removal. */
    @Test
    fun `regenerating refuses to write a removed key path nobody named`() {
        val refusal = ConfigSchema.refusal(generated = lost, committed = before, acknowledged = emptySet())

        assertNotNull(refusal)
        assertTrue(refusal!!, "apps[].visibility" in refusal)
        assertTrue(refusal, "removeSchemaKeys" in refusal)
    }

    @Test
    fun `a removal named exactly is written`() {
        assertNull(ConfigSchema.refusal(generated = lost, committed = before, acknowledged = setOf("apps[].visibility")))
    }

    /** A name that was not removed is refused too: an acknowledgement is of this removal, not a standing permission. */
    @Test
    fun `a removal named differently is refused`() {
        val refusal = ConfigSchema.refusal(generated = lost, committed = before, acknowledged = setOf("apps[].label"))

        assertNotNull(refusal)
        assertTrue(refusal!!, "apps[].visibility" in refusal)
    }

    /**
     * A merge conflict or a hand edit can leave the committed file unreadable.
     * The freshness failure must still say what is wrong rather than throw
     * from the diagnosis (review on #227).
     */
    @Test
    fun `a committed file that is not a JSON object is named, not thrown on`() {
        val conflicted = "<<<<<<< HEAD\n$before\n=======\n$lost\n>>>>>>> other"

        for (committed in listOf(conflicted, "[]")) {
            val message = ConfigSchema.staleness(generated = before, committed = committed)

            assertTrue(message, message.startsWith("docs/configuration/launcher.schema.json cannot be read as a schema"))
        }
    }

    /** Which key paths it held cannot be read, so a removal cannot be ruled out: not written. */
    @Test
    fun `regenerating refuses to write over a committed file it cannot read`() {
        val refusal = ConfigSchema.refusal(generated = before, committed = "[]", acknowledged = emptySet())

        assertNotNull(refusal)
        assertTrue(refusal!!, "git checkout" in refusal)
    }

    /**
     * Valid JSON with a property tree of the wrong shape lists no key paths
     * where it has some, so a removal would read as none (review on #227).
     */
    @Test
    fun `a committed property tree of the wrong shape is unreadable, however deep`() {
        val flattened = before.replace(""""properties": {"label": {"type": "string"}, "visibility": {"enum": ["hidden"]}}""", """"properties": []""")
        val notAList = before.replace(""""oneOf": [{"enum": ["none"]}, {"type": "object", "properties": {"packageName": {"type": "string"}}}]""", """"oneOf": {}""")

        for (committed in listOf(flattened, notAList)) {
            assertTrue(committed, committed != before)
            assertTrue(ConfigSchema.staleness(generated = before, committed = committed).startsWith("docs/configuration/launcher.schema.json cannot be read as a schema"))
            assertNotNull(ConfigSchema.refusal(generated = lost, committed = committed, acknowledged = emptySet()))
        }
    }

    /** An acknowledgement names exactly the removed paths, none when none were removed (review on #227). */
    @Test
    fun `a name given when nothing was removed is refused`() {
        val grown = before.replace(""""label": {"type": "string"}""", """"label": {"type": "string"}, "activity": {"type": "string"}""")

        assertNotNull(ConfigSchema.refusal(generated = grown, committed = before, acknowledged = setOf("apps[].label")))
        assertNotNull(ConfigSchema.refusal(generated = before, committed = null, acknowledged = setOf("apps[].label")))
    }

    /** Controls: nothing removed, nothing to acknowledge. */
    @Test
    fun `an addition, a new limit or a first file is written without naming anything`() {
        val grown = before.replace(""""label": {"type": "string"}""", """"label": {"type": "string"}, "activity": {"type": "string"}""")
        val relimited = before.replace(""""label": {"type": "string"}""", """"label": {"type": "string", "maxLength": 8}""")

        assertNull(ConfigSchema.refusal(generated = grown, committed = before, acknowledged = emptySet()))
        assertNull(ConfigSchema.refusal(generated = relimited, committed = before, acknowledged = emptySet()))
        assertNull(ConfigSchema.refusal(generated = before, committed = null, acknowledged = emptySet()))
    }
}

package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `docs/configuration/complete-example.json` is the one document that sets
 * every key the launcher applies (#3 slice 3). The round trip in
 * `:services:config` runs it through parse, migrate, diff, apply and
 * read-back; the JSON Schema will be checked against it too. This test keeps
 * it complete: a key added to [ConfigParser.keyEffects] as applied fails here,
 * by name, until the example sets it.
 */
class CompleteExampleTest {

    private val text: String =
        File(System.getProperty("repoRoot"), "docs/configuration/complete-example.json").readText()

    @Test
    fun `the complete example parses with nothing to report`() {
        val result = ConfigParser.parse(text)

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
    }

    @Test
    fun `the complete example sets every applied key`() {
        val present = keyPaths(ConfigParser.json.parseToJsonElement(text))
        val applied = ConfigParser.keyEffects.flatMap { (section, keys) ->
            keys.filterValues { it == KeyEffect.Applied }.keys.map { section to it }
        }

        val missing = applied.filter { it !in present }.map { (section, key) -> if (section.isEmpty()) key else "$section.$key" }

        assertEquals(
            "applied keys the complete example does not set: $missing",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * A key set to its default proves nothing in the round trip: dropped on
     * the way, it would come back as the default anyway. So every value the
     * example sets differs from what the read-back serves with nothing
     * configured; a value that does not fails by path.
     */
    @Test
    fun `every value in the complete example is off its default`() {
        val defaults = leaves(ConfigParser.json.parseToJsonElement(
            ConfigParser.json.encodeToString(LauncherConfig.serializer(), ConfigState().toLauncherConfig()),
        ))
        val example = leaves(ConfigParser.json.parseToJsonElement(text)) - "schemaVersion"

        val atDefault = example.filter { (path, value) -> defaults[path] == value }.keys

        assertEquals("values in the complete example that equal their default: $atDefault", emptySet<String>(), atDefault)
    }

    /** Every scalar in [element] by its path, array elements by index. */
    private fun leaves(element: JsonElement, path: String = ""): Map<String, JsonElement> = when (element) {
        is JsonObject -> element.flatMap { (key, value) -> leaves(value, if (path.isEmpty()) key else "$path.$key").toList() }.toMap()
        is JsonArray -> element.withIndex().flatMap { (i, value) -> leaves(value, "$path[$i]").toList() }.toMap()
        else -> mapOf(path to element)
    }

    /** Every (section, key) in [element], sections named as in [ConfigParser.keyEffects]. */
    private fun keyPaths(element: JsonElement, section: String = ""): Set<Pair<String, String>> {
        val obj = element as? JsonObject ?: return emptySet()
        return obj.flatMap { (key, value) ->
            val child = if (section.isEmpty()) key else "$section.$key"
            val nested = when (value) {
                is JsonObject -> keyPaths(value, child)
                is JsonArray -> value.flatMap { keyPaths(it, "$child[]") }
                else -> emptyList()
            }
            listOf(section to key) + nested
        }.toSet()
    }
}

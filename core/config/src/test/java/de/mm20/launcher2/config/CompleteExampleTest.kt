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

    private val text: String = repoFile("docs/configuration/complete-example.json").readText()

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

    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$path not found above ${File("").absolutePath}")
    }
}

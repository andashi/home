package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The configuration docs (docs/configuration, #88) cannot drift from the
 * contract: every example marked `<!-- config -->` is parsed and must produce
 * no diagnostic, and every key of the contract must be named somewhere in the
 * docs by its full path. A new key cannot ship undocumented.
 *
 * The docs directory is declared as an input of this test task in
 * build.gradle.kts, or a change to the docs alone would not re-run it.
 */
class ConfigurationDocsTest {

    private val docsDir: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/configuration")
            if (candidate.isDirectory) return@run candidate
            dir = dir.parentFile
        }
        throw AssertionError("docs/configuration not found above ${File("").absolutePath}")
    }

    private val pages: Map<String, String> =
        docsDir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }.associate { it.name to it.readText() }

    @Test
    fun `every example in the docs parses without a diagnostic`() {
        val all = pages.flatMap { (name, text) -> configExamples(text).map { name to it } }
        assertTrue("the docs carry examples", all.size >= 10)
        for ((name, example) in all) {
            val result = ConfigParser.parse(example)
            assertEquals("$name:\n$example", emptyList<Diagnostic>(), result.diagnostics)
            assertTrue("$name:\n$example", result.isSuccess)
        }
    }

    /**
     * The name a key is documented under: its full dotted path, with the two
     * layouts' item keys written once as `home.grid.layouts.<layout>.items[].x`.
     */
    private fun documentedName(section: String, key: String): String {
        val path = if (section.isEmpty()) key else "$section.$key"
        return path
            .replace("home.grid.layouts.phone.items[]", "home.grid.layouts.<layout>.items[]")
            .replace("home.grid.layouts.fold.items[]", "home.grid.layouts.<layout>.items[]")
            .replace(Regex("^home\\.grid\\.layouts\\.(phone|fold)\\.items$"), "home.grid.layouts.<layout>.items")
    }

    @Test
    fun `every key of the contract is documented by its full path`() {
        val text = pages.values.joinToString("\n")
        val names = ConfigParser.keyEffects.flatMap { (section, keys) ->
            keys.keys.map { documentedName(section, it) }
        }.toSortedSet()
        val missing = names.filter { "`$it`" !in text }
        assertEquals("keys no page names (in backticks)", emptyList<String>(), missing)
    }
}

/** Every fenced `json` block that directly follows a `<!-- config -->` line. */
internal fun configExamples(markdown: String): List<String> {
    val lines = markdown.lines()
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() == "<!-- config -->") {
            // The fence must follow its marker directly (blank lines
            // allowed): a detached marker must not borrow a later block.
            val open = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
                ?: throw AssertionError("a <!-- config --> marker at the end of a page")
            val opener = lines[open].trim()
            val fence = opener.takeWhile { it == '`' }
            assertTrue("a <!-- config --> marker not followed by a fenced block: '${lines[open]}'", fence.length >= 3)
            assertEquals("examples are tagged json", "json", opener.removePrefix(fence).trim())
            // CommonMark: a closing fence is a run of at least as many backticks, nothing else.
            val close = (open + 1 until lines.size).firstOrNull {
                val t = lines[it].trim()
                t.length >= fence.length && t.all { c -> c == '`' }
            } ?: throw AssertionError("an example that is never closed")
            out += lines.subList(open + 1, close).joinToString("\n")
            i = close
        }
        i++
    }
    return out
}

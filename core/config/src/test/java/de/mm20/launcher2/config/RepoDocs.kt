package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The repo's documents the config tests read. ADR 0002 and docs/configuration
 * are declared as inputs of the test task in build.gradle.kts, or a change to
 * a document alone would leave the task UP-TO-DATE and nothing would check it
 * (AGENTS.md, "Test policy").
 */
internal object RepoDocs {
    val root = File(System.getProperty("repoRoot") ?: error("repoRoot is not set (core/config/build.gradle.kts)"))

    val adr0002: String get() = read("docs/architecture/adr/0002-config-format-json.md")

    /** Every key the contract has, each off its default (CompleteExampleTest). */
    val completeExample: String get() = read("docs/configuration/complete-example.json")

    val schemaFile = File(root, "docs/configuration/launcher.schema.json")

    /** The pages of docs/configuration by file name, in name order. */
    val configurationPages: Map<String, String>
        get() = File(root, "docs/configuration").listFiles { f -> f.extension == "md" }!!
            .sortedBy { it.name }
            .associate { it.name to it.readText() }

    private fun read(path: String): String =
        File(root, path).also { assertTrue("$path is missing", it.isFile) }.readText()
}

/** The body of the first fenced `json` block after the [marker] line. */
internal fun fencedJsonAfter(markdown: String, marker: String): String {
    val lines = markdown.lines()
    val markerAt = lines.indexOfFirst { it.trim() == marker }
    assertTrue("$marker is missing from the ADR", markerAt >= 0)
    val openAt = (markerAt + 1..lines.lastIndex).firstOrNull { lines[it].trimStart().startsWith("```") }
    assertNotNull("no fenced block follows $marker", openAt)
    return fencedJson(lines, openAt!!, "the block after $marker").first
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
            val openAt = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
                ?: throw AssertionError("a <!-- config --> marker at the end of a page")
            val (body, closeAt) = fencedJson(lines, openAt, "the block after a <!-- config --> marker")
            out += body
            i = closeAt
        }
        i++
    }
    return out
}

/** The fenced `json` block opening at [openAt]: its body, and the line of its closing fence. */
private fun fencedJson(lines: List<String>, openAt: Int, what: String): Pair<String, Int> {
    val opener = lines[openAt].trim()
    val fence = opener.takeWhile { it == '`' }
    assertTrue("$what is not a fenced block: '${lines[openAt]}'", fence.length >= 3)
    assertEquals("$what must be tagged json", "json", opener.removePrefix(fence).trim())
    // CommonMark: a closing fence is a run of at least as many backticks, nothing else.
    val closeAt = (openAt + 1..lines.lastIndex).firstOrNull {
        val line = lines[it].trim()
        line.length >= fence.length && line.all { char -> char == '`' }
    } ?: throw AssertionError("$what is never closed")
    return lines.subList(openAt + 1, closeAt).joinToString("\n") to closeAt
}

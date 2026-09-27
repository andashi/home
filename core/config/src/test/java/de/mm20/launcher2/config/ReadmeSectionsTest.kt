package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The readme's "What is configurable today" table names every top-level
 * section of the contract. ConfigurationDocsTest holds docs/configuration to
 * every key by its full path; the readme is outside what it reads, and the
 * table drifted: `apps` and `gestures` were in the contract and missing here.
 *
 * Only sections are checked. The table describes keys in prose ("themed
 * icons, enforce themed, icon pack"), which cannot be held to paths; a
 * missing section is the failure that happened.
 *
 * readme.md is declared as an input of this test task in build.gradle.kts, or
 * a change to the readme alone would leave the task UP-TO-DATE and nothing
 * would check it (AGENTS.md, "Ways a test runs and tests nothing", 1).
 */
class ReadmeSectionsTest {

    private val readme: String =
        File(RepoDocs.root, "readme.md").also { assertTrue("readme.md is missing", it.isFile) }.readText()

    /**
     * The top-level keys that are sections: those with a table of their own
     * in [ConfigParser.keyEffects] (`apps[]` for a list, `gestures` for an
     * object). `schemaVersion` is a value, not a section, and falls out here
     * rather than by name.
     */
    private val sections: List<String> =
        ConfigParser.keyEffects.getValue("").keys.filter { key ->
            key in ConfigParser.keyEffects || "$key[]" in ConfigParser.keyEffects
        }.sorted()

    /** The backticked names in the first column of the table under [heading]. */
    private fun sectionCells(heading: String): List<String> {
        val lines = readme.lines()
        val at = lines.indexOfFirst { it.trim() == heading }
        assertTrue("'$heading' is missing from readme.md", at >= 0)
        val rows = lines.drop(at + 1)
            .dropWhile { !it.trimStart().startsWith("|") }
            .takeWhile { it.trimStart().startsWith("|") }
        assertTrue("no table under '$heading' in readme.md", rows.size >= 3)
        assertEquals("the table's header", "Section", rows[0].split("|")[1].trim())
        return rows.drop(2).flatMap { row ->
            Regex("`([^`]+)`").findAll(row.split("|")[1]).map { it.groupValues[1] }.toList()
        }
    }

    @Test
    fun `the contract has the sections this test is about`() {
        // A control: if keyEffects stopped listing sections at the top level,
        // the test below would pass on an empty list.
        assertTrue(sections.toString(), sections.containsAll(listOf("icons", "appearance", "home", "search")))
        assertTrue("schemaVersion is a value, not a section: $sections", "schemaVersion" !in sections)
    }

    @Test
    fun `the readme's table names every top-level section of the contract`() {
        val named = sectionCells("## What is configurable today")
        // A section is named by itself or by a subsection: `appearance` by
        // `appearance.glass`, `home` by `home.searchBar`.
        val missing = sections.filter { section ->
            named.none { it == section || it == "$section[]" || it.startsWith("$section.") }
        }
        assertEquals(
            "top-level sections the readme's 'What is configurable today' table does not name",
            emptyList<String>(),
            missing,
        )
    }
}

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
 * Only the table's first column is checked, both ways: every section is
 * named, and every name is in the contract. The second column describes keys
 * in prose ("themed icons, enforce themed, icon pack"), which cannot be held
 * to paths; a missing section is the failure that happened.
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
     * object) and an effect the launcher applies. `schemaVersion` is a value,
     * not a section, and falls out here rather than by name.
     */
    private val sections: List<String> =
        ConfigParser.keyEffects.getValue("").filter { (key, effect) ->
            effect == KeyEffect.Applied && (key in ConfigParser.keyEffects || "$key[]" in ConfigParser.keyEffects)
        }.keys.sorted()

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
        // A control on the derivation: without it, a section that stopped
        // being derived would silently stop being checked. `apps` is the one
        // section found through its list table (`apps[]`), `gestures` one
        // found through its own; the rest are found both ways. A subset, so
        // a new section does not break it.
        assertTrue(
            sections.toString(),
            sections.containsAll(listOf("icons", "appearance", "home", "search", "apps", "gestures")),
        )
        assertTrue("schemaVersion is a value, not a section: $sections", "schemaVersion" !in sections)
    }

    /**
     * Whether [name] is a section or a key the launcher acts on: a table of
     * its own (`home.grid`, `apps[]`), or an applied key of its parent's table
     * (`home.lockRotation` in `home`). An inert key is accepted but does
     * nothing - `appearance.transparency` since #73 - so a row for it is
     * stale although the parser still knows the name.
     */
    private fun inContract(name: String): Boolean {
        val tables = ConfigParser.keyEffects
        if (name in tables || "$name[]" in tables) return true
        val parent = name.substringBeforeLast('.', missingDelimiterValue = "")
        val key = name.substringAfterLast('.')
        return tables[parent]?.get(key) == KeyEffect.Applied
    }

    @Test
    fun `every name in the readme's table is in the contract`() {
        // The other direction: a row for a section or key the contract no
        // longer has, say `appearance.transparency` after #73, would tell a
        // reader about a key that does nothing.
        val named = sectionCells("## What is configurable today")
        val unknown = named.filterNot { inContract(it) }
        assertEquals("names in the readme's table that are not in the contract", emptyList<String>(), unknown)
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

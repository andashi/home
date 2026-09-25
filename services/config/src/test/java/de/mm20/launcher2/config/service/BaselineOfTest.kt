package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigDiffer
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ConfigState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #3 slice 4 (D): the baseline is what the file produced, and nothing a
 * person changed on the device while it was being applied.
 */
class BaselineOfTest {

    private fun tree(json: String): JsonObject = ConfigParser.json.parseToJsonElement(json).jsonObject

    @Test
    fun `a section the reload changed comes from after the apply, the others from before it`() {
        val before = tree("""{"icons":{"themed":false},"search":{"layout":"grid"}}""")
        // During the apply of "search", a person switched themed icons on.
        val after = tree("""{"icons":{"themed":true},"search":{"layout":"list"}}""")

        assertEquals(
            tree("""{"icons":{"themed":false},"search":{"layout":"list"}}"""),
            baselineOf(before, after, applied = listOf("search")),
        )
    }

    @Test
    fun `a section inside a changed one that the reload did not change comes from before`() {
        val before = tree("""{"search":{"layout":"grid","actions":[{"type":"url","label":"A"}]}}""")
        val after = tree("""{"search":{"layout":"list","actions":[{"type":"url","label":"B"}]}}""")

        assertEquals(
            tree("""{"search":{"layout":"list","actions":[{"type":"url","label":"A"}]}}"""),
            baselineOf(before, after, applied = listOf("search")),
        )
    }

    /** The complete example touches every section, so every section it produces must be known here. */
    @Test
    fun `every section a reload can apply is one the baseline knows`() {
        val example = ConfigParser.parse(File(System.getProperty("repoRoot"), "docs/configuration/complete-example.json").readText()).config!!

        val sections = ConfigDiffer.diff(example, ConfigState()).map { it.section }.toSet()

        assertTrue("sections: $sections", sections.isNotEmpty())
        assertEquals(emptySet<String>(), sections - ConfigSections.toSet())
    }
}

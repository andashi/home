package de.mm20.launcher2.config

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The checked-in JSON Schema (docs/configuration/launcher.schema.json) is the
 * one the parser implies (#3 slice 3, ADR 0002).
 *
 * Comparing the file with its generator proves only that the file is fresh,
 * so the schema is also run - by a real JSON Schema implementation, test scope
 * only - against the documents the parser accepts and the ones it rejects:
 * they must agree.
 */
class ConfigSchemaTest {

    private val root = File(System.getProperty("repoRoot"))
    private val schemaFile = File(root, "docs/configuration/launcher.schema.json")

    /**
     * Fresh: regenerated in memory, it must equal the checked-in file byte for
     * byte. `-PupdateSchema` rewrites the file instead, for the one commit
     * that changes the contract.
     */
    @Test
    fun `the checked-in schema is the generated one`() {
        val generated = ConfigSchema.text()
        if (System.getProperty("updateSchema") == "true") schemaFile.writeText(generated)

        assertEquals(
            "docs/configuration/launcher.schema.json is stale; regenerate it with " +
                "./gradlew :core:config:testDebugUnitTest --tests '*ConfigSchemaTest*' -PupdateSchema",
            generated,
            schemaFile.takeIf { it.exists() }?.readText(),
        )
    }

    private val schema: Schema by lazy {
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schemaFile.readText(), InputFormat.JSON)
    }

    /** The document as plain JSON: examples may carry comments and trailing commas (JSONC). */
    private fun json(jsonc: String): String = ConfigParser.json.parseToJsonElement(jsonc).toString()

    private fun schemaErrors(jsonc: String): List<String> =
        schema.validate(json(jsonc), InputFormat.JSON).map { "${it.instanceLocation}: ${it.message}" }

    @Test
    fun `every documented example is valid against the schema`() {
        val examples = buildList {
            add("ADR 0002" to fencedJsonAfter(File(root, "docs/architecture/adr/0002-config-format-json.md").readText(), "<!-- adr-0002-example -->"))
            File(root, "docs/configuration").listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }.forEach { page ->
                configExamples(page.readText()).forEachIndexed { i, example -> add("${page.name} #${i + 1}" to example) }
            }
        }
        assertTrue("no examples found", examples.size > 1)

        val invalid = examples.mapNotNull { (where, text) -> schemaErrors(text).takeIf { it.isNotEmpty() }?.let { "$where: $it" } }

        assertEquals("documented examples the schema rejects", emptyList<String>(), invalid)
    }

    /**
     * Each document breaks one limit. The parser must reject it (an error) and
     * so must the schema: if only one does, the two disagree about the
     * contract, which is exactly what a generated schema would never notice
     * by itself.
     */
    @Test
    fun `the schema rejects what the parser rejects`() {
        val broken = mapOf(
            "glass blur above its maximum" to """{"schemaVersion":2,"appearance":{"glass":{"blur":${ConfigValidator.MaxGlassBlur + 1}}}}""",
            "glass tint above its maximum" to """{"schemaVersion":2,"appearance":{"glass":{"tint":1.5}}}""",
            "an unknown glass contrast" to """{"schemaVersion":2,"appearance":{"glass":{"contrast":"extreme"}}}""",
            "a package name without a dot" to """{"schemaVersion":2,"icons":{"pack":"nodot"}}""",
            "a wallpaper upload name with a path" to """{"schemaVersion":2,"appearance":{"wallpaper":{"image":"../x.jpg"}}}""",
            "too many grid columns" to """{"schemaVersion":2,"home":{"grid":{"columns":${ConfigValidator.MaxGridColumns + 1}}}}""",
            "a grid item id with capitals" to """{"schemaVersion":2,"home":{"grid":{"layouts":{"phone":{"items":[{"id":"Clock","widget":"favorites"}]}}}}}""",
            "a grid widget that is no component" to """{"schemaVersion":2,"home":{"grid":{"layouts":{"phone":{"items":[{"id":"x","widget":"clock"}]}}}}}""",
            "a grid width of zero" to """{"schemaVersion":2,"home":{"grid":{"layouts":{"phone":{"items":[{"id":"x","widget":"favorites","x":0,"y":0,"w":0,"h":1}]}}}}}""",
            "a url action without its query" to """{"schemaVersion":2,"search":{"actions":[{"type":"url","label":"L","url":"https://example.org/"}]}}""",
            "an app action without its package" to """{"schemaVersion":2,"search":{"actions":[{"type":"app","label":"L"}]}}""",
            "an unknown barPosition" to """{"schemaVersion":2,"search":{"barPosition":"middle"}}""",
        )

        val disagreements = broken.mapNotNull { (what, text) ->
            val parserRejects = ConfigParser.parse(text).diagnostics.any { it.severity == Severity.Error }
            val schemaRejects = schemaErrors(text).isNotEmpty()
            if (parserRejects && schemaRejects) null else "$what: parser rejects=$parserRejects, schema rejects=$schemaRejects"
        }

        assertEquals("the schema and the parser disagree", emptyList<String>(), disagreements)
    }

    /**
     * Strict mode: an unknown key is invalid in the schema (the parser only
     * warns, on purpose) and the schema's message names it, so a typo is
     * reported as the typo.
     */
    @Test
    fun `a misspelt key is named by the schema and warned about by the parser`() {
        val text = """{"schemaVersion":2,"home":{"grid":{"labls":true}}}"""

        val errors = schemaErrors(text)
        val parser = ConfigParser.parse(text).diagnostics

        assertTrue("schema errors name the key: $errors", errors.any { "labls" in it })
        assertTrue("the parser warns: $parser", parser.any { it.code == "unknown-key" && it.severity == Severity.Warning })
    }
}

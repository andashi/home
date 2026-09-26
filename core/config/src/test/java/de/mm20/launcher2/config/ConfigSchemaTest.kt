package de.mm20.launcher2.config

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private val schemaFile = RepoDocs.schemaFile

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

    /**
     * Every enum's wire values as the published schema lists them. They are
     * derived from the enum names in kebab case (FieldEnumSerializer); this
     * pins them, so renaming an entry, or adding a multi-word one, is a
     * visible change of the contract rather than a side effect (#3 slice 3).
     */
    @Test
    fun `the enum values the schema publishes are exactly these`() {
        val root = ConfigParser.json.parseToJsonElement(ConfigSchema.text()).jsonObject
        fun enumAt(path: String): List<String> =
            path.split('.').fold(root) { node, key -> node["properties"]!!.jsonObject[key]!!.jsonObject }
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("low", "medium", "high"), enumAt("appearance.glass.contrast"))
        assertEquals(listOf("top", "bottom", "follow"), enumAt("search.barPosition"))
        assertEquals(listOf("grid", "list"), enumAt("search.layout"))
        assertEquals(listOf("light", "dark", "system"), enumAt("appearance.theme.mode"))
        assertEquals(listOf("system", "black-and-white", "high-contrast"), enumAt("appearance.theme.colors"))
    }

    /**
     * The schema the agreement tests run: the generated one, not the checked-in
     * file. The freshness test holds the two equal, and with `-PupdateSchema`
     * the file is rewritten by whichever test runs first - the agreement tests
     * must not depend on that order (review on #150).
     */
    private val schema: Schema by lazy {
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(ConfigSchema.text(), InputFormat.JSON)
    }

    /** The document as plain JSON: examples may carry comments and trailing commas (JSONC). */
    private fun json(jsonc: String): String = ConfigParser.json.parseToJsonElement(jsonc).toString()

    private fun schemaErrors(jsonc: String): List<String> =
        schema.validate(json(jsonc), InputFormat.JSON).map { "${it.instanceLocation}: ${it.message}" }

    @Test
    fun `every documented example is valid against the schema`() {
        val examples = buildList {
            add("ADR 0002" to fencedJsonAfter(RepoDocs.adr0002, "<!-- adr-0002-example -->"))
            add("complete-example.json" to RepoDocs.completeExample)
            RepoDocs.configurationPages.forEach { (name, page) ->
                configExamples(page).forEachIndexed { i, example -> add("$name #${i + 1}" to example) }
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
            // Review on #150: present is not enough, the validator wants them non-blank.
            "a url action with a blank label" to """{"schemaVersion":2,"search":{"actions":[{"type":"url","label":"","url":"https://example.org/?q=${'$'}{1}"}]}}""",
            "an app action with a whitespace label" to """{"schemaVersion":2,"search":{"actions":[{"type":"app","label":"  ","package":"org.example.app"}]}}""",
            "a grid widget whose package is too long" to """{"schemaVersion":2,"home":{"grid":{"layouts":{"phone":{"items":[{"id":"x","widget":"a.${"b".repeat(ConfigValidator.MaxPackageNameLength)}/.C"}]}}}}}""",
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
     * The other direction, generated rather than listed: every limit the
     * schema adds ([ConfigSchema.constraints]) is broken once in the complete
     * example, which is valid as it is, and the parser must report an error.
     * A limit the schema claims but the validator does not enforce fails here
     * by its path.
     */
    @Test
    fun `every limit the schema adds is one the parser enforces`() {
        val base = ConfigParser.json.parseToJsonElement(RepoDocs.completeExample)
        assertEquals("the complete example is clean", emptyList<Diagnostic>(), ConfigParser.parse(base.toString()).diagnostics)

        val unenforced = ConfigSchema.constraints.flatMap { (path, limits) ->
            limits.mapNotNull { (keyword, bound) ->
                val broken = base.changed(path.split('.')) { breaking(keyword, bound, it) }.toString()
                val parserRejects = ConfigParser.parse(broken).diagnostics.any { it.severity == Severity.Error }
                val schemaRejects = schemaErrors(broken).isNotEmpty()
                if (parserRejects && schemaRejects) null else "$path $keyword: parser rejects=$parserRejects, schema rejects=$schemaRejects"
            }
        }

        assertEquals("schema limits the parser does not enforce", emptyList<String>(), unenforced)
    }

    /**
     * The direction the two guards above cannot see: a limit the parser
     * enforces that the schema never had. Both of them walk the schema's own
     * limits, so a missing one leaves nothing to break (review on #150: blank
     * labels, the package part of a component name). Here every string and
     * number in the complete example is broken the generic ways a limit is
     * broken - blank, whitespace only, stretched past any length limit,
     * negative, huge - and wherever the parser rejects the result, the schema
     * must reject it too.
     *
     * What it cannot find: a limit only a value of another shape breaks, or
     * one across fields. Those stay with the hand-written list in
     * [`the schema rejects what the parser rejects`]; ADR 0002 says so.
     */
    @Test
    fun `every generic break of the complete example the parser rejects, the schema rejects too`() {
        val base = ConfigParser.json.parseToJsonElement(RepoDocs.completeExample)

        val missed = leaves(base, emptyList()).flatMap { (path, value) ->
            breaksOf(value).mapNotNull { broken ->
                val doc = base.replacedAt(path, broken).toString()
                val parserRejects = ConfigParser.parse(doc).diagnostics.any { it.severity == Severity.Error }
                if (parserRejects && schemaErrors(doc).isEmpty()) "${path.joinToString(".")} = ${broken.toString().take(40)}" else null
            }
        }

        assertEquals("the parser rejects these and the schema lets them through", emptyList<String>(), missed)
    }

    /** Every string and number in [element], by its path of keys and list indices. */
    private fun leaves(element: JsonElement, at: List<Any>): List<Pair<List<Any>, JsonPrimitive>> = when (element) {
        is JsonObject -> element.flatMap { (key, value) -> leaves(value, at + key) }
        is JsonArray -> element.flatMapIndexed { i, value -> leaves(value, at + i) }
        is JsonPrimitive -> if (element is kotlinx.serialization.json.JsonNull || element.booleanOrNull != null) emptyList() else listOf(at to element)
    }

    private fun breaksOf(value: JsonPrimitive): List<JsonPrimitive> =
        if (value.isString) {
            val s = value.content
            listOf(JsonPrimitive(""), JsonPrimitive("  "), JsonPrimitive(s.take(1) + "a".repeat(300) + s.drop(1)))
        } else {
            listOf(JsonPrimitive(-1), JsonPrimitive(1_000_000))
        }

    private fun JsonElement.replacedAt(path: List<Any>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return when (val step = path.first()) {
            is String -> JsonObject(jsonObject + (step to jsonObject.getValue(step).replacedAt(path.drop(1), value)))
            else -> JsonArray(jsonArray.toMutableList().also { it[step as Int] = it[step].replacedAt(path.drop(1), value) })
        }
    }

    /** A value just past [keyword]'s [bound], in place of [current]. */
    private fun breaking(keyword: String, bound: JsonElement, current: JsonElement): JsonElement {
        val limit = bound.jsonPrimitive
        fun past(step: Int) = if ('.' in limit.content) JsonPrimitive(limit.double + step) else JsonPrimitive(limit.long + step)
        return when (keyword) {
            "minimum" -> past(-1)
            "maximum" -> past(+1)
            "pattern" -> JsonPrimitive("!")
            "maxLength" -> JsonPrimitive("a." + "a".repeat(limit.int)) // a package name in form, one too long
            "maxItems" -> JsonArray(List(limit.int + 1) { current.jsonArray.first() })
            // Inside the range, between the steps.
            "multipleOf" -> JsonPrimitive(current.jsonPrimitive.long + 1)
            else -> error("no way to break $keyword")
        }
    }

    /**
     * This element with the value at [segments] replaced. `*` is any key of a
     * map (the first), `name[]` the first element of a list that has the rest.
     */
    private fun JsonElement.changed(segments: List<String>, change: (JsonElement) -> JsonElement): JsonElement {
        if (segments.isEmpty()) return change(this)
        val head = segments.first()
        val rest = segments.drop(1)
        val obj = jsonObject
        val key = head.removeSuffix("[]").let { if (it == "*") obj.keys.first() else it }
        val child = obj[key] ?: throw AssertionError("the complete example has no '$key' for ${segments.joinToString(".")}")
        val newChild = if (!head.endsWith("[]")) child.changed(rest, change) else {
            val list = child.jsonArray
            val at = list.indexOfFirst { rest.isEmpty() || (it is JsonObject && rest.first().removeSuffix("[]") in it) }
            if (at < 0) throw AssertionError("no element of '$key' has ${rest.first()}")
            JsonArray(list.toMutableList().also { it[at] = it[at].changed(rest, change) })
        }
        return JsonObject(obj + (key to newChild))
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

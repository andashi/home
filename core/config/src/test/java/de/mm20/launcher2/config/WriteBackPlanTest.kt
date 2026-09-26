package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #3 slice 4: which members of `launcher.json` a write-back rewrites, and
 * how. Only keys the file already has (W1); a key the file leaves out is
 * unmanaged and stays out.
 */
class WriteBackPlanTest {

    private fun tree(json: String): JsonObject = ConfigParser.json.parseToJsonElement(json).jsonObject

    /** The file's own values as its effective state: the literal-comparison case. */
    private fun changes(file: String, device: String) =
        WriteBackPlan.changes(literal = tree(file), fileEffective = tree(file), device = tree(device))

    /**
     * A value the device has but cannot express, such as a colour scheme a
     * person made, reads back as nothing: the file keeps what it wrote, and
     * the plan names the path it kept, so a caller can say why instead of
     * working it out again (#183 simplify).
     */
    @Test
    fun `a value the device cannot express is kept as written, and the plan names its path`() {
        val file = """{"schemaVersion":2,"appearance":{"theme":{"mode":"system","colors":"system"}}}"""
        val device = """{"schemaVersion":2,"appearance":{"theme":{"mode":"system"}}}"""

        val plan = WriteBackPlan.plan(literal = tree(file), fileEffective = tree(file), device = tree(device))

        assertEquals(emptyList<WriteBackPlan.Change>(), plan.changes)
        assertEquals(listOf(listOf("appearance", "theme", "colors")), plan.kept)
    }

    @Test
    fun `a key the file leaves out is not kept, it is unmanaged`() {
        val file = """{"schemaVersion":2,"appearance":{"theme":{"mode":"system"}}}"""
        val device = """{"schemaVersion":2,"appearance":{"theme":{"mode":"dark"}}}"""

        val plan = WriteBackPlan.plan(literal = tree(file), fileEffective = tree(file), device = tree(device))

        assertEquals(emptyList<List<String>>(), plan.kept)
    }

    @Test
    fun `a device that matches the file changes nothing`() {
        val file = """{"schemaVersion":2,"home":{"grid":{"columns":4}}}"""

        assertEquals(emptyList<WriteBackPlan.Change>(), changes(file, file))
    }

    @Test
    fun `a leaf the file has and the device changed is rewritten at its path`() {
        val result = changes(
            """{"schemaVersion":2,"home":{"grid":{"columns":4,"labels":true}}}""",
            """{"schemaVersion":2,"home":{"grid":{"columns":5,"labels":true}}}""",
        )

        assertEquals(listOf(WriteBackPlan.Change(listOf("home", "grid", "columns"), JsonPrimitive(5))), result)
    }

    @Test
    fun `a key the file leaves out stays out, whatever the device has`() {
        val result = changes(
            """{"schemaVersion":2,"home":{"grid":{"columns":4}}}""",
            """{"schemaVersion":2,"home":{"grid":{"columns":4,"labels":false}},"search":{"layout":"list"}}""",
        )

        assertEquals(emptyList<WriteBackPlan.Change>(), result)
    }

    /** An inert key, or one of a newer build: the model has nothing to say about it. */
    @Test
    fun `a key the device does not know is left as written`() {
        val result = changes(
            """{"schemaVersion":2,"home":{"grid":{"columns":4,"futureKey":1}}}""",
            """{"schemaVersion":2,"home":{"grid":{"columns":4}}}""",
        )

        assertEquals(emptyList<WriteBackPlan.Change>(), result)
    }

    @Test
    fun `a list is rewritten whole when any element differs`() {
        val result = changes(
            """{"schemaVersion":2,"home":{"favorites":["org.a.one","org.a.two"]}}""",
            """{"schemaVersion":2,"home":{"favorites":["org.a.two","org.a.one"]}}""",
        )

        assertEquals(listOf(listOf("home", "favorites")), result.map { it.path })
    }

    @Test
    fun `the same number written differently is not a change`() {
        val result = changes(
            """{"schemaVersion":2,"appearance":{"glass":{"blur":12}}}""",
            """{"schemaVersion":2,"appearance":{"glass":{"blur":12.0}}}""",
        )

        assertEquals(emptyList<WriteBackPlan.Change>(), result)
    }

    /**
     * #140: a value the device cannot apply as written (here: clamped) shows
     * up as a difference against the literal file. Compared with what the
     * file produces once applied, it is none, so the file keeps its value.
     */
    @Test
    fun `a value the device clamped is not written back when compared with the file's effect`() {
        val literal = tree("""{"schemaVersion":2,"home":{"grid":{"columns":9}}}""")
        val applied = tree("""{"schemaVersion":2,"home":{"grid":{"columns":8}}}""")

        val result = WriteBackPlan.changes(literal = literal, fileEffective = applied, device = applied)

        assertEquals(emptyList<WriteBackPlan.Change>(), result)
    }

    /** Grid items as the file writes them, what they produced once applied, and the device now. */
    private fun items(json: String) = """{"schemaVersion":2,"home":{"grid":{"layouts":{"phone":{"items":$json}}}}}"""

    private fun gridChange(literal: String, applied: String, device: String) =
        WriteBackPlan.changes(tree(items(literal)), tree(items(applied)), tree(items(device)))

    /**
     * D inside a list: the dock's `h: 7` was clamped to 6 (#140). Moving the
     * clock rewrites the list, and the dock must keep what the file says.
     */
    @Test
    fun `an element nobody touched keeps its written values when its list is rewritten`() {
        val result = gridChange(
            literal = """[{"id":"dock","widget":"favorites","h":7},{"id":"clock","widget":"a.b/.C","x":0}]""",
            applied = """[{"id":"dock","widget":"favorites","h":6},{"id":"clock","widget":"a.b/.C","x":0}]""",
            device = """[{"id":"dock","widget":"favorites","h":6},{"id":"clock","widget":"a.b/.C","x":2}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement(
                """{"phone":{"items":[{"id":"dock","widget":"favorites","h":7},{"id":"clock","widget":"a.b/.C","x":2}]}}"""
            ),
            result.single().value,
        )
    }

    /** D inside an element: the dock moved, its clamped height did not, and keeps what the file says. */
    @Test
    fun `a moved element keeps the written value of a field nobody changed`() {
        val result = gridChange(
            literal = """[{"id":"dock","widget":"favorites","x":0,"h":7}]""",
            applied = """[{"id":"dock","widget":"favorites","x":0,"h":6}]""",
            device = """[{"id":"dock","widget":"favorites","x":1,"h":6}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement("""{"phone":{"items":[{"id":"dock","widget":"favorites","x":1,"h":7}]}}"""),
            result.single().value,
        )
    }

    /**
     * Review on #155: a key inside a rebuilt entry that the device's model
     * does not know - a newer field, one written by hand - is the file's, and
     * survives. The rule is that the file's text survives, not a list of
     * fields to keep.
     */
    @Test
    fun `a key the model does not know survives inside a rebuilt entry`() {
        val result = gridChange(
            literal = """[{"id":"clock","widget":"a.b/.C","x":0,"futureField":{"k":1}}]""",
            applied = """[{"id":"clock","widget":"a.b/.C","x":0}]""",
            device = """[{"id":"clock","widget":"a.b/.C","x":2}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement("""{"phone":{"items":[{"id":"clock","widget":"a.b/.C","x":2,"futureField":{"k":1}}]}}"""),
            result.single().value,
        )
    }

    /** A list the device state differs in, merged back to exactly what the file says, is no change to write. */
    @Test
    fun `a merged value equal to the file's is not written`() {
        assertEquals(
            emptyList<WriteBackPlan.Change>(),
            WriteBackPlan.changes(
                tree(favorites("""["org.a.one","org.not.here"]""")),
                tree(favorites("""[]""")),
                tree(favorites("""["org.a.one"]""")),
            ),
        )
    }

    /** The device lists items in its own order; they are matched by id, never by position. */
    @Test
    fun `elements are matched by id, not by position`() {
        val result = gridChange(
            literal = """[{"id":"dock","widget":"favorites","h":7},{"id":"clock","widget":"a.b/.C","x":0}]""",
            applied = """[{"id":"dock","widget":"favorites","h":6},{"id":"clock","widget":"a.b/.C","x":0}]""",
            device = """[{"id":"clock","widget":"a.b/.C","x":2},{"id":"dock","widget":"favorites","h":6}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement(
                """{"phone":{"items":[{"id":"clock","widget":"a.b/.C","x":2},{"id":"dock","widget":"favorites","h":7}]}}"""
            ),
            result.single().value,
        )
    }

    private fun favorites(json: String) = """{"schemaVersion":2,"home":{"favorites":$json}}"""

    private fun favoritesChange(literal: String, applied: String, device: String) =
        WriteBackPlan.changes(tree(favorites(literal)), tree(favorites(applied)), tree(favorites(device)))
            .single().value

    /**
     * An entry the device could not apply (an app not installed here) never
     * reached it, so the device cannot have removed it: it stays, where it
     * was, when the list is rewritten for another change.
     */
    @Test
    fun `an entry the device could not apply stays in its rewritten list`() {
        assertEquals(
            ConfigParser.json.parseToJsonElement("""["org.a.one","org.not.here","org.a.two"]"""),
            favoritesChange(
                literal = """["org.a.one","org.not.here"]""",
                applied = """["org.a.one"]""",
                device = """["org.a.one","org.a.two"]""",
            ),
        )
    }

    /**
     * A section whose apply failed leaves the baseline without entries the
     * device still has. An entry the device has is the file's, never a second
     * copy of it.
     */
    @Test
    fun `an entry the baseline lost but the device has is not written twice`() {
        assertEquals(
            ConfigParser.json.parseToJsonElement("""["org.a.one","org.not.here","org.a.two"]"""),
            favoritesChange(
                literal = """["org.a.one","org.not.here"]""",
                applied = """[]""",
                device = """["org.a.one","org.a.two"]""",
            ),
        )
    }

    /**
     * As it really is: the file writes personal favorites as strings, the
     * device serves every favorite as an object. Unchanged entries keep the
     * string; an entry that changed is written whole, in the device's form.
     */
    @Test
    fun `favorites written as strings keep their form, a changed one is written complete`() {
        val o = { pkg: String, profile: String -> """{"packageName":"$pkg","profile":"$profile"}""" }
        val result = WriteBackPlan.changes(
            literal = tree(favorites("""["org.a.one","org.a.two"]""")),
            fileEffective = tree(favorites("""[${o("org.a.one", "personal")},${o("org.a.two", "personal")}]""")),
            device = tree(favorites("""[${o("org.a.one", "personal")},${o("org.a.two", "work")}]""")),
            canonical = tree(favorites("""[${o("org.a.one", "personal")},${o("org.a.two", "personal")}]""")),
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement("""["org.a.one",${o("org.a.two", "work")}]"""),
            result.single().value,
        )
    }

    @Test
    fun `an entry removed on the device leaves the list`() {
        assertEquals(
            ConfigParser.json.parseToJsonElement("""["org.a.one"]"""),
            favoritesChange(
                literal = """["org.a.one","org.a.two"]""",
                applied = """["org.a.one","org.a.two"]""",
                device = """["org.a.one"]""",
            ),
        )
    }

    /** The file's own spelling of an entry the device kept: here the object form of a personal favorite. */
    @Test
    fun `an entry the device kept keeps its written form`() {
        val literal = tree(favorites("""[{"packageName":"org.a.one"},"org.a.two"]"""))
        val canonical = tree(favorites("""["org.a.one","org.a.two"]"""))

        val result = WriteBackPlan.changes(literal, tree(favorites("""["org.a.one","org.a.two"]""")), tree(favorites("""["org.a.one"]""")), canonical)

        assertEquals(ConfigParser.json.parseToJsonElement("""[{"packageName":"org.a.one"}]"""), result.single().value)
    }

    /** The grid-item exception (ADR 0002): an absent option means its default, and stays absent. */
    @Test
    fun `an option the file left out stays out while it keeps its default`() {
        val result = gridChange(
            literal = """[{"id":"clock","widget":"a.b/.C","x":0}]""",
            applied = """[{"id":"clock","widget":"a.b/.C","x":0,"borderless":false,"background":true,"themeColors":true}]""",
            device = """[{"id":"clock","widget":"a.b/.C","x":1,"borderless":false,"background":true,"themeColors":true}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement("""{"phone":{"items":[{"id":"clock","widget":"a.b/.C","x":1}]}}"""),
            result.single().value,
        )
    }

    @Test
    fun `an option the device changed is written, the others stay out`() {
        val result = gridChange(
            literal = """[{"id":"clock","widget":"a.b/.C"}]""",
            applied = """[{"id":"clock","widget":"a.b/.C","borderless":false,"background":true,"themeColors":true}]""",
            device = """[{"id":"clock","widget":"a.b/.C","borderless":true,"background":true,"themeColors":true}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement("""{"phone":{"items":[{"id":"clock","widget":"a.b/.C","borderless":true}]}}"""),
            result.single().value,
        )
    }

    @Test
    fun `an element added on the device is written without its option defaults`() {
        val result = gridChange(
            literal = """[]""",
            applied = """[]""",
            device = """[{"id":"clock","widget":"a.b/.C","x":0,"y":0,"w":4,"h":2,"borderless":false,"background":true,"themeColors":true}]""",
        )

        assertEquals(
            ConfigParser.json.parseToJsonElement("""{"phone":{"items":[{"id":"clock","widget":"a.b/.C","x":0,"y":0,"w":4,"h":2}]}}"""),
            result.single().value,
        )
    }

    /** The option defaults are the grid items' exception, not a rule for any key that shares a name. */
    @Test
    fun `a new entry outside the grid keeps a value that looks like an option default`() {
        val file = """{"schemaVersion":2,"search":{"actions":[]}}"""
        val result = WriteBackPlan.changes(
            tree(file),
            tree(file),
            tree("""{"schemaVersion":2,"search":{"actions":[{"type":"url","background":true}]}}"""),
        )

        assertEquals(ConfigParser.json.parseToJsonElement("""[{"type":"url","background":true}]"""), result.single().value)
    }

    @Test
    fun `a splice rewrites the changed values and keeps every other byte`() {
        val file = """
            {
              // provisioned by the host
              "schemaVersion": 2,
              "home": {
                "grid": { "columns": 4, /* four fit the cover */ "labels": true }
              },
              "search": { "layout": "grid" }
            }
        """.trimIndent()
        val changes = listOf(
            WriteBackPlan.Change(listOf("home", "grid", "columns"), JsonPrimitive(12)),
            WriteBackPlan.Change(listOf("search", "layout"), JsonPrimitive("list")),
        )

        val spliced = WriteBackPlan.splice(file, changes)

        assertEquals(
            file.replace("\"columns\": 4", "\"columns\": 12").replace("\"layout\": \"grid\"", "\"layout\": \"list\""),
            spliced,
        )
    }

    @Test
    fun `a spliced object or list is indented under its key`() {
        val file = "{\n  \"schemaVersion\": 2,\n  \"home\": {\n    \"favorites\": [\"org.a.one\"]\n  }\n}"
        val favorites = ConfigParser.json.parseToJsonElement("""["org.a.two","org.a.one"]""")

        val spliced = WriteBackPlan.splice(file, listOf(WriteBackPlan.Change(listOf("home", "favorites"), favorites)))

        assertNotNull(spliced)
        assertEquals(
            "{\n  \"schemaVersion\": 2,\n  \"home\": {\n    \"favorites\": [\n        \"org.a.two\",\n        \"org.a.one\"\n    ]\n  }\n}",
            spliced,
        )
    }
}

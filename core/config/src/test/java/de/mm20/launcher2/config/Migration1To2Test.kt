package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema 1 -> 2. The golden pair under `src/test/resources/migrations` is the
 * documented shape of both versions: `v1-full.json` is what the provisioning
 * generator emitted for schema 1 (JSONC, since the migration runs on the
 * parsed tree), `v2-full.json` is what it must become. Compared as trees, so
 * formatting is free.
 */
class Migration1To2Test {

    private fun resource(name: String): JsonObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/migrations/$name")) {
            "missing test resource /migrations/$name"
        }.bufferedReader().readText()
        return ConfigParser.json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `the golden v1 document migrates to the golden v2 document`() {
        val migrated = Migration1To2.migrate(resource("v1-full.json"))

        assertEquals(resource("v2-full.json"), migrated)
    }

    @Test
    fun `dock favorites move up to home favorites and dock goes`() {
        val v1 = ConfigParser.json.parseToJsonElement(
            """{"schemaVersion":1,"home":{"dock":{"enabled":true,"favorites":["com.example.a"]}}}"""
        ).jsonObject

        val v2 = Migration1To2.migrate(v1)

        val home = v2["home"]!!.jsonObject
        assertNull(home["dock"])
        assertEquals("""["com.example.a"]""", home["favorites"].toString())
        assertEquals(JsonPrimitive(2), v2["schemaVersion"])
    }

    @Test
    fun `the widgets list goes and enabled stays`() {
        val v1 = ConfigParser.json.parseToJsonElement(
            """{"schemaVersion":1,"home":{"widgets":{"enabled":true,"widgets":["apps"]}}}"""
        ).jsonObject

        val widgets = Migration1To2.migrate(v1)["home"]!!.jsonObject["widgets"]!!.jsonObject

        assertEquals(setOf("enabled"), widgets.keys)
    }

    @Test
    fun `a document without home only gets its version bumped`() {
        val v1 = ConfigParser.json.parseToJsonElement("""{"schemaVersion":1,"icons":{"themed":true}}""").jsonObject

        val v2 = Migration1To2.migrate(v1)

        assertEquals(JsonPrimitive(2), v2["schemaVersion"])
        assertEquals(v1["icons"], v2["icons"])
        assertNull(v2["home"])
    }

    @Test
    fun `a dock without favorites leaves no favorites key behind`() {
        val v1 = ConfigParser.json.parseToJsonElement(
            """{"schemaVersion":1,"home":{"dock":{"enabled":false}}}"""
        ).jsonObject

        val home = Migration1To2.migrate(v1)["home"]!!.jsonObject

        assertEquals(emptySet<String>(), home.keys)
    }

    @Test
    fun `keys the migration does not know pass through untouched`() {
        val v1 = ConfigParser.json.parseToJsonElement(
            """{"schemaVersion":1,"future":{"x":1},"home":{"searchBar":{"position":"top"},"future2":true,"dock":{"future3":1,"favorites":[]}}}"""
        ).jsonObject

        val v2 = Migration1To2.migrate(v1)

        assertEquals(v1["future"], v2["future"])
        val home = v2["home"]!!.jsonObject
        assertEquals(v1["home"]!!.jsonObject["searchBar"], home["searchBar"])
        assertEquals(JsonPrimitive(true), home["future2"])
        // dock.future3 has no home in v2; the dock object is gone with it.
        assertNull(home["dock"])
    }

    @Test
    fun `the migration is pure`() {
        val v1 = resource("v1-full.json")

        val first = Migration1To2.migrate(v1)
        val second = Migration1To2.migrate(v1)

        assertEquals(first, second)
        assertEquals("the input is not mutated", resource("v1-full.json"), v1)
    }

    @Test
    fun `a full v1 document parses into a v2 config with the same favorites`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/migrations/v1-full.json")).bufferedReader().readText()

        val result = ConfigParser.parse(text)

        assertTrue(result.diagnostics.toString(), result.isSuccess)
        // The v1 golden still carries the transparency block, which left the
        // contract with #73: it is reported, and nothing else is.
        assertEquals(
            listOf("inert-key" to "appearance.transparency"),
            result.diagnostics.map { it.code to it.path },
        )
        val config = result.config!!
        assertEquals(2, config.schemaVersion)
        assertEquals(
            listOf(
                Favorite("org.thoughtcrime.securesms", Profile.Personal),
                Favorite("com.example.work.mail", Profile.Work),
                Favorite("com.example.dialer", Profile.Personal),
            ),
            config.home?.favorites,
        )
        assertEquals(true, config.home?.widgets?.enabled)
        assertNull(config.home?.grid)
    }
}

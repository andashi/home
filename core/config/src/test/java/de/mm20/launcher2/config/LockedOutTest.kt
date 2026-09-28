package de.mm20.launcher2.config

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The config side of the launcher's lockout page (#229). The launcher decides
 * it in ScaffoldConfiguration.isUseless (app:ui); [isLockedOut] is the same
 * rule in the file's words. Two implementations of one rule can drift, so the
 * cases are not written here: both this test and LockoutTest in app:ui read
 * docs/configuration/search-unreachable-cases.json, a declared input of both
 * test tasks. A case added there is checked on both sides.
 */
class LockedOutTest {

    @Test
    fun `every shared case agrees with the file's rule`() {
        val cases = ConfigParser.json.parseToJsonElement(RepoDocs.read("docs/configuration/search-unreachable-cases.json"))
            .jsonObject.getValue("cases").jsonArray
        check(cases.size > 1) { "no cases read" }

        val disagreements = cases.map { it.jsonObject }.mapNotNull { case ->
            val named = case.getValue("gestures").jsonObject.mapKeys { (key, _) -> Gesture.entries.single { it.path == "gestures.$key" } }
                .mapValues { (_, value) -> ConfigParser.json.decodeFromJsonElement(GestureConfig.serializer(), value) }
            val state = ConfigState(
                searchBarHidden = case.getValue("hidden").jsonPrimitive.boolean,
                gestures = Gesture.entries.associateWith { GestureConfig.Action(GestureActionName.None) as GestureConfig? } + named,
            )
            val expected = case.getValue("lockedOut").jsonPrimitive.boolean
            if (state.isLockedOut == expected) null else "${case.getValue("case").jsonPrimitive.content}: expected $expected"
        }

        assertEquals(emptyList<String>(), disagreements)
    }

    @Test
    fun `the launcher's own defaults are not locked out, bar hidden or not`() {
        assertEquals(false, ConfigState().isLockedOut)
        assertEquals(false, ConfigState(searchBarHidden = true).isLockedOut)
    }
}

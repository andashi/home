package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What a write-back rewrites in `launcher.json`, and the rewrite itself
 * (#3 slice 4, ADR 0003: the config is the source of truth in both
 * directions, and the device stays editable).
 *
 * Only keys the file already has are written (W1): a key it leaves out is
 * unmanaged, and a write-back must not start managing it. A key the device's
 * model does not know - an inert key, or one of a newer build - is left as
 * written.
 *
 * The device is compared with what the file produces once applied, not with
 * what it literally says (D): a value the launcher could not apply as written,
 * such as a clamped one (#140), shows no difference and keeps its value in the
 * file. Write-back records what a person changed on the device, never what the
 * launcher failed to do. The cost: setting on the device exactly the value the
 * file's setting is clamped to writes nothing, and the file keeps its own,
 * which renders the same.
 */
object WriteBackPlan {

    /** Rewrite the value at [path] (object keys from the root) with [value]. */
    data class Change(val path: List<String>, val value: JsonElement)

    /**
     * The changes that make [literal] (the file as written) say what [device]
     * has in effect. [fileEffective] is what the file produces once applied;
     * where it has no value for a key, the key's literal value stands in.
     */
    fun changes(literal: JsonObject, fileEffective: JsonObject, device: JsonObject): List<Change> =
        buildList { collect(literal, fileEffective, device, emptyList()) }

    private fun MutableList<Change>.collect(
        literal: JsonObject,
        fileEffective: JsonObject?,
        device: JsonObject,
        at: List<String>,
    ) {
        for ((key, written) in literal) {
            val now = device[key]?.takeUnless { it is JsonNull } ?: continue
            val effect = fileEffective?.get(key)?.takeUnless { it is JsonNull } ?: written
            val path = at + key
            if (written is JsonObject && now is JsonObject && path !in WholeValues) {
                collect(written, effect as? JsonObject, now, path)
            } else if (!same(effect, now)) {
                add(Change(path, merged(written, effect, now)))
            }
        }
    }

    /**
     * Objects the contract uses as maps, keyed by name: written whole when
     * present, like a list, because their entries are data rather than keys.
     * The grid layouts are the only one.
     */
    private val WholeValues = setOf(listOf("home", "grid", "layouts"))

    /**
     * The value to write for a list or map the device changed: D at every
     * depth. Inside it, what nobody changed keeps its written text - a
     * clamped `h: 7` stays 7 when its neighbour moves - and a field the file
     * left out that still has the value it produced stays out, which is the
     * grid-item exception applied rather than special-cased. Elements are
     * matched by `id` where they have one, else by position. What the file
     * has no counterpart for is the device's, without its option defaults.
     */
    private fun merged(written: JsonElement?, effect: JsonElement?, now: JsonElement): JsonElement = when {
        now is JsonObject -> JsonObject(
            buildMap {
                for ((key, value) in now) {
                    val was = (effect as? JsonObject)?.get(key)
                    val text = (written as? JsonObject)?.get(key)
                    when {
                        was != null && same(was, value) -> if (text != null) put(key, text)
                        written == null && GridItemConfig.OptionDefaults[key]?.let { JsonPrimitive(it) == value } == true -> Unit
                        else -> put(key, merged(text, was, value))
                    }
                }
            }
        )
        now is JsonArray -> JsonArray(
            now.mapIndexed { index, element ->
                merged(
                    (written as? JsonArray)?.counterpart(element, index),
                    (effect as? JsonArray)?.counterpart(element, index),
                    element,
                )
            }
        )
        else -> if (written != null && effect != null && same(effect, now)) written else now
    }

    /** The element [element] corresponds to: the one with its `id`, or, without one, the one at [index]. */
    private fun JsonArray.counterpart(element: JsonElement, index: Int): JsonElement? {
        val id = (element as? JsonObject)?.get("id") ?: return getOrNull(index)
        return firstOrNull { (it as? JsonObject)?.get("id") == id }
    }


    /** Structural equality, with numbers compared by value: `12` and `12.0` are the same setting. */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString ->
            a.doubleOrNull?.let { it == b.doubleOrNull } ?: (a == b)
        a is JsonObject && b is JsonObject ->
            a.keys == b.keys && a.all { (k, v) -> same(v, b.getValue(k)) }
        a is JsonArray && b is JsonArray ->
            a.size == b.size && a.indices.all { same(a[it], b[it]) }
        else -> a == b
    }

    /**
     * [text] with every change spliced over exactly its value's span, so every
     * other byte survives, comments included. Null when a change's key is not
     * where the file had it, which cannot happen for changes made from this
     * text; the caller then writes nothing.
     */
    fun splice(text: String, changes: List<Change>): String? {
        val spans = changes.map { change ->
            val found = JsoncObjectSpan.find(text, change.path) as? JsoncSpanResult.Found ?: return null
            found to change.value
        }
        var out = text
        // From the end, so an earlier span's offsets still hold.
        for ((span, value) in spans.sortedByDescending { it.first.start }) {
            out = out.replaceRange(span.start, span.endExclusive, render(value, JsoncObjectSpan.lineIndent(text, span.start)))
        }
        return out
    }

    /** Pretty JSON is rendered at column 0; every line after the first moves under [indent]. */
    private fun render(value: JsonElement, indent: String): String {
        val lines = ConfigParser.json.encodeToString(JsonElement.serializer(), value).split('\n')
        return lines[0] + lines.drop(1).joinToString("") { "\n" + indent + it }
    }
}

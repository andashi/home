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
            if (written is JsonObject && now is JsonObject) {
                collect(written, effect as? JsonObject, now, path)
            } else if (!same(effect, now)) {
                add(Change(path, now))
            }
        }
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

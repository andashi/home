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
     * has in effect. [fileEffective] is what the file produced once applied.
     * [canonical] is the file parsed and encoded again, the shape the other
     * two have, which the entries of a list are paired with; it is [literal]
     * wherever the file already writes the canonical form.
     */
    fun changes(
        literal: JsonObject,
        fileEffective: JsonObject,
        device: JsonObject,
        canonical: JsonObject = literal,
    ): List<Change> = buildList { collect(literal, canonical, fileEffective, device, emptyList()) }

    private fun MutableList<Change>.collect(
        literal: JsonObject,
        canonical: JsonObject?,
        fileEffective: JsonObject?,
        device: JsonObject,
        at: List<String>,
    ) {
        for ((key, written) in literal) {
            val now = device[key]?.takeUnless { it is JsonNull } ?: continue
            val canon = canonical?.get(key)?.takeUnless { it is JsonNull } ?: written
            val effect = fileEffective?.get(key)?.takeUnless { it is JsonNull } ?: canon
            val path = at + key
            if (written is JsonObject && now is JsonObject && path !in WholeValues) {
                collect(written, canon as? JsonObject, effect as? JsonObject, now, path)
            } else if (!same(effect, now)) {
                add(Change(path, merged(written, canon, effect, now)))
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
     * depth. [written] is the file's text of it, [canon] the same parsed,
     * [effect] what it produced, [now] the device's.
     *
     * - What nobody changed keeps its written text: a clamped `h: 7` stays 7
     *   when its neighbour moves.
     * - A field the file left out that still has the value it produced stays
     *   out: the grid-item exception applied rather than special-cased.
     * - A list entry the device could not apply (an app not installed here)
     *   never reached it, so the device cannot have removed it: it stays,
     *   where it was. An entry it had and no longer has was removed on it.
     * - What the file has no counterpart for is the device's, without its
     *   option defaults.
     */
    private fun merged(written: JsonElement?, canon: JsonElement?, effect: JsonElement?, now: JsonElement): JsonElement = when {
        now is JsonObject -> JsonObject(
            buildMap {
                for ((key, value) in now) {
                    val was = (effect as? JsonObject)?.get(key)
                    val text = (written as? JsonObject)?.get(key)
                    when {
                        was != null && same(was, value) -> if (text != null) put(key, text)
                        written == null && GridItemConfig.OptionDefaults[key]?.let { JsonPrimitive(it) == value } == true -> Unit
                        else -> put(key, merged(text, (canon as? JsonObject)?.get(key), was, value))
                    }
                }
            }
        )
        now is JsonArray -> mergedList(written as? JsonArray, (canon ?: written) as? JsonArray, effect as? JsonArray, now)
        else -> if (written != null && effect != null && same(effect, now)) written else now
    }

    private fun mergedList(written: JsonArray?, canon: JsonArray?, effect: JsonArray?, now: JsonArray): JsonArray {
        val fileEntries = canon.orEmpty()
        val applied = effect.orEmpty()
        // Which file entry each applied entry came from.
        val takenFile = mutableSetOf<Int>()
        val fileOf = applied.map { entry -> fileEntries.pair(entry, takenFile)?.also { takenFile += it } }
        // Which applied entry each device entry is.
        val takenApplied = mutableSetOf<Int>()
        val out = now.map { entry ->
            val j = applied.pair(entry, takenApplied)?.also { takenApplied += it }
            val i = j?.let { fileOf[it] }
            merged(i?.let { written?.getOrNull(it) }, i?.let { fileEntries[it] }, j?.let { applied[it] }, entry)
        }.toMutableList()
        // The file's entries that never reached the device, back where they were.
        for (i in fileEntries.indices) {
            if (i in takenFile) continue
            val text = written?.getOrNull(i) ?: continue
            out.add(i.coerceAtMost(out.size), text)
        }
        return JsonArray(out)
    }

    /** The unpaired entry [entry] is: the one with its `id`, or, without one, the first equal one. */
    private fun JsonArray.pair(entry: JsonElement, taken: Set<Int>): Int? {
        val id = (entry as? JsonObject)?.get("id")
        return indices.firstOrNull { it !in taken && if (id != null) (this[it] as? JsonObject)?.get("id") == id else same(this[it], entry) }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

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

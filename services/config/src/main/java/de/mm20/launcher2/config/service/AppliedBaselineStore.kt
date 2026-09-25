package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.config.ConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * What the file with hash [configSha256] produced once applied: the effective
 * state served right after its reload or self-write (#3 slice 4, D).
 */
@Serializable
data class AppliedBaseline(
    val configSha256: String,
    val effective: JsonObject,
)

/**
 * Persists the [AppliedBaseline] in app-internal storage
 * (`files/config/applied-baseline.json`), next to the reload report.
 *
 * A write-back compares the device with it, not with the file's literal
 * values: the launcher applies some values differently from how they are
 * written (a widget height clamped to what the widget allows, #140), and only
 * the state after the apply knows that. Nothing in it is not already in the
 * config file and the settings.
 *
 * Writes are atomic (temp file, then rename). A missing or corrupt baseline
 * reads back as null, and a write-back without one skips the file.
 */
class AppliedBaselineStore(context: Context) {
    private val file = File(context.filesDir, "config/applied-baseline.json")

    suspend fun save(baseline: AppliedBaseline) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ConfigParser.json.encodeToString(AppliedBaseline.serializer(), baseline))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace ${file.name}")
        }
    }

    suspend fun read(): AppliedBaseline? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            ConfigParser.json.decodeFromString(AppliedBaseline.serializer(), file.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}

/**
 * Every section a reload applies, by its path in the document
 * ([de.mm20.launcher2.config.ConfigMutation.section]). BaselineOfTest fails
 * when a reload can apply one that is missing here.
 */
internal val ConfigSections = listOf(
    "icons",
    "appearance.glass",
    "appearance.wallpaper",
    "search",
    "search.actions",
    "home.searchBar",
    "home.favorites",
    "home.widgets.enabled",
    "home.grid",
)

/**
 * The baseline of one reload: each section it [applied] as the device has it
 * after the apply - clamps and skipped entries included - and every other
 * section as it was before. A section the reload did not change already was
 * what the file produces; taking it from before keeps out whatever a person
 * changed on the device while the apply ran, so that change is still written
 * back. A section inside an applied one that was not itself applied comes
 * from before as well.
 */
internal fun baselineOf(before: JsonObject, after: JsonObject, applied: Collection<String>): JsonObject {
    var out = before
    for (section in applied) out = out.with(section.split('.'), after.at(section.split('.')))
    for (section in ConfigSections) {
        if (section in applied) continue
        if (applied.any { section.startsWith("$it.") }) out = out.with(section.split('.'), before.at(section.split('.')))
    }
    return out
}

private fun JsonObject.at(path: List<String>): JsonElement? =
    path.fold<String, JsonElement?>(this) { node, key -> (node as? JsonObject)?.get(key) }

/** This object with the value at [path] set to [value], or removed when [value] is null. */
private fun JsonObject.with(path: List<String>, value: JsonElement?): JsonObject {
    val key = path.first()
    if (path.size == 1) return JsonObject(if (value == null) this - key else this + (key to value))
    val child = this[key] as? JsonObject ?: if (value == null) return this else JsonObject(emptyMap())
    return JsonObject(this + (key to child.with(path.drop(1), value)))
}

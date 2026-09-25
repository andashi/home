package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.LauncherConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException

/** The effective config as the tree a write-back compares and a baseline records (#3 slice 4). */
internal fun effectiveTree(config: LauncherConfig): JsonObject =
    ConfigParser.json.encodeToJsonElement(LauncherConfig.serializer(), config).jsonObject

/** The value at [path] (object keys from here), or null where the path leaves the objects. */
internal fun JsonObject.at(path: List<String>): JsonElement? =
    path.fold<String, JsonElement?>(this) { node, key -> (node as? JsonObject)?.get(key) }

/** This object with the value at [path] set to [value], or removed when [value] is null. */
internal fun JsonObject.with(path: List<String>, value: JsonElement?): JsonObject {
    val key = path.first()
    if (path.size == 1) return JsonObject(if (value == null) this - key else this + (key to value))
    val child = this[key] as? JsonObject ?: if (value == null) return this else JsonObject(emptyMap())
    return JsonObject(this + (key to child.with(path.drop(1), value)))
}

/**
 * Replaces this file with [text] the one way the config files are written:
 * a temp file next to it, then a rename, so a reader never sees a torn file.
 * A failed rename keeps the old file and throws.
 */
internal fun File.replaceAtomically(text: String, tempSuffix: String = ".tmp") {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name$tempSuffix")
    try {
        tmp.writeText(text)
        if (!tmp.renameTo(this)) throw IOException("Could not replace $name")
    } finally {
        tmp.delete()
    }
}

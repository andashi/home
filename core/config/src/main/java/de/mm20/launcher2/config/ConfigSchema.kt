package de.mm20.launcher2.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The JSON Schema of `launcher.json` (#3 slice 3, ADR 0002), generated from
 * what the parser reads: the keys from [ConfigParser.keyEffects], their types
 * from the serial descriptors, their limits from [ConfigValidator]'s constants
 * and patterns. Nothing here restates a number.
 *
 * Strict (`additionalProperties: false`): the schema describes what this
 * build understands, so an editor marks a typo while it is typed. The parser
 * stays tolerant - an unknown key is a warning there - so a newer file does
 * not break an older launcher. Keys that are accepted but do nothing
 * ([KeyEffect.Inert]) are listed as deprecated, not rejected.
 *
 * A custom serializer has no descriptor that says what it accepts, so every
 * one is named in [custom]; one that is not fails generation, and with it
 * the schema test.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ConfigSchema {

    const val Dialect = "https://json-schema.org/draft/2020-12/schema"

    private val pretty = Json { prettyPrint = true }

    /** The schema as it is checked in: pretty-printed, stable, one trailing newline. */
    fun text(): String = pretty.encodeToString(JsonObject.serializer(), document()) + "\n"

    fun document(): JsonObject = buildJsonObject {
        put("\$schema", Dialect)
        put("title", "launcher.json")
        put(
            "description",
            "The Andashi Home launcher configuration, schemaVersion ${ConfigMigrations.currentSchemaVersion}. " +
                "Generated from the parser; see docs/configuration and ADR 0002.",
        )
        objectSchema(LauncherConfig.serializer().descriptor, "").forEach { (k, v) -> put(k, v) }
    }

    // ---- structure ----

    private fun objectSchema(descriptor: SerialDescriptor, section: String): JsonObject {
        val keys = ConfigParser.keyEffects[section]
            ?: error("no key table entry for '$section' (ConfigParser.keyEffects)")
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        for (name in names) {
            require(name in keys) { "model key ${join(section, name)} is not in the parser's key table" }
        }
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (i in names.indices) {
                    put(names[i], propertySchema(join(section, names[i]), descriptor.getElementDescriptor(i)))
                }
                for ((key, effect) in keys) {
                    if (key in names) continue
                    val inert = effect as? KeyEffect.Inert
                        ?: error("key ${join(section, key)} is in the key table but not in the model")
                    putJsonObject(key) {
                        put("deprecated", true)
                        put("description", "Accepted and ignored: ${inert.reason}")
                    }
                }
            }
            val required = names.filterIndexed { i, _ -> !descriptor.isElementOptional(i) }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
            objectRules[section]?.let { put("allOf", it) }
        }
    }

    /** Fields one type of an object needs (ConfigValidator's search action checks). */
    private val objectRules: Map<String, JsonElement> = mapOf(
        "search.actions[]" to buildJsonArray {
            add(requiredWhenType(SearchActionTypes.Url, "label", "url"))
            add(requiredWhenType(SearchActionTypes.App, "label", "package"))
        },
    )

    private fun requiredWhenType(type: String, vararg fields: String) = buildJsonObject {
        putJsonObject("if") {
            putJsonObject("properties") { putJsonObject("type") { put("const", type) } }
            putJsonArray("required") { add(JsonPrimitive("type")) }
        }
        putJsonObject("then") { putJsonArray("required") { fields.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun propertySchema(path: String, descriptor: SerialDescriptor): JsonObject {
        replaced[path]?.let { return it }
        val base = typeSchema(path, descriptor)
        val extra = constraints[path] ?: return base
        return JsonObject(base + extra)
    }

    private fun typeSchema(path: String, descriptor: SerialDescriptor): JsonObject {
        val name = descriptor.serialName.removeSuffix("?")
        custom[name]?.let { return it(path, descriptor) }
        return when (val kind = descriptor.kind) {
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            PrimitiveKind.STRING -> {
                require(name == "kotlin.String") { "custom serializer $name at $path has no schema entry (ConfigSchema.custom)" }
                type("string")
            }
            SerialKind.ENUM -> enumOf((0 until descriptor.elementsCount).map(descriptor::getElementName))
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", propertySchema("$path[]", descriptor.getElementDescriptor(0)))
            }
            StructureKind.MAP -> {
                // The only map in the contract: the grid layouts by name.
                require(path == "home.grid.layouts") { "no schema for a map at $path" }
                val value = descriptor.getElementDescriptor(1)
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        for (layout in GridLayouts.All.sorted()) put(layout, objectSchema(value, "$path.$layout"))
                    }
                    put("additionalProperties", false)
                }
            }
            StructureKind.CLASS -> objectSchema(descriptor, path)
            else -> error("no schema for $name ($kind) at $path")
        }
    }

    /** The custom serializers, by the serial name of the descriptor they declare. */
    private val custom: Map<String, (String, SerialDescriptor) -> JsonObject> = mapOf(
        "de.mm20.launcher2.config.GlassContrast" to { _, _ -> enumOf(GlassContrast.entries.map { it.name.lowercase() }) },
        "de.mm20.launcher2.config.SearchBarPositionInSearch" to { _, _ ->
            enumOf(InSearchBarPosition.entries.map { it.name.lowercase() })
        },
        "de.mm20.launcher2.config.SearchResultLayout" to { _, _ ->
            enumOf(SearchResultLayout.entries.map { it.name.lowercase() })
        },
        // A package name for the personal profile, or the object form (FavoriteSerializer).
        "Favorite" to { path, descriptor ->
            buildJsonObject {
                putJsonArray("oneOf") {
                    add(packageName())
                    add(objectSchema(descriptor, path))
                }
            }
        },
    )

    // ---- limits, all from ConfigValidator ----

    /** Leaves whose schema is not their type's at all. */
    private val replaced: Map<String, JsonObject> = buildMap {
        put("schemaVersion", buildJsonObject { put("const", ConfigMigrations.currentSchemaVersion) })
        put("icons.pack", buildJsonObject {
            putJsonArray("oneOf") {
                add(buildJsonObject {
                    put("const", IconsConfig.NoPack)
                    put("description", "The apps' own icons: no pack, and no Lawnicons fallback.")
                })
                add(packageName())
            }
        })
        for (layout in GridLayouts.All) {
            put("home.grid.layouts.$layout.items[].widget", buildJsonObject {
                putJsonArray("oneOf") {
                    add(buildJsonObject { put("const", GridItemConfig.Favorites) })
                    add(buildJsonObject {
                        put("type", "string")
                        put("pattern", "^${body(ConfigValidator.packageNameRegex)}/${body(ConfigValidator.classNameRegex)}$")
                        put("description", "An AppWidget provider as package/class.")
                    })
                }
            })
        }
    }

    /** Limits added to a leaf's type. */
    private val constraints: Map<String, Map<String, JsonElement>> = buildMap {
        put("appearance.glass.blur", range(ConfigValidator.MinGlass, ConfigValidator.MaxGlassBlur))
        put("appearance.glass.tint", range(ConfigValidator.MinGlass, ConfigValidator.MaxGlassTint))
        put("appearance.glass.radius", range(ConfigValidator.MinGlass, ConfigValidator.MaxGlassRadius))
        put("appearance.wallpaper.image", pattern(ConfigValidator.imageNameRegex))
        put("home.favorites", mapOf("maxItems" to JsonPrimitive(ConfigValidator.MaxFavorites)))
        put("home.favorites[].packageName", packageNameLimits())
        put("home.grid.columns", range(ConfigValidator.MinGridColumns, ConfigValidator.MaxGridColumns))
        for (layout in GridLayouts.All) {
            val items = "home.grid.layouts.$layout.items"
            put(items, mapOf("maxItems" to JsonPrimitive(ConfigValidator.MaxGridItems)))
            put("$items[].id", pattern(ConfigValidator.gridItemIdRegex))
            put("$items[].x", range(0, ConfigValidator.MaxGridCoordinate))
            put("$items[].y", range(0, ConfigValidator.MaxGridCoordinate))
            put("$items[].w", range(1, ConfigValidator.MaxGridCoordinate))
            put("$items[].h", range(1, ConfigValidator.MaxGridCoordinate))
        }
        put("search.actions", mapOf("maxItems" to JsonPrimitive(ConfigValidator.MaxSearchActions)))
        put("search.actions[].type", enumOf((SearchActionTypes.Configurable + SearchActionTypes.Intent).sorted()))
        put("search.actions[].url", mapOf("pattern" to JsonPrimitive("\\$\\{1\\}")))
        put("search.actions[].package", packageNameLimits())
        put("search.actions[].encoding", enumOf(SearchActionTypes.Encodings.sorted()))
    }

    // ---- helpers ----

    private fun join(section: String, key: String) = if (section.isEmpty()) key else "$section.$key"

    private fun type(name: String) = buildJsonObject { put("type", name) }

    private fun enumOf(values: List<String>) = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private fun packageNameLimits(): Map<String, JsonElement> = mapOf(
        "pattern" to JsonPrimitive(ConfigValidator.packageNameRegex.pattern),
        "maxLength" to JsonPrimitive(ConfigValidator.MaxPackageNameLength),
    )

    private fun packageName() = JsonObject(mapOf("type" to JsonPrimitive("string")) + packageNameLimits())

    private fun pattern(regex: Regex): Map<String, JsonElement> = mapOf("pattern" to JsonPrimitive(regex.pattern))

    private fun range(min: Number, max: Number): Map<String, JsonElement> =
        mapOf("minimum" to JsonPrimitive(min), "maximum" to JsonPrimitive(max))

    /** A pattern without its anchors, to build a larger one from it. */
    private fun body(regex: Regex): String = regex.pattern.removePrefix("^").removeSuffix("$")
}

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
 * one is named in [fieldEnums] or handled in [typeSchema]; one that is not
 * fails generation, and with it the schema test.
 *
 * Test code: only [ConfigSchemaTest] runs it, to check the file in
 * docs/configuration against it, so nothing of it ships.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ConfigSchema {

    private val pretty = Json { prettyPrint = true }

    /** The schema as it is checked in: pretty-printed, stable, one trailing newline. */
    fun text(): String = pretty.encodeToString(JsonObject.serializer(), document()) + "\n"

    fun document(): JsonObject = JsonObject(
        mapOf(
            "\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema"),
            "title" to JsonPrimitive("launcher.json"),
            "description" to JsonPrimitive(
                "The Andashi Home launcher configuration, schemaVersion ${ConfigMigrations.currentSchemaVersion}. " +
                    "Generated from the parser; see docs/configuration and ADR 0002."
            ),
        ) + objectSchema(LauncherConfig.serializer().descriptor, "")
    )

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
            // The fields one type of search action needs (ConfigValidator's action checks).
            if (section == "search.actions[]") putJsonArray("allOf") {
                add(requiredWhenType(SearchActionTypes.Url, "label", "url"))
                add(requiredWhenType(SearchActionTypes.App, "label", "package"))
            }
        }
    }

    private fun requiredWhenType(type: String, vararg fields: String) = buildJsonObject {
        putJsonObject("if") {
            putJsonObject("properties") { putJsonObject("type") { put("const", type) } }
            putJsonArray("required") { add(JsonPrimitive("type")) }
        }
        putJsonObject("then") { putJsonArray("required") { fields.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun propertySchema(path: String, descriptor: SerialDescriptor): JsonObject {
        val key = anyLayout(path)
        replaced[key]?.let { return it }
        val base = typeSchema(path, descriptor)
        val extra = constraints[key] ?: return base
        return JsonObject(base + extra)
    }

    private fun typeSchema(path: String, descriptor: SerialDescriptor): JsonObject {
        val name = descriptor.serialName.removeSuffix("?")
        fieldEnums[name]?.let { return enumOf(it.names) }
        // A package name for the personal profile, or the object form (FavoriteSerializer).
        if (name == "Favorite") return buildJsonObject {
            putJsonArray("oneOf") {
                add(packageName())
                add(objectSchema(descriptor, path))
            }
        }
        return when (val kind = descriptor.kind) {
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            PrimitiveKind.STRING -> {
                require(name == "kotlin.String") { "custom serializer $name at $path has no schema entry (ConfigSchema)" }
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

    /** The enums with a field-naming serializer, by the serial name of its descriptor; their values come from it. */
    private val fieldEnums: Map<String, FieldEnumSerializer<*>> =
        listOf(GlassContrastSerializer, SearchBarPositionInSearchSerializer, SearchResultLayoutSerializer)
            .associateBy { it.descriptor.serialName }

    /** Every layout has the same items, so their limits are keyed once, under `*`. */
    private fun anyLayout(path: String) = path.replace(layoutPath, "home.grid.layouts.*.")

    private val layoutPath = Regex("^home\\.grid\\.layouts\\.[^.]+\\.")

    // ---- limits, all from ConfigValidator ----

    /** Leaves whose schema is not their type's at all. */
    private val replaced: Map<String, JsonObject> = mapOf(
        "schemaVersion" to buildJsonObject { put("const", ConfigMigrations.currentSchemaVersion) },
        "icons.pack" to buildJsonObject {
            putJsonArray("oneOf") {
                add(buildJsonObject {
                    put("const", IconsConfig.NoPack)
                    put("description", "The apps' own icons: no pack, and no Lawnicons fallback.")
                })
                add(packageName())
            }
        },
        "home.grid.layouts.*.items[].widget" to buildJsonObject {
            putJsonArray("oneOf") {
                add(buildJsonObject { put("const", GridItemConfig.Favorites) })
                add(buildJsonObject {
                    put("type", "string")
                    put("pattern", ConfigValidator.componentNameRegex.pattern)
                    put("description", "An AppWidget provider as package/class.")
                })
            }
        },
        "search.actions[].type" to enumOf((SearchActionTypes.Configurable + SearchActionTypes.Intent).sorted()),
        "search.actions[].encoding" to enumOf(SearchActionTypes.Encodings.sorted()),
    )

    /** Limits added to a leaf's type; [ConfigSchemaTest] breaks each one and expects the parser to object. */
    internal val constraints: Map<String, Map<String, JsonElement>> = mapOf(
        "appearance.glass.blur" to range(ConfigValidator.MinGlass, ConfigValidator.MaxGlassBlur),
        "appearance.glass.tint" to range(ConfigValidator.MinGlass, ConfigValidator.MaxGlassTint),
        "appearance.glass.radius" to range(ConfigValidator.MinGlass, ConfigValidator.MaxGlassRadius),
        "appearance.wallpaper.image" to pattern(ConfigValidator.imageNameRegex),
        "home.favorites" to maxItems(ConfigValidator.MaxFavorites),
        "home.favorites[].packageName" to packageNameLimits(),
        "home.grid.columns" to range(ConfigValidator.MinGridColumns, ConfigValidator.MaxGridColumns),
        "home.grid.layouts.*.items" to maxItems(ConfigValidator.MaxGridItems),
        "home.grid.layouts.*.items[].id" to pattern(ConfigValidator.gridItemIdRegex),
        "home.grid.layouts.*.items[].x" to range(ConfigValidator.MinGridPosition, ConfigValidator.MaxGridCoordinate),
        "home.grid.layouts.*.items[].y" to range(ConfigValidator.MinGridPosition, ConfigValidator.MaxGridCoordinate),
        "home.grid.layouts.*.items[].w" to range(ConfigValidator.MinGridSpan, ConfigValidator.MaxGridCoordinate),
        "home.grid.layouts.*.items[].h" to range(ConfigValidator.MinGridSpan, ConfigValidator.MaxGridCoordinate),
        "search.actions" to maxItems(ConfigValidator.MaxSearchActions),
        "search.actions[].url" to mapOf("pattern" to JsonPrimitive(literal(SearchActionTypes.QueryPlaceholder))),
        "search.actions[].package" to packageNameLimits(),
    )

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

    /** [text] as an ECMA-262 pattern that matches it literally; Regex.escape's \Q..\E is Java's only. */
    private fun literal(text: String) = text.replace(Regex("""[\\^$.|?*+()\[\]{}]""")) { "\\" + it.value }

    private fun maxItems(max: Int): Map<String, JsonElement> = mapOf("maxItems" to JsonPrimitive(max))

    private fun range(min: Number, max: Number): Map<String, JsonElement> =
        mapOf("minimum" to JsonPrimitive(min), "maximum" to JsonPrimitive(max))
}

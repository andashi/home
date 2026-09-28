package de.mm20.launcher2.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    /** How to regenerate the checked-in schema, for a change of the contract made on purpose. */
    const val RegenerateCommand = "./gradlew :core:config:testDebugUnitTest --tests '*ConfigSchemaTest*' -PupdateSchema"

    /**
     * Every property path in [schema] - `apps[].label`,
     * `gestures.swipeLeft.packageName` - list items and alternatives
     * (`oneOf`, `anyOf`, `allOf`) included.
     */
    fun keyPaths(schema: JsonObject, at: String = ""): Set<String> = buildSet {
        (schema["properties"] as? JsonObject)?.forEach { (key, value) ->
            val path = if (at.isEmpty()) key else "$at.$key"
            add(path)
            (value as? JsonObject)?.let { addAll(keyPaths(it, path)) }
        }
        (schema["items"] as? JsonObject)?.let { addAll(keyPaths(it, "$at[]")) }
        for (combinator in listOf("oneOf", "anyOf", "allOf")) {
            (schema[combinator] as? JsonArray)?.forEach { alternative ->
                (alternative as? JsonObject)?.let { addAll(keyPaths(it, at)) }
            }
        }
    }

    /**
     * Why the checked-in schema ([committed]) differs from the [generated]
     * one, key paths first. A removed path is what a merge or a refactor
     * that drops a key from ConfigParser.keyEffects looks like, and
     * regenerating would rewrite the file to match the loss, so then the
     * message says what it means and nothing about regenerating.
     */
    fun staleness(generated: String, committed: String?): String {
        val regenerate = "regenerate it with $RegenerateCommand"
        if (committed == null) return "docs/configuration/launcher.schema.json does not exist; $regenerate"
        val removed = removedKeyPaths(generated, committed).sorted()
        val added = (pathsOf(generated) - pathsOf(committed)).sorted()
        val addedLine = added.takeIf { it.isNotEmpty() }?.let { "key paths added: ${it.joinToString()}" }
        return when {
            removed.isNotEmpty() ->
                "key paths removed from the contract: ${removed.joinToString()}. " +
                    "Keys disappeared: if that was not deliberate, a merge or a refactor lost them from " +
                    "ConfigParser.keyEffects - find where before touching launcher.schema.json." +
                    addedLine?.let { " Also $it." }.orEmpty()
            addedLine != null -> "$addedLine. If that is the change you made, $regenerate"
            else ->
                "no key path added or removed, but launcher.schema.json differs (a limit, a value or a description); $regenerate"
        }
    }

    /**
     * Why `-PupdateSchema` must not write [generated] over [committed], or
     * null when it may. A write that removes key paths is refused unless
     * [acknowledged] (`-PremoveSchemaKeys`) names exactly those paths: the
     * remedy for a stale file must not be able to write a lost key into it.
     */
    fun refusal(generated: String, committed: String?, acknowledged: Set<String>): String? {
        if (committed == null) return null
        val removed = removedKeyPaths(generated, committed)
        if (removed.isEmpty() || removed == acknowledged) return null
        return "not written: ${staleness(generated, committed)} If the removal is deliberate, pass " +
            "-PremoveSchemaKeys=<comma-separated paths> naming exactly the removed ones " +
            "(named now: ${acknowledged.sorted().joinToString().ifEmpty { "none" }})."
    }

    private fun pathsOf(schema: String): Set<String> = keyPaths(Json.parseToJsonElement(schema) as JsonObject)

    private fun removedKeyPaths(generated: String, committed: String): Set<String> = pathsOf(committed) - pathsOf(generated)

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
            // A position is x and y together (partial-grid-position).
            if (section.startsWith("home.grid.layouts.") && section.endsWith(".items[]")) putJsonObject("dependentRequired") {
                putJsonArray("x") { add(JsonPrimitive("y")) }
                putJsonArray("y") { add(JsonPrimitive("x")) }
            }
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
        putJsonObject("then") {
            putJsonArray("required") { fields.forEach { add(JsonPrimitive(it)) } }
            // The validator wants them non-blank, not only present; url and
            // package have their own patterns, which a blank value fails.
            if ("label" in fields) {
                putJsonObject("properties") { putJsonObject("label") { put("pattern", "\\S") } }
            }
        }
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
        if (name == "AppIcon") return appIconSchema(path)
        if (name == "TagIcon") return tagIconSchema(path)
        // A package name for a personal app's first entry, or the object form (TagAppSerializer).
        if (name == "TagApp") return buildJsonObject {
            putJsonArray("oneOf") {
                add(packageName())
                add(objectSchema(descriptor, path))
            }
        }
        // A package name for the personal profile, or the object form (FavoriteSerializer).
        if (name == "Favorite") return buildJsonObject {
            putJsonArray("oneOf") {
                add(packageName())
                add(objectSchema(descriptor, path))
            }
        }
        // An action by name, or an app as a favorite's object form (GestureConfigSerializer, #3 slice 2).
        if (name == GestureConfigSerializer.descriptor.serialName) return buildJsonObject {
            putJsonArray("oneOf") {
                add(enumOf(GestureActionNameSerializer.names))
                add(objectSchema(Favorite.serializer().descriptor, ConfigParser.childSection(path.substringBeforeLast('.'), path.substringAfterLast('.'))))
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

    /**
     * An icon's object forms at [path], each a variant: the keys come from the
     * parser's key table and their leaves' limits from [constraints] and
     * [replaced], as every other key's, and the variants cover the table's
     * keys exactly.
     */
    private class IconVariants(private val path: String) {
        private val keys = ConfigParser.keyEffects[path] ?: error("no key table entry for '$path' (ConfigParser.keyEffects)")
        private val covered = mutableSetOf<String>()

        fun variant(vararg fields: Pair<String, JsonObject>, optional: Set<String> = emptySet()) = buildJsonObject {
            require(fields.all { it.first in keys }) { "a key of $path is not in the parser's key table" }
            fields.forEach { covered += it.first }
            put("type", "object")
            putJsonObject("properties") {
                for ((field, schema) in fields) {
                    val key = join(path, field)
                    put(field, replaced[key] ?: JsonObject(schema + constraints[key].orEmpty()))
                }
            }
            putJsonArray("required") { fields.filter { it.first !in optional }.forEach { add(JsonPrimitive(it.first)) } }
            put("additionalProperties", false)
        }

        fun schema(words: List<String>, variants: List<JsonObject>): JsonObject {
            require(covered == keys.keys) { "the icon's variants do not cover $path's keys exactly" }
            return buildJsonObject {
                putJsonArray("oneOf") {
                    if (words.isNotEmpty()) add(enumOf(words))
                    variants.forEach { add(it) }
                }
            }
        }

        fun pack() = variant("pack" to type("string"), "drawable" to type("string"), "themed" to type("boolean"), optional = setOf("themed"))
    }

    /** `apps[].icon` (AppIconSerializer): a word, a pack icon or an adaptive one. */
    private fun appIconSchema(path: String): JsonObject = IconVariants(path).run {
        val pack = pack()
        val adaptive = variant(
            "scale" to JsonObject(type("number") + range(ConfigValidator.MinIconScale, ConfigValidator.MaxIconScale)),
            "background" to type("string"),
        )
        schema(AppIconSerializer.Words, listOf(pack, adaptive))
    }

    /** `tags[].icon` (TagIconSerializer): a pack icon or text, what the tag's picker offers. */
    private fun tagIconSchema(path: String): JsonObject = IconVariants(path).run {
        val pack = pack()
        val text = variant("text" to type("string"))
        schema(emptyList(), listOf(pack, text))
    }

    /** The enums with a field-naming serializer, by the serial name of its descriptor; their values come from it. */
    private val fieldEnums: Map<String, FieldEnumSerializer<*>> =
        listOf(
            GlassContrastSerializer, SearchBarPositionInSearchSerializer, SearchResultLayoutSerializer,
            ThemeModeSerializer, ThemeColorsSerializer, ThemeShapesSerializer, ThemeTypographySerializer,
            StatusBarIconsSerializer, NavigationBarIconsSerializer,
            AppVisibilitySerializer,
        )
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
        "apps[].icon.background" to buildJsonObject {
            putJsonArray("oneOf") {
                add(enumOf(AppIconSerializer.BackgroundWords))
                add(buildJsonObject {
                    put("type", "string")
                    put("pattern", AppIconSerializer.colourRegex.pattern)
                    put("description", "A colour as #RRGGBB or #AARRGGBB.")
                })
            }
        },
    )

    /** Limits added to a leaf's type; [ConfigSchemaTest] breaks each one and expects the parser to object. */
    internal val constraints: Map<String, Map<String, JsonElement>> = mapOf(
        // The steps the settings screen offers (#3 slice 1): 32, 40, 48, 56, 64.
        "icons.size" to range(IconDefaults.MinSize, IconDefaults.MaxSize) +
            mapOf("multipleOf" to JsonPrimitive(IconDefaults.SizeStep)),
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
        "search.frequentlyUsedRows" to range(SearchDefaults.MinFrequentlyUsedRows, SearchDefaults.MaxFrequentlyUsedRows),
        "search.transliterator" to pattern(ConfigValidator.transliteratorIdRegex),
        "search.actions[].url" to mapOf("pattern" to JsonPrimitive(literal(SearchActionTypes.QueryPlaceholder))),
        "search.actions[].package" to packageNameLimits(),
        // #3 slice 4.
        "apps" to maxItems(ConfigValidator.MaxApps),
        "apps[].packageName" to packageNameLimits(),
        "apps[].activity" to pattern(ConfigValidator.activityNameRegex) +
            mapOf("maxLength" to JsonPrimitive(ConfigValidator.MaxPackageNameLength)),
        // Empty and blank are refused by the pattern, which needs one character that is not white space.
        "apps[].label" to mapOf(
            "maxLength" to JsonPrimitive(ConfigValidator.MaxLabelLength),
            "pattern" to JsonPrimitive(ConfigValidator.labelCharsPattern),
        ),
        // #3 slice 2.
        "gestures.*.packageName" to packageNameLimits(),
        // #3 slice 4, PR 2. The drawable's pattern bounds its length too.
        "apps[].icon.pack" to packageNameLimits(),
        "apps[].icon.drawable" to pattern(ConfigValidator.drawableRegex),
        // #3 slice 4: tags. A name and a text icon are names, as a label is.
        "tags" to maxItems(ConfigValidator.MaxTags),
        "tags[].name" to mapOf(
            "maxLength" to JsonPrimitive(ConfigValidator.MaxLabelLength),
            "pattern" to JsonPrimitive(ConfigValidator.labelCharsPattern),
        ),
        "tags[].icon.pack" to packageNameLimits(),
        "tags[].icon.drawable" to pattern(ConfigValidator.drawableRegex),
        "tags[].icon.text" to mapOf(
            "maxLength" to JsonPrimitive(ConfigValidator.MaxTagTextLength),
            "pattern" to JsonPrimitive(ConfigValidator.labelCharsPattern),
        ),
        "tags[].apps" to maxItems(ConfigValidator.MaxApps),
        "tags[].apps[].packageName" to packageNameLimits(),
        "tags[].apps[].activity" to pattern(ConfigValidator.activityNameRegex) +
            mapOf("maxLength" to JsonPrimitive(ConfigValidator.MaxPackageNameLength)),
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

package de.mm20.launcher2.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object ConfigParser {
    const val MaxInputBytes = 256 * 1024

    val json: Json = Json {
        allowComments = true
        allowTrailingComma = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Every key of the contract, and what this build does with it (#47).
     *
     * Two questions in one table on purpose. The spelling check that used to
     * live here answered only "is this key known"; a separate list of inert
     * keys beside it would be optional, and optional lists rot. Here a new key
     * cannot be added without a [KeyEffect], because the entry is required to
     * compile - that property is the whole point, not the current contents.
     *
     * Keys are classified by tracing them to a consumer, not by intent. The
     * classification is asserted against [ConfigMutation] in
     * `ConfigParserTest`, so a section that gains or loses a mutation cannot
     * drift away from this table unnoticed.
     */
    private val GridItemKeys: Map<String, KeyEffect> = listOf(
        "id", "widget", "x", "y", "w", "h", "profile", "borderless", "background", "themeColors",
    ).associateWith { KeyEffect.Applied }

    internal val keyEffects: Map<String, Map<String, KeyEffect>> = mapOf(
        "" to mapOf(
            "schemaVersion" to KeyEffect.Applied,
            "icons" to KeyEffect.Applied,
            "appearance" to KeyEffect.Applied,
            "home" to KeyEffect.Applied,
        ),
        "icons" to mapOf(
            "themed" to KeyEffect.Applied,
            "enforceThemed" to KeyEffect.Applied,
            "pack" to KeyEffect.Applied,
        ),
        "appearance" to mapOf(
            // No sub-table on purpose: its keys mean nothing any more, so
            // they are neither spell-checked nor validated, and the section
            // is reported once (#73).
            "transparency" to KeyEffect.Inert(
                "replaced by appearance.glass (#24); the file no longer feeds the transparency scheme",
            ),
            "glass" to KeyEffect.Applied,
            "wallpaper" to KeyEffect.Applied,
        ),
        "appearance.glass" to mapOf(
            "blur" to KeyEffect.Applied,
            "tint" to KeyEffect.Applied,
            "radius" to KeyEffect.Applied,
            "contrast" to KeyEffect.Applied,
            "wallpaperBlur" to KeyEffect.Applied,
            "searchWallpaperBlur" to KeyEffect.Applied,
        ),
        "appearance.wallpaper" to mapOf(
            "image" to KeyEffect.Applied,
            "target" to KeyEffect.Applied,
        ),
        "home" to mapOf(
            "searchBar" to KeyEffect.Applied,
            "favorites" to KeyEffect.Applied,
            "widgets" to KeyEffect.Applied,
            "grid" to KeyEffect.Applied,
        ),
        "home.searchBar" to mapOf(
            "position" to KeyEffect.Applied,
        ),
        "home.favorites[]" to mapOf(
            "packageName" to KeyEffect.Applied,
            "profile" to KeyEffect.Applied,
        ),
        "home.widgets" to mapOf(
            "enabled" to KeyEffect.Applied,
        ),
        "home.grid" to mapOf(
            "columns" to KeyEffect.Applied,
            "locked" to KeyEffect.Applied,
            "layouts" to KeyEffect.Applied,
            "labels" to KeyEffect.Applied,
        ),
        "home.grid.layouts" to GridLayouts.All.associateWith { KeyEffect.Applied },
        "home.grid.layouts.phone" to mapOf("items" to KeyEffect.Applied),
        "home.grid.layouts.fold" to mapOf("items" to KeyEffect.Applied),
        "home.grid.layouts.phone.items[]" to GridItemKeys,
        "home.grid.layouts.fold.items[]" to GridItemKeys,
    )

    private val knownKeys: Map<String, Set<String>> = keyEffects.mapValues { it.value.keys }

    fun parse(input: String): ConfigParseResult {
        if (input.toByteArray(Charsets.UTF_8).size > MaxInputBytes) {
            return ConfigParseResult(
                config = null,
                diagnostics = listOf(
                    Diagnostic(
                        Severity.Error,
                        "input-too-large",
                        "",
                        "Input exceeds the maximum size of $MaxInputBytes bytes",
                    )
                ),
            )
        }

        val root = try {
            json.parseToJsonElement(input)
        } catch (e: SerializationException) {
            return ConfigParseResult(
                config = null,
                diagnostics = listOf(
                    Diagnostic(
                        Severity.Error,
                        "malformed-json",
                        "",
                        "Malformed JSON: ${e.message ?: "parse error"}",
                    )
                ),
            )
        } catch (e: IllegalArgumentException) {
            return ConfigParseResult(
                config = null,
                diagnostics = listOf(
                    Diagnostic(
                        Severity.Error,
                        "malformed-json",
                        "",
                        "Malformed JSON: ${e.message ?: "parse error"}",
                    )
                ),
            )
        }

        if (root !is JsonObject) {
            return ConfigParseResult(
                config = null,
                diagnostics = listOf(
                    Diagnostic(
                        Severity.Error,
                        "malformed-json",
                        "",
                        "Config document must be a JSON object",
                    )
                ),
            )
        }

        val versionDiagnostics = mutableListOf<Diagnostic>()
        val versionElement = root["schemaVersion"]
        val version = (versionElement as? JsonPrimitive)?.intOrNull
        when {
            versionElement == null -> {
                versionDiagnostics += Diagnostic(
                    Severity.Error,
                    "missing-schema-version",
                    "schemaVersion",
                    "Missing required key 'schemaVersion'",
                )
            }

            version == null -> {
                versionDiagnostics += Diagnostic(
                    Severity.Error,
                    "invalid-schema-version",
                    "schemaVersion",
                    "'schemaVersion' must be an integer",
                )
            }

            version > ConfigMigrations.currentSchemaVersion -> {
                versionDiagnostics += Diagnostic(
                    Severity.Error,
                    "unsupported-schema-version",
                    "schemaVersion",
                    "Schema version $version is newer than the supported version " +
                            "${ConfigMigrations.currentSchemaVersion}",
                )
            }

            !ConfigMigrations.canMigrate(version) -> {
                versionDiagnostics += Diagnostic(
                    Severity.Error,
                    "unsupported-schema-version",
                    "schemaVersion",
                    "No migration path from schema version $version to " +
                            "${ConfigMigrations.currentSchemaVersion}",
                )
            }
        }
        if (version == null || versionDiagnostics.isNotEmpty()) {
            return ConfigParseResult(config = null, diagnostics = versionDiagnostics)
        }

        val document = ConfigMigrations.migrate(version, root)

        val unknownKeyDiagnostics = mutableListOf<Diagnostic>()
        val sanitized = dropUnknownLayouts(document, unknownKeyDiagnostics)
        unknownKeyDiagnostics += diagnoseKeys(sanitized)

        val config = try {
            json.decodeFromJsonElement(LauncherConfig.serializer(), sanitized)
        } catch (e: SerializationException) {
            return ConfigParseResult(
                config = null,
                diagnostics = unknownKeyDiagnostics + Diagnostic(
                    Severity.Error,
                    "decode-failed",
                    "",
                    "Document does not match the schema: ${e.message ?: "decode error"}",
                ),
            )
        } catch (e: IllegalArgumentException) {
            return ConfigParseResult(
                config = null,
                diagnostics = unknownKeyDiagnostics + Diagnostic(
                    Severity.Error,
                    "decode-failed",
                    "",
                    "Document does not match the schema: ${e.message ?: "decode error"}",
                ),
            )
        }

        val validationDiagnostics = ConfigValidator.validate(config)
        return ConfigParseResult(
            config = config,
            diagnostics = unknownKeyDiagnostics + validationDiagnostics,
        )
    }

    /**
     * Removes entries of `home.grid.layouts` whose key is not a layout this
     * build has, reporting each one as `unknown-layout`.
     *
     * A map decodes every key it finds, so a layout a later build introduced
     * would otherwise ride into state under a name nothing renders. Dropping
     * it keeps the rest of the document, including the layouts this build
     * does render. Runs before the key walk so the entry is reported once,
     * as what it is, and not also as an unknown key.
     */
    private fun dropUnknownLayouts(
        document: JsonObject,
        out: MutableList<Diagnostic>,
    ): JsonObject {
        val home = document["home"] as? JsonObject ?: return document
        val grid = home["grid"] as? JsonObject ?: return document
        val layouts = grid["layouts"] as? JsonObject ?: return document
        val unknown = layouts.keys.filter { it !in GridLayouts.All }
        if (unknown.isEmpty()) return document
        for (key in unknown) {
            out += Diagnostic(
                Severity.Warning,
                "unknown-layout",
                "home.grid.layouts.$key",
                "Unknown layout '$key' is ignored; this build renders ${GridLayouts.All.joinToString(" and ")}",
            )
        }
        return buildJsonObject {
            for ((k, v) in document) if (k != "home") put(k, v)
            put("home", buildJsonObject {
                for ((k, v) in home) if (k != "grid") put(k, v)
                put("grid", buildJsonObject {
                    for ((k, v) in grid) if (k != "layouts") put(k, v)
                    put("layouts", buildJsonObject {
                        for ((k, v) in layouts) if (k in GridLayouts.All) put(k, v)
                    })
                })
            })
        }
    }

    /**
     * Walks [document] against [effects] (the contract table by default) and
     * reports every unknown key and every inert key. Takes the table as a
     * parameter so the inert-key mechanism stays testable while the contract
     * itself has no inert key.
     */
    internal fun diagnoseKeys(
        document: JsonObject,
        effects: Map<String, Map<String, KeyEffect>> = keyEffects,
    ): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        collectUnknownKeys(document, "", "", effects, out)
        return out
    }

    private fun collectUnknownKeys(
        element: JsonElement,
        canonicalPath: String,
        reportPath: String,
        table: Map<String, Map<String, KeyEffect>>,
        out: MutableList<Diagnostic>,
    ) {
        when (element) {
            is JsonObject -> {
                val effects = table[canonicalPath]
                if (effects != null) {
                    for (key in element.keys) {
                        val path = if (reportPath.isEmpty()) key else "$reportPath.$key"
                        when (val effect = effects[key]) {
                            null -> out += Diagnostic(
                                Severity.Warning,
                                "unknown-key",
                                path,
                                "Unknown key '$path' is ignored",
                            )

                            // Reported because it is present, not because it
                            // changed: a key that is inert stays inert on the
                            // second reload, when the differ produces no
                            // mutation for it and nothing else would speak up.
                            is KeyEffect.Inert -> out += Diagnostic(
                                Severity.Warning,
                                "inert-key",
                                path,
                                "'$path' is accepted but this build does not act on it as specified: ${effect.reason}",
                            )

                            KeyEffect.Applied -> Unit
                        }
                    }
                }
                for ((key, value) in element) {
                    val childCanonical = if (canonicalPath.isEmpty()) key else "$canonicalPath.$key"
                    val childReport = if (reportPath.isEmpty()) key else "$reportPath.$key"
                    collectUnknownKeys(value, childCanonical, childReport, table, out)
                }
            }

            is JsonArray -> {
                element.forEachIndexed { index, item ->
                    collectUnknownKeys(
                        item,
                        "$canonicalPath[]",
                        "$reportPath[$index]",
                        table,
                        out,
                    )
                }
            }

            else -> Unit
        }
    }
}

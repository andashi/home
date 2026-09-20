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

    private val knownKeys: Map<String, Set<String>> = mapOf(
        "" to setOf("schemaVersion", "icons", "appearance", "home"),
        "icons" to setOf("themed", "enforceThemed", "pack"),
        "appearance" to setOf("transparency", "wallpaper"),
        "appearance.transparency" to setOf("name", "background", "surface", "elevatedSurface"),
        "appearance.wallpaper" to setOf("image", "target"),
        "home" to setOf("searchBar", "dock", "widgets"),
        "home.searchBar" to setOf("position"),
        "home.dock" to setOf("enabled", "favorites"),
        "home.dock.favorites[]" to setOf("packageName", "profile"),
        "home.widgets" to setOf("enabled", "widgets"),
    )

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
        collectUnknownKeys(document, "", "", unknownKeyDiagnostics)
        val sanitized = dropUnknownWidgets(document, unknownKeyDiagnostics)

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
     * Removes entries of `home.widgets.widgets` that name a widget this build
     * does not have, reporting each one.
     *
     * An unknown *key* is already tolerated, but an unknown *value* is not:
     * this parser deliberately does not set `coerceInputValues`, so a single
     * `"widgets": ["weather"]` left over from before that widget was removed
     * would fail the decode and take the zone's entire configuration with it —
     * wallpaper, dock, icons and all. Dropping the entry keeps the rest of the
     * document, which is the behaviour a removal should have.
     *
     * Scalar enums stay strict on purpose. A bad `searchBar.position` is a typo
     * with no sensible fallback, and the objects that held removed scalars
     * (`home.clock`) leave the contract whole, which makes them unknown keys.
     */
    private fun dropUnknownWidgets(
        document: JsonObject,
        out: MutableList<Diagnostic>,
    ): JsonObject {
        val home = document["home"] as? JsonObject ?: return document
        val widgets = home["widgets"] as? JsonObject ?: return document
        val list = widgets["widgets"] as? JsonArray ?: return document

        val known = BuiltinWidget.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        }
        val kept = list.filterIndexed { index, entry ->
            val name = (entry as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (name != null && name in known) return@filterIndexed true
            out += Diagnostic(
                Severity.Warning,
                "unknown-widget",
                "home.widgets.widgets[$index]",
                "Unknown widget '${name ?: entry}' is ignored",
            )
            false
        }
        if (kept.size == list.size) return document

        return buildJsonObject {
            for ((k, v) in document) if (k != "home") put(k, v)
            put("home", buildJsonObject {
                for ((k, v) in home) if (k != "widgets") put(k, v)
                put("widgets", buildJsonObject {
                    for ((k, v) in widgets) if (k != "widgets") put(k, v)
                    put("widgets", JsonArray(kept))
                })
            })
        }
    }

    private fun collectUnknownKeys(
        element: JsonElement,
        canonicalPath: String,
        reportPath: String,
        out: MutableList<Diagnostic>,
    ) {
        when (element) {
            is JsonObject -> {
                val known = knownKeys[canonicalPath]
                if (known != null) {
                    for (key in element.keys) {
                        if (key !in known) {
                            out += Diagnostic(
                                Severity.Warning,
                                "unknown-key",
                                if (reportPath.isEmpty()) key else "$reportPath.$key",
                                "Unknown key '${if (reportPath.isEmpty()) key else "$reportPath.$key"}' is ignored",
                            )
                        }
                    }
                }
                for ((key, value) in element) {
                    val childCanonical = if (canonicalPath.isEmpty()) key else "$canonicalPath.$key"
                    val childReport = if (reportPath.isEmpty()) key else "$reportPath.$key"
                    collectUnknownKeys(value, childCanonical, childReport, out)
                }
            }

            is JsonArray -> {
                element.forEachIndexed { index, item ->
                    collectUnknownKeys(
                        item,
                        "$canonicalPath[]",
                        "$reportPath[$index]",
                        out,
                    )
                }
            }

            else -> Unit
        }
    }
}

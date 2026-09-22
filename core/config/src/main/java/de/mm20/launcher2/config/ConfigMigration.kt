package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonObject

interface ConfigMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(document: JsonObject): JsonObject
}

object ConfigMigrations {
    private val migrations: List<ConfigMigration> = listOf(Migration1To2)

    val currentSchemaVersion: Int = 2

    fun canMigrate(version: Int): Boolean {
        if (version > currentSchemaVersion) return false
        var v = version
        while (v < currentSchemaVersion) {
            val step = migrations.firstOrNull { it.fromVersion == v } ?: return false
            v = step.toVersion
        }
        return true
    }

    fun migrate(version: Int, document: JsonObject): JsonObject {
        require(canMigrate(version)) { "No migration path from schema version $version" }
        var v = version
        var doc = document
        while (v < currentSchemaVersion) {
            val step = migrations.first { it.fromVersion == v }
            doc = step.migrate(doc)
            v = step.toVersion
        }
        return doc
    }
}

/**
 * Schema 1 -> 2 (ADR 0001 revised, #23): the dock band became the favorites
 * widget on the grid, so `home.dock` goes and its `favorites` list moves up
 * to `home.favorites`; `home.dock.enabled` has no successor (the widget is on
 * the grid when a layout contains it). `home.widgets.widgets` listed built-in
 * widgets, of which only `apps` was left; the grid names widgets per item, so
 * the list goes too and `home.widgets` keeps only `enabled`.
 *
 * Pure: the same v1 tree always yields the same v2 tree, and keys the
 * migration does not know pass through untouched so that a document ahead of
 * this build is not damaged on the way.
 */
internal object Migration1To2 : ConfigMigration {
    override val fromVersion = 1
    override val toVersion = 2

    override fun migrate(document: JsonObject): JsonObject {
        TODO("PR 3: dock.favorites -> favorites, drop dock and widgets.widgets, bump schemaVersion")
    }
}

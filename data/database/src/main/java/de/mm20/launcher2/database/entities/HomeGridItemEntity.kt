package de.mm20.launcher2.database.entities

import androidx.room.Entity

/**
 * One item of the single-page home grid (ADR 0001, revised 2026-09-22).
 *
 * The row mirrors one entry of `home.grid.layouts.<layout>.items[]` in the
 * config document, keyed by the same stable [id], plus the one thing the
 * config must never carry: [appWidgetId], the AppWidget host's integer id,
 * which is local to this device and this profile. [position] is the index in
 * the config array so that writing the grid back produces the same order the
 * file had.
 *
 * This is deliberately not an extension of `Widget`: that table's repository
 * replaces a whole parent scope by index on every write, which is the wrong
 * shape for a grid whose items are patched one at a time.
 */
@Entity(tableName = "HomeGridItem", primaryKeys = ["layout", "id"])
data class HomeGridItemEntity(
    /** `phone` or `fold`; the layouts a device uses are disjoint sets. */
    val layout: String,
    /** The config item id, stable across devices and reloads. */
    val id: String,
    /** `favorites`, or a flattened `ComponentName` (`pkg/cls`) of an AppWidget provider. */
    val widget: String,
    /** `personal`, `work` or `private` for AppWidgets; null for the favorites widget. */
    val profile: String?,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** Device-local AppWidget host id; null until bound, never written to the config. */
    val appWidgetId: Int?,
    /** JSON of the per-item options (borderless, background, themeColors); null means defaults. */
    val config: String?,
    /** Index in the config array, so write-back keeps the file's order. */
    val position: Int,
)

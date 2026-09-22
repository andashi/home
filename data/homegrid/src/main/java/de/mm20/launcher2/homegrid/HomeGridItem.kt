package de.mm20.launcher2.homegrid

import kotlinx.serialization.Serializable

/** The layouts a device can use; a device uses exactly one of them (ADR 0001, D7). */
object HomeGridLayouts {
    const val Phone = "phone"
    const val Fold = "fold"
}

/** Values of [HomeGridItem.widget] that are not AppWidget providers. */
object HomeGridWidgets {
    const val Favorites = "favorites"
}

/**
 * Per-item options of an AppWidget cell, stored as JSON. Every field has a
 * default so that an absent or unreadable config falls back to the defaults
 * instead of dropping the row.
 */
@Serializable
data class HomeGridItemConfig(
    val borderless: Boolean = false,
    val background: Boolean = true,
    val themeColors: Boolean = true,
)

/**
 * One cell group of the home grid, the domain twin of the database row.
 *
 * [id] is the config item id and the identity across devices and reloads;
 * [appWidgetId] is the AppWidget host's integer id, local to this device and
 * profile, and never part of the config document. [position] is the index in
 * the config array so that write-back keeps the file's order.
 */
data class HomeGridItem(
    val layout: String,
    val id: String,
    val widget: String,
    val profile: String? = null,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val appWidgetId: Int? = null,
    val config: HomeGridItemConfig = HomeGridItemConfig(),
    val position: Int,
) {
    val isFavorites: Boolean get() = widget == HomeGridWidgets.Favorites
}

package de.mm20.launcher2.ui.launcher.grid

import de.mm20.launcher2.homegrid.HomeGridItem

/**
 * The label under a grid item (`home.grid.labels`, ADR 0004): the providing
 * app's name, as the reference does ("Kalender", not the widget's own
 * "Upcoming events"), else the AppWidget provider's label. The favorites
 * widget - the dock - never has one. Blank counts as absent.
 */
internal fun gridItemLabel(
    item: HomeGridItem,
    providerLabel: () -> CharSequence?,
    appLabel: () -> CharSequence?,
): String? {
    if (item.isFavorites) return null
    return appLabel()?.toString()?.takeIf { it.isNotBlank() }
        ?: providerLabel()?.toString()?.takeIf { it.isNotBlank() }
}

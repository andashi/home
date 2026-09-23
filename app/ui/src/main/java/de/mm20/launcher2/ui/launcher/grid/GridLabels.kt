package de.mm20.launcher2.ui.launcher.grid

import de.mm20.launcher2.homegrid.HomeGridItem

/**
 * The label under a grid item (`home.grid.labels`, ADR 0004): the AppWidget
 * provider's label, else the providing app's name. The favorites widget - the
 * dock - never has one. Blank counts as absent.
 */
internal fun gridItemLabel(
    item: HomeGridItem,
    providerLabel: () -> CharSequence?,
    appLabel: () -> CharSequence?,
): String? = TODO()

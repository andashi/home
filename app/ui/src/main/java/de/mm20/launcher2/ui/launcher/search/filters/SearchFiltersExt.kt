package de.mm20.launcher2.ui.launcher.search.filters

import de.mm20.launcher2.search.SearchFilters

fun SearchFilters.withAllCategories(): SearchFilters {
    return copy(
        apps = true,
        shortcuts = true,
        contacts = true,
    )
}

fun SearchFilters.withOnlyCategory(
    apps: Boolean = false,
    shortcuts: Boolean = false,
    contacts: Boolean = false,
): SearchFilters {
    return copy(
        apps = apps,
        shortcuts = shortcuts,
        contacts = contacts,
    )
}

/**
 * Create a new [SearchFilters] object with the [apps] property update, according to the following rules:
 *  - If all categories are enabled, disable all categories except for apps.
 *  - If apps is the only enabled category, enable all categories.
 *  - Otherwise, toggle the apps category.
 */
fun SearchFilters.toggleApps(): SearchFilters {
    if (allCategoriesEnabled) {
        return withOnlyCategory(apps = true)
    }
    if (apps && enabledCategories == 1) {
        return withAllCategories()
    }

    return copy(apps = !apps)
}

fun SearchFilters.toggleShortcuts(): SearchFilters {
    if (allCategoriesEnabled) {
        return withOnlyCategory(shortcuts = true)
    }
    if (shortcuts && enabledCategories == 1) {
        return withAllCategories()
    }

    return copy(shortcuts = !shortcuts)
}

fun SearchFilters.toggleContacts(): SearchFilters {
    if (allCategoriesEnabled) {
        return withOnlyCategory(contacts = true)
    }
    if (contacts && enabledCategories == 1) {
        return withAllCategories()
    }

    return copy(contacts = !contacts)
}


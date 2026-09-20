package de.mm20.launcher2.search

import de.mm20.launcher2.ktx.toInt
import kotlinx.serialization.Serializable

@Serializable
data class SearchFilters(
    val allowNetwork: Boolean = false,
    val hiddenItems: Boolean = false,
    val apps: Boolean = true,
    val shortcuts: Boolean = true,
    val contacts: Boolean = true,
) {
    private val categories = listOf(apps, shortcuts, contacts)

    val allCategoriesEnabled
        get() = categories.all { it }

    val enabledCategories: Int
        get() = categories.count { it }
}

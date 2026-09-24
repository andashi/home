package de.mm20.launcher2.search

import de.mm20.launcher2.ktx.toInt
import kotlinx.serialization.Serializable

@Serializable
data class SearchFilters(
    val hiddenItems: Boolean = false,
    val apps: Boolean = true,
    val shortcuts: Boolean = true,
    val contacts: Boolean = true,
) {
    // Computed, not stored: a stored list was serialized into the settings
    // file and read back as-is, out of step with the booleans (#108).
    private val categories
        get() = listOf(apps, shortcuts, contacts)

    val allCategoriesEnabled
        get() = categories.all { it }

    val enabledCategories: Int
        get() = categories.count { it }
}

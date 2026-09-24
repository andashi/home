package de.mm20.launcher2.search

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category checks follow the three booleans, whatever the settings file
 * says (#108): the file used to carry a copy of them, read back as-is.
 */
class SearchFiltersTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `the encoded filter carries no copy of its categories`() {
        val encoded = json.encodeToString(SearchFilters.serializer(), SearchFilters())

        assertFalse(encoded, encoded.contains("categories"))
    }

    @Test
    fun `a stale categories list from an older file is ignored`() {
        // Written before seven categories were removed: websites only.
        val legacy = """{"hiddenItems":false,"apps":false,"shortcuts":false,"contacts":false,
            "categories":[false,true,false,false,false,false,false,false,false]}"""

        val decoded = json.decodeFromString(SearchFilters.serializer(), legacy)

        assertFalse(decoded.allCategoriesEnabled)
        assertEquals(0, decoded.enabledCategories)
    }

    /** Control: a consistent file decodes to what it says. */
    @Test
    fun `a current file decodes to its booleans`() {
        val decoded = json.decodeFromString(
            SearchFilters.serializer(),
            """{"hiddenItems":false,"apps":true,"shortcuts":false,"contacts":true}""",
        )

        assertFalse(decoded.allCategoriesEnabled)
        assertEquals(2, decoded.enabledCategories)
        assertTrue(SearchFilters().allCategoriesEnabled)
    }
}

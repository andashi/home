package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `search.defaultFilter` and `search.filterBarItems` (#229): the filters a
 * query starts with, and which filters the bar above the keyboard shows, in
 * order. The same four words for both: `apps`, `shortcuts`, `contacts`,
 * `hidden`. Each list is the whole value, as favorites are.
 */
class SearchFilterKeysTest {

    private fun parse(search: String) = ConfigParser.parse("""{ "schemaVersion": 2, "search": $search }""")

    private fun errors(search: String) = parse(search).diagnostics.filter { it.severity == Severity.Error }.map { it.code to it.path }

    @Test
    fun `both lists parse in the four words`() {
        val search = parse("""{ "defaultFilter": ["apps", "hidden"], "filterBarItems": ["contacts", "apps"] }""").config!!.search!!

        assertEquals(listOf(SearchFilterItem.Apps, SearchFilterItem.Hidden), search.defaultFilter)
        assertEquals(listOf(SearchFilterItem.Contacts, SearchFilterItem.Apps), search.filterBarItems)
        assertTrue(!parse("""{ "filterBarItems": ["calendar"] }""").isSuccess)
    }

    @Test
    fun `a filter named twice is an error in either list`() {
        assertEquals(listOf("invalid-search" to "search.defaultFilter"), errors("""{ "defaultFilter": ["apps", "apps"] }"""))
        assertEquals(listOf("invalid-search" to "search.filterBarItems"), errors("""{ "filterBarItems": ["hidden", "hidden"] }"""))
    }

    /**
     * The launcher never lets the last category go: switching it off switches
     * them all on (SearchFiltersExt). A default filter without apps, shortcuts
     * or contacts is a state the device cannot be in, so the file cannot ask for it.
     */
    @Test
    fun `a default filter with no category is an error, an empty bar is not`() {
        assertEquals(listOf("invalid-search" to "search.defaultFilter"), errors("""{ "defaultFilter": ["hidden"] }"""))
        assertEquals(listOf("invalid-search" to "search.defaultFilter"), errors("""{ "defaultFilter": [] }"""))
        assertEquals(emptyList<Pair<String, String>>(), errors("""{ "filterBarItems": [] }"""))
    }

    /** The default filter is a set: order is no difference. The bar is a list: order is. */
    @Test
    fun `the default filter diffs as a set, the bar as a list`() {
        val current = ConfigState(
            search = SearchState(
                defaultFilter = listOf(SearchFilterItem.Apps, SearchFilterItem.Contacts),
                filterBarItems = listOf(SearchFilterItem.Apps, SearchFilterItem.Contacts),
            ),
        )
        fun diff(search: SearchConfig) = ConfigDiffer.diff(LauncherConfig(2, search = search), current)

        assertEquals(emptyList<ConfigMutation>(), diff(SearchConfig(defaultFilter = listOf(SearchFilterItem.Contacts, SearchFilterItem.Apps))))
        assertEquals(
            listOf(ConfigMutation.SetSearch(SearchConfig(filterBarItems = listOf(SearchFilterItem.Contacts, SearchFilterItem.Apps)))),
            diff(SearchConfig(filterBarItems = listOf(SearchFilterItem.Contacts, SearchFilterItem.Apps))),
        )
    }

    @Test
    fun `both are served back, the default filter in the canonical order`() {
        val state = ConfigState(
            search = SearchState(
                defaultFilter = listOf(SearchFilterItem.Hidden, SearchFilterItem.Apps),
                filterBarItems = listOf(SearchFilterItem.Contacts, SearchFilterItem.Apps),
            ),
        )
        val search = state.toLauncherConfig().search!!

        assertEquals(listOf(SearchFilterItem.Apps, SearchFilterItem.Hidden), search.defaultFilter)
        assertEquals(listOf(SearchFilterItem.Contacts, SearchFilterItem.Apps), search.filterBarItems)
    }

    @Test
    fun `the defaults are the launcher's own`() {
        assertEquals(listOf(SearchFilterItem.Apps, SearchFilterItem.Shortcuts, SearchFilterItem.Contacts), SearchState().defaultFilter)
        assertEquals(SearchFilterItem.entries, SearchState().filterBarItems)
    }
}

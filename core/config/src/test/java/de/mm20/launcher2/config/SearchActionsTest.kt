package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `search.actions` (#106): parse, validate, diff and read back. */
class SearchActionsTest {

    private fun parse(actions: String) =
        ConfigParser.parse("""{ "schemaVersion": 2, "search": { "actions": $actions } }""")

    private fun errors(actions: String, code: String) =
        parse(actions).diagnostics.filter { it.code == code && it.severity == Severity.Error }

    @Test
    fun `every kind of action parses with nothing to report`() {
        val result = parse(
            """[
              { "type": "call" },
              { "type": "websearch" },
              { "type": "url", "label": "Docs", "url": "https://example.org/?q=${'$'}{1}", "package": "org.torproject.torbrowser", "encoding": "form" },
              { "type": "app", "label": "Store", "package": "app.grapheneos.apps" }
            ]"""
        )

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(
            listOf(
                SearchActionConfig("call"),
                SearchActionConfig("websearch"),
                SearchActionConfig("url", "Docs", "https://example.org/?q=${'$'}{1}", "org.torproject.torbrowser", "form"),
                SearchActionConfig("app", "Store", packageName = "app.grapheneos.apps"),
            ),
            result.config?.search?.actions,
        )
    }

    @Test
    fun `an empty list is no actions, and fine`() {
        val result = parse("[]")

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(emptyList<SearchActionConfig>(), result.config?.search?.actions)
    }

    @Test
    fun `a misspelled key in an action is reported at its path`() {
        val result = parse("""[ { "type": "app", "label": "Store", "pakage": "app.grapheneos.apps" } ]""")

        assertTrue(result.diagnostics.any { it.code == "unknown-key" && it.path == "search.actions[0].pakage" })
    }

    @Test
    fun `each broken action is an error at its index`() {
        val cases = mapOf(
            """[ { "type": "carousel" } ]""" to "unknown type",
            """[ { "type": "url", "url": "https://example.org/?q=${'$'}{1}" } ]""" to "url without label",
            """[ { "type": "url", "label": "Docs", "url": "https://example.org/" } ]""" to "url without the query",
            """[ { "type": "url", "label": "Docs" } ]""" to "url without url",
            """[ { "type": "url", "label": "Docs", "url": "https://x/${'$'}{1}", "encoding": "base64" } ]""" to "unknown encoding",
            """[ { "type": "app", "label": "Store" } ]""" to "app without package",
            """[ { "type": "app", "package": "app.grapheneos.apps" } ]""" to "app without label",
        )
        for ((actions, what) in cases) {
            val found = errors(actions, "invalid-search-action")
            assertEquals("$what: $actions", listOf("search.actions[0]"), found.map { it.path })
        }
    }

    @Test
    fun `a malformed package name is reported`() {
        val found = errors("""[ { "type": "app", "label": "Store", "package": "not a package" } ]""", "invalid-package-name")
        assertEquals(listOf("search.actions[0].package"), found.map { it.path })
    }

    @Test
    fun `a duplicate action is an error at the second one`() {
        val found = errors("""[ { "type": "call" }, { "type": "websearch" }, { "type": "call" } ]""", "duplicate-search-action")
        assertEquals(listOf("search.actions[2]"), found.map { it.path })
    }

    @Test
    fun `more than the cap is an error on the list`() {
        val many = (0..ConfigValidator.MaxSearchActions).joinToString(",", "[", "]") {
            """{ "type": "url", "label": "S$it", "url": "https://s$it.example/${'$'}{1}" }"""
        }
        assertEquals(listOf("search.actions"), errors(many, "too-many-search-actions").map { it.path })
    }

    @Test
    fun `a built-in with a field it does not use is a warning`() {
        val warnings = parse("""[ { "type": "call", "label": "Ring" } ]""").diagnostics
            .filter { it.code == "search-action-field-ignored" }
        assertEquals(listOf(Severity.Warning), warnings.map { it.severity })
        assertEquals(listOf("search.actions[0]"), warnings.map { it.path })
    }

    @Test
    fun `actions diff as a list, the default encoding written or not`() {
        val url = SearchActionConfig("url", "Docs", "https://example.org/?q=${'$'}{1}")
        val state = ConfigState(searchActions = listOf(url.copy(encoding = "url")))

        assertEquals(
            emptyList<ConfigMutation>(),
            ConfigDiffer.diff(LauncherConfig(2, search = SearchConfig(actions = listOf(url))), state),
        )
        assertEquals(
            listOf(ConfigMutation.SetSearchActions(emptyList())),
            ConfigDiffer.diff(LauncherConfig(2, search = SearchConfig(actions = emptyList())), state),
        )
    }

    /** Control: a file without `search.actions` leaves the device's list alone. */
    @Test
    fun `no actions in the file is no mutation`() {
        val state = ConfigState(searchActions = listOf(SearchActionConfig("call")))
        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(LauncherConfig(2, search = SearchConfig()), state))
    }

    @Test
    fun `the read-back serves the actions in effect`() {
        val actions = listOf(SearchActionConfig("websearch"))
        assertEquals(actions, ConfigState(searchActions = actions).toLauncherConfig().search?.actions)
    }

    /**
     * #116 review: fields the store ignores must not keep the lists apart,
     * or every reload would rewrite the table.
     */
    @Test
    fun `ignored fields do not keep the lists from converging`() {
        val state = ConfigState(
            searchActions = listOf(SearchActionConfig("call"), SearchActionConfig("app", "Store", packageName = "app.x")),
        )
        val file = listOf(
            SearchActionConfig("call", label = "Ring"),
            SearchActionConfig("app", "Store", url = "https://x/${'$'}{1}", packageName = "app.x", encoding = "form"),
        )

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(LauncherConfig(2, search = SearchConfig(actions = file)), state))
    }

    /**
     * #116 review: a custom intent action a user made on the device reads
     * back as {type: intent, label}. A pulled read-back must apply again, so
     * the type is accepted, with a warning: the file keeps it, it cannot make one.
     */
    @Test
    fun `a read-back intent action is a warning, not an error`() {
        val result = parse("""[ { "type": "intent", "label": "Mine" }, { "type": "websearch" } ]""")

        assertTrue(result.isSuccess)
        val warning = result.diagnostics.single { it.code == "search-action-read-only" }
        assertEquals(Severity.Warning, warning.severity)
        assertEquals("search.actions[0]", warning.path)
    }
}

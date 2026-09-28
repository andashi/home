package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `search.shortcutsExcluded` (#229): the apps whose shortcuts search leaves
 * out, per package and profile, named the way favorites are - a package name
 * for a personal app, `{ packageName, profile }` otherwise. The whole state,
 * as a set.
 */
class ShortcutsExcludedTest {

    private fun parse(list: String) = ConfigParser.parse("""{ "schemaVersion": 2, "search": { "shortcutsExcluded": $list } }""")

    private fun errors(list: String) = parse(list).diagnostics.filter { it.severity == Severity.Error }.map { it.code to it.path }

    @Test
    fun `entries parse in both forms, a package alone meaning the personal app`() {
        assertEquals(
            listOf(Favorite("org.a"), Favorite("org.b", Profile.Work)),
            parse("""["org.a", { "packageName": "org.b", "profile": "work" }]""").config!!.search!!.shortcutsExcluded,
        )
    }

    @Test
    fun `a bad package name or the same app twice is an error`() {
        assertEquals(listOf("invalid-package-name" to "search.shortcutsExcluded[0]"), errors("""["not a package"]"""))
        assertEquals(
            listOf("invalid-search" to "search.shortcutsExcluded[1]"),
            errors("""["org.a", { "packageName": "org.a", "profile": "personal" }]"""),
        )
        assertEquals("another profile is another app", emptyList<Pair<String, String>>(), errors("""["org.a", { "packageName": "org.a", "profile": "work" }]"""))
    }

    @Test
    fun `the list diffs as a set`() {
        val current = ConfigState(shortcutsExcluded = listOf(Favorite("org.a"), Favorite("org.b", Profile.Work)))
        fun diff(list: List<Favorite>) = ConfigDiffer.diff(LauncherConfig(2, search = SearchConfig(shortcutsExcluded = list)), current)

        assertEquals(emptyList<ConfigMutation>(), diff(listOf(Favorite("org.b", Profile.Work), Favorite("org.a"))))
        assertEquals(listOf(ConfigMutation.SetShortcutsExcluded(listOf(Favorite("org.a")))), diff(listOf(Favorite("org.a"))))
    }

    @Test
    fun `it is served back in a canonical order, empty by default`() {
        assertEquals(emptyList<Favorite>(), ConfigState().toLauncherConfig().search?.shortcutsExcluded)
        assertEquals(
            listOf(Favorite("org.a"), Favorite("org.z"), Favorite("org.b", Profile.Work)),
            ConfigState(shortcutsExcluded = listOf(Favorite("org.b", Profile.Work), Favorite("org.z"), Favorite("org.a")))
                .toLauncherConfig().search?.shortcutsExcluded,
        )
    }

    @Test
    fun `the mutation is its own section, applied by the store`() {
        assertTrue(ConfigMutation.SetShortcutsExcluded(emptyList()).section == "search.shortcutsExcluded")
    }
}

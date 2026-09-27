package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `apps` (#3 slice 4): an app's own name and visibility on this launcher.
 * The list is the whole desired state, like `home.favorites`: an app not
 * listed has neither, and a key an entry leaves out is its default.
 */
class AppsConfigTest {

    private fun parse(apps: String) = ConfigParser.parse("""{ "schemaVersion": 2, "apps": $apps }""")

    private fun errorsAt(apps: String, code: String) =
        parse(apps).diagnostics.filter { it.code == code && it.severity == Severity.Error }.map { it.path }

    // ---- parsing ----

    @Test
    fun `an app entry parses with its label, visibility, profile and activity`() {
        val result = parse(
            """[
              { "packageName": "org.thoughtcrime.securesms", "label": "Chat" },
              { "packageName": "com.android.stk", "visibility": "hidden" },
              { "packageName": "app.vanadium.browser", "profile": "work", "visibility": "search-only" },
              { "packageName": "com.example.two", "activity": "com.example.two.SecondActivity", "label": "Two" }
            ]"""
        )

        assertTrue(result.diagnostics.toString(), result.isSuccess)
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(
            listOf(
                AppConfig("org.thoughtcrime.securesms", label = "Chat"),
                AppConfig("com.android.stk", visibility = AppVisibility.Hidden),
                AppConfig("app.vanadium.browser", profile = Profile.Work, visibility = AppVisibility.SearchOnly),
                AppConfig("com.example.two", activity = "com.example.two.SecondActivity", label = "Two"),
            ),
            result.config?.apps,
        )
    }

    /** Always an object: a bare package name would say nothing an entry can mean. */
    @Test
    fun `a bare package name is not an app entry`() {
        assertFalse(parse("""[ "org.thoughtcrime.securesms" ]""").isSuccess)
    }

    @Test
    fun `an unknown visibility fails and names its field`() {
        val result = parse("""[ { "packageName": "com.example", "visibility": "invisible" } ]""")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.toString(), result.diagnostics.any { it.message.contains("apps[].visibility") })
    }

    @Test
    fun `a misspelled key inside an entry is reported at its path`() {
        val result = parse("""[ { "packageName": "com.example", "lable": "Typo" } ]""")

        assertTrue(result.isSuccess)
        assertEquals(listOf("apps[0].lable"), result.diagnostics.filter { it.code == "unknown-key" }.map { it.path })
    }

    // ---- validation: config is untrusted input, and a label ends up on screen ----

    @Test
    fun `a label must be one to a hundred characters`() {
        assertEquals(listOf("apps[0].label"), errorsAt("""[ { "packageName": "com.example", "label": "" } ]""", "invalid-apps"))
        assertEquals(listOf("apps[0].label"), errorsAt("""[ { "packageName": "com.example", "label": "   " } ]""", "invalid-apps"))
        val long = "x".repeat(101)
        assertEquals(listOf("apps[0].label"), errorsAt("""[ { "packageName": "com.example", "label": "$long" } ]""", "invalid-apps"))
        // Control: exactly a hundred is accepted.
        assertTrue(parse("""[ { "packageName": "com.example", "label": "${"x".repeat(100)}" } ]""").isSuccess)
    }

    @Test
    fun `a label with a control character or a line break is refused`() {
        for (bad in listOf("Chat\\u0000", "Two\\nLines", "Tab\\there", "Bell\\u0007", "Line\\u2028Sep")) {
            assertEquals(bad, listOf("apps[0].label"), errorsAt("""[ { "packageName": "com.example", "label": "$bad" } ]""", "invalid-apps"))
        }
    }

    /** Control: letters from any script, digits, spaces, punctuation and emoji are a name. */
    @Test
    fun `ordinary names in any script are accepted`() {
        for (good in listOf("Chat", "Приват", "银行", "Mail & Calendar", "Home ⌂", "Signal 2")) {
            assertTrue(good, parse("""[ { "packageName": "com.example", "label": "$good" } ]""").isSuccess)
        }
    }

    @Test
    fun `an invalid package or activity name fails at its path`() {
        assertEquals(listOf("apps[0].packageName"), errorsAt("""[ { "packageName": "not a package" } ]""", "invalid-package-name"))
        assertEquals(
            listOf("apps[0].activity"),
            errorsAt("""[ { "packageName": "com.example", "activity": "no spaces allowed" } ]""", "invalid-apps"),
        )
    }

    @Test
    fun `the same app twice is an error at the second entry`() {
        assertEquals(
            listOf("apps[1]"),
            errorsAt(
                """[ { "packageName": "com.example", "label": "A" }, { "packageName": "com.example", "label": "B" } ]""",
                "duplicate-app",
            ),
        )
        // Control: the same package in another profile, or as another activity, is another app.
        assertTrue(
            parse(
                """[ { "packageName": "com.example", "label": "A" },
                    { "packageName": "com.example", "profile": "work", "label": "B" },
                    { "packageName": "com.example", "activity": "com.example.Other", "label": "C" } ]"""
            ).isSuccess
        )
    }

    // ---- the differ: the whole desired state ----

    private fun diff(apps: List<AppConfig>, current: List<AppConfig>) =
        ConfigDiffer.diff(LauncherConfig(2, apps = apps), ConfigState(apps = current))

    @Test
    fun `a changed label is a change of the whole list`() {
        val mutations = diff(listOf(AppConfig("com.example", label = "New")), listOf(AppConfig("com.example", label = "Old")))

        assertEquals(listOf(ConfigMutation.SetApps(listOf(AppConfig("com.example", label = "New")))), mutations)
    }

    @Test
    fun `order is not a difference`() {
        val a = AppConfig("com.a", label = "A")
        val b = AppConfig("com.b", visibility = AppVisibility.Hidden)

        assertEquals(emptyList<ConfigMutation>(), diff(listOf(b, a), listOf(a, b)))
    }

    /** An entry that asks for nothing but defaults is the same as no entry. */
    @Test
    fun `an entry of defaults is no customization`() {
        val defaults = listOf(AppConfig("com.a"), AppConfig("com.b", visibility = AppVisibility.Default))

        assertEquals(emptyList<ConfigMutation>(), diff(defaults, emptyList()))
    }

    /** The sharp edge, on purpose: an empty list clears every customization. */
    @Test
    fun `an empty list clears what the device has`() {
        val mutations = diff(emptyList(), listOf(AppConfig("com.a", label = "A")))

        assertEquals(listOf(ConfigMutation.SetApps(emptyList())), mutations)
    }

    /** Absent is unmanaged: a file without `apps` touches nothing. */
    @Test
    fun `a file without apps changes nothing`() {
        val mutations = ConfigDiffer.diff(LauncherConfig(2), ConfigState(apps = listOf(AppConfig("com.a", label = "A"))))

        assertTrue(mutations.none { it is ConfigMutation.SetApps })
    }

    // ---- the read-back ----

    @Test
    fun `the read-back serves the apps, sorted and without defaults`() {
        val state = ConfigState(
            apps = listOf(
                AppConfig("com.b", visibility = AppVisibility.Hidden),
                AppConfig("com.a", label = "A"),
            ),
        )

        assertEquals(
            listOf(AppConfig("com.a", label = "A"), AppConfig("com.b", visibility = AppVisibility.Hidden)),
            state.toLauncherConfig().apps,
        )
    }

    @Test
    fun `the read-back of a device with no customizations serves an empty list`() {
        assertEquals(emptyList<AppConfig>(), ConfigState().toLauncherConfig().apps)
    }
}

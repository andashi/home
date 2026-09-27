package de.mm20.launcher2.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `apps[].icon` (#3 slice 4, PR 2): which icon the launcher draws for an app.
 * Every form the icon picker can produce is here, because `apps` is the whole
 * state: a form the file could not say would be reset by the next apply.
 */
class AppIconConfigTest {

    private fun parse(icon: String) =
        ConfigParser.parse("""{ "schemaVersion": 2, "apps": [ { "packageName": "com.example", "icon": $icon } ] }""")

    private fun iconOf(icon: String): AppIcon? {
        val result = parse(icon)
        assertTrue(result.diagnostics.toString(), result.isSuccess)
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        return result.config?.apps?.single()?.icon
    }

    private fun errorsAt(icon: String) =
        parse(icon).diagnostics.filter { it.code == "invalid-apps" && it.severity == Severity.Error }.map { it.path }

    // ---- the forms ----

    @Test
    fun `the three words are the system icon, the themed icon and the placeholder`() {
        assertEquals(AppIcon.System, iconOf(""""system""""))
        assertEquals(AppIcon.Themed, iconOf(""""themed""""))
        assertEquals(AppIcon.Placeholder, iconOf(""""placeholder""""))
    }

    @Test
    fun `a pack icon is a pack and one of its drawables`() {
        assertEquals(
            AppIcon.Pack("app.lawnchair.lawnicons", "signal"),
            iconOf("""{ "pack": "app.lawnchair.lawnicons", "drawable": "signal" }"""),
        )
    }

    /** A calendar icon is one drawable per day, as the pack's index stores it. */
    @Test
    fun `a calendar icon's drawable is the pack's list of days`() {
        val days = (1..31).joinToString(",") { "calendar_$it" }

        assertEquals(AppIcon.Pack("com.example.pack", days), iconOf("""{ "pack": "com.example.pack", "drawable": "$days" }"""))
    }

    @Test
    fun `an adaptive icon is a scale and a background`() {
        assertEquals(AppIcon.Adaptive(0.7f, IconBackground.Theme), iconOf("""{ "scale": 0.7, "background": "theme" }"""))
        assertEquals(AppIcon.Adaptive(1f, IconBackground.FromIcon), iconOf("""{ "scale": 1, "background": "icon" }"""))
        assertEquals(
            AppIcon.Adaptive(0.7f, IconBackground.Color(0xFFFFFFFF.toInt())),
            iconOf("""{ "scale": 0.7, "background": "#FFFFFF" }"""),
        )
        assertEquals(
            AppIcon.Adaptive(0.7f, IconBackground.Color(0x80102030.toInt())),
            iconOf("""{ "scale": 0.7, "background": "#80102030" }"""),
        )
    }

    // ---- structure: what cannot be an icon at all ----

    @Test
    fun `an unknown word fails and names its field`() {
        val result = parse(""""monochrome"""")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.toString(), result.diagnostics.any { it.message.contains("apps[].icon") })
    }

    @Test
    fun `an object that is neither a pack icon nor an adaptive one fails`() {
        for (bad in listOf(
            """{ "pack": "com.example.pack" }""",
            """{ "drawable": "signal" }""",
            """{ "scale": 0.7 }""",
            """{ "background": "theme" }""",
            """{ "pack": "com.example.pack", "drawable": "signal", "scale": 0.7 }""",
            """{ }""",
            "42",
        )) {
            val result = parse(bad)
            assertFalse(bad, result.isSuccess)
            assertTrue(bad + " " + result.diagnostics, result.diagnostics.any { it.message.contains("apps[].icon") })
        }
    }

    @Test
    fun `a background that is not a word or a colour fails and names its field`() {
        for (bad in listOf("white", "#FFF", "#GGGGGG", "FFFFFF", "#FFFFFFFFFF")) {
            val result = parse("""{ "scale": 0.7, "background": "$bad" }""")
            assertFalse(bad, result.isSuccess)
            assertTrue(bad + " " + result.diagnostics, result.diagnostics.any { it.message.contains("apps[].icon.background") })
        }
    }

    @Test
    fun `a misspelled key inside an icon is reported at its path`() {
        val result = parse("""{ "pack": "com.example.pack", "drawable": "signal", "themed": true }""")

        assertEquals(listOf("apps[0].icon.themed"), result.diagnostics.filter { it.code == "unknown-key" }.map { it.path })
    }

    // ---- validation: untrusted input that reaches Resources.getIdentifier ----

    @Test
    fun `the pack is a package name`() {
        val result = parse("""{ "pack": "not a package", "drawable": "signal" }""")

        assertEquals(
            listOf("apps[0].icon.pack"),
            result.diagnostics.filter { it.code == "invalid-package-name" && it.severity == Severity.Error }.map { it.path },
        )
    }

    @Test
    fun `a drawable is a resource name, or a list of at most 31 of them`() {
        for (bad in listOf("", "1signal", "sig nal", "../signal", "signal/x", "android:drawable/x", "a,,b", "a,", "x".repeat(101))) {
            assertEquals(bad, listOf("apps[0].icon.drawable"), errorsAt("""{ "pack": "com.example.pack", "drawable": "$bad" }"""))
        }
        val tooMany = (1..32).joinToString(",") { "d$it" }
        assertEquals(listOf("apps[0].icon.drawable"), errorsAt("""{ "pack": "com.example.pack", "drawable": "$tooMany" }"""))
        // Control: the forms packs use.
        for (good in listOf("signal", "_x", "com_android_chrome", "Clock2", "x".repeat(100))) {
            assertEquals(good, emptyList<String>(), errorsAt("""{ "pack": "com.example.pack", "drawable": "$good" }"""))
        }
    }

    @Test
    fun `a scale is between a half and one and a half`() {
        assertEquals(listOf("apps[0].icon.scale"), errorsAt("""{ "scale": 0.49, "background": "theme" }"""))
        assertEquals(listOf("apps[0].icon.scale"), errorsAt("""{ "scale": 1.51, "background": "theme" }"""))
        // Control: the picker's presets, 48/44 and 48/38 among them.
        for (good in listOf(0.5, 0.7, 1.0, 48.0 / 44, 48.0 / 38, 1.5)) {
            assertEquals("$good", emptyList<String>(), errorsAt("""{ "scale": $good, "background": "theme" }"""))
        }
    }

    // ---- the whole state ----

    /** An entry with only an icon asks for something, so it is a customization. */
    @Test
    fun `an icon alone is a customization`() {
        val app = AppConfig("com.a", icon = AppIcon.Themed)

        assertEquals(listOf(app), listOf(app).normalizedApps())
    }

    @Test
    fun `a changed icon is a change of the whole list`() {
        val mutations = ConfigDiffer.diff(
            LauncherConfig(2, apps = listOf(AppConfig("com.a", icon = AppIcon.System))),
            ConfigState(apps = listOf(AppConfig("com.a", icon = AppIcon.Themed))),
        )

        assertEquals(listOf(ConfigMutation.SetApps(listOf(AppConfig("com.a", icon = AppIcon.System)))), mutations)
    }

    /**
     * The two background words are stored as two colour values; a file that
     * writes those values means the words, so the two are no difference.
     */
    @Test
    fun `a colour equal to a stored word is that word`() {
        val asColour = listOf(
            AppConfig("com.a", icon = AppIcon.Adaptive(0.7f, IconBackground.Color(0))),
            AppConfig("com.b", icon = AppIcon.Adaptive(0.7f, IconBackground.Color(1))),
        )
        val asWords = listOf(
            AppConfig("com.a", icon = AppIcon.Adaptive(0.7f, IconBackground.Theme)),
            AppConfig("com.b", icon = AppIcon.Adaptive(0.7f, IconBackground.FromIcon)),
        )

        assertEquals(asWords, asColour.normalizedApps())
    }

    // ---- the read-back ----

    /** What the device reads back is what a file says, so a write-back can be pushed again unchanged. */
    @Test
    fun `every form reads back as it is written`() {
        val icons = listOf(
            """"system"""",
            """"themed"""",
            """"placeholder"""",
            """{"pack":"app.lawnchair.lawnicons","drawable":"signal"}""",
            """{"scale":0.7,"background":"theme"}""",
            """{"scale":1.2631578,"background":"icon"}""",
            """{"scale":0.7,"background":"#FFFFFF"}""",
            """{"scale":0.7,"background":"#80102030"}""",
        )
        for (icon in icons) {
            val parsed = iconOf(icon)
            assertEquals(icon, icon, Json.encodeToString(AppIconSerializer, parsed!!))
        }
    }
}

package de.mm20.launcher2.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tags` (#3 slice 4): which apps carry which tags, and each tag's icon. The
 * whole desired state of the apps' tags, as `apps` is of their names: a tag
 * not listed tags no app, and `"tags": []` untags every app. The contacts and
 * shortcuts a tag holds are the phone's, and the file never touches them.
 */
class TagConfigTest {

    private fun parse(tags: String) = ConfigParser.parse("""{ "schemaVersion": 2, "tags": $tags }""")

    private fun tagsOf(tags: String): List<TagConfig>? {
        val result = parse(tags)
        assertTrue(result.diagnostics.toString(), result.isSuccess)
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        return result.config?.tags
    }

    private fun errors(tags: String) = parse(tags).diagnostics.filter { it.severity == Severity.Error }.map { it.code to it.path }

    // ---- the forms ----

    @Test
    fun `a tag is a name, an icon and its apps, an app by package or in full`() {
        assertEquals(
            listOf(
                TagConfig(
                    name = "Music",
                    icon = TagIcon.Text("🎵"),
                    apps = listOf(TagApp("org.a"), TagApp("com.b", Profile.Work, "com.b.Second")),
                ),
            ),
            tagsOf("""[{ "name": "Music", "icon": { "text": "🎵" }, "apps": ["org.a", { "packageName": "com.b", "profile": "work", "activity": "com.b.Second" }] }]"""),
        )
    }

    @Test
    fun `a tag's icon can be a pack's drawable, its unthemed variant too`() {
        assertEquals(
            TagIcon.Pack("app.lawnchair.lawnicons", "music", themed = false),
            tagsOf("""[{ "name": "Music", "icon": { "pack": "app.lawnchair.lawnicons", "drawable": "music", "themed": false } }]""")?.single()?.icon,
        )
    }

    @Test
    fun `an empty list is a managed nothing, an absent one is unmanaged`() {
        assertEquals(emptyList<TagConfig>(), tagsOf("[]"))
        assertEquals(null, ConfigParser.parse("""{ "schemaVersion": 2 }""").config?.tags)
        assertEquals("a tag without apps", emptyList<TagApp>(), tagsOf("""[{ "name": "Later" }]""")?.single()?.apps)
    }

    @Test
    fun `every form reads back as it is written`() {
        val tags = listOf(
            """[{"name":"Music","icon":{"text":"🎵"},"apps":["org.a"]}]""",
            """[{"name":"Music","icon":{"pack":"app.lawnchair.lawnicons","drawable":"music"}}]""",
            """[{"name":"Music","icon":{"pack":"app.lawnchair.lawnicons","drawable":"music","themed":false}}]""",
            """[{"name":"Work","apps":[{"packageName":"com.b","profile":"work","activity":"com.b.Second"}]}]""",
        )
        for (text in tags) {
            val parsed = tagsOf(text)!!
            assertEquals(text, Json.parseToJsonElement(text), ConfigParser.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(TagConfig.serializer()), parsed))
        }
    }

    @Test
    fun `an icon that is neither a pack's drawable nor text fails`() {
        for (icon in listOf("\"themed\"", """{ "scale": 0.7, "background": "theme" }""", """{ "text": "x", "pack": "a.b", "drawable": "c" }""")) {
            assertTrue(icon, !parse("""[{ "name": "Music", "icon": $icon }]""").isSuccess)
        }
    }

    // ---- validation: untrusted input that ends up on screen ----

    @Test
    fun `a name is 1 to 100 characters, not blank, without control characters`() {
        assertEquals(listOf("invalid-tags" to "tags[0].name"), errors("""[{ "name": " " }]"""))
        assertEquals(listOf("invalid-tags" to "tags[0].name"), errors("""[{ "name": "a\u0007b" }]"""))
        assertEquals(listOf("invalid-tags" to "tags[0].name"), errors("""[{ "name": "${"x".repeat(101)}" }]"""))
        assertEquals(emptyList<Pair<String, String>>(), errors("""[{ "name": "${"x".repeat(100)}" }]"""))
    }

    @Test
    fun `a text icon is 1 to 16 characters without control characters`() {
        assertEquals(listOf("invalid-tags" to "tags[0].icon.text"), errors("""[{ "name": "a", "icon": { "text": "" } }]"""))
        assertEquals(listOf("invalid-tags" to "tags[0].icon.text"), errors("""[{ "name": "a", "icon": { "text": "${"x".repeat(17)}" } }]"""))
        assertEquals(listOf("invalid-tags" to "tags[0].icon.text"), errors("""[{ "name": "a", "icon": { "text": "\n" } }]"""))
    }

    @Test
    fun `a pack icon's pack and drawable are checked as an app's are`() {
        assertEquals(
            listOf("invalid-package-name" to "tags[0].icon.pack", "invalid-tags" to "tags[0].icon.drawable"),
            errors("""[{ "name": "a", "icon": { "pack": "not a package", "drawable": "../x" } }]"""),
        )
    }

    @Test
    fun `the same name twice is an error, as is the same app twice in a tag`() {
        assertEquals(listOf("duplicate-tag" to "tags[1]"), errors("""[{ "name": "Music" }, { "name": "Music" }]"""))
        assertEquals(
            "a package written two ways is the same app",
            listOf("duplicate-app" to "tags[0].apps[1]"),
            errors("""[{ "name": "a", "apps": ["org.a", { "packageName": "org.a", "profile": "personal" }] }]"""),
        )
        assertEquals("another profile is another app", emptyList<Pair<String, String>>(), errors("""[{ "name": "a", "apps": ["org.a", { "packageName": "org.a", "profile": "work" }] }]"""))
    }

    @Test
    fun `an app's package name is checked`() {
        assertEquals(listOf("invalid-package-name" to "tags[0].apps[0]"), errors("""[{ "name": "a", "apps": ["not a package"] }]"""))
    }

    // ---- compared and read back ----

    /** Order is not meaning: a tag's apps are a set, the tags a set by name. */
    @Test
    fun `the same tags in another order are no change`() {
        val file = tagsOf("""[{ "name": "B", "apps": ["org.b", "org.a"] }, { "name": "A", "icon": { "text": "x" } }]""")!!
        val device = listOf(TagConfig("A", TagIcon.Text("x")), TagConfig("B", apps = listOf(TagApp("org.a"), TagApp("org.b"))))

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(LauncherConfig(2, tags = file), ConfigState(tags = device)))
        assertEquals(
            listOf(ConfigMutation.SetTags(file.normalizedTags())),
            ConfigDiffer.diff(LauncherConfig(2, tags = file), ConfigState(tags = device.dropLast(1))),
        )
    }

    @Test
    fun `the tags read back from the state`() {
        val tags = listOf(TagConfig("Music", TagIcon.Text("🎵"), listOf(TagApp("org.a"))))

        assertEquals(tags, ConfigState(tags = tags).toLauncherConfig().tags)
    }
}

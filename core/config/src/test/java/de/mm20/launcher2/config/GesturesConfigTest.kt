package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `gestures` (#3 slice 2): parse, validate, diff and read back. */
class GesturesConfigTest {

    private val everyGesture = """
        {
          "schemaVersion": 2,
          "gestures": {
            "swipeDown": "notifications",
            "swipeUp": "search",
            "swipeLeft": { "packageName": "com.android.dialer" },
            "swipeRight": { "packageName": "com.example.mail", "profile": "work" },
            "doubleTap": "screen-lock",
            "longPress": "launcher-settings",
            "homeButton": "none"
          }
        }
    """.trimIndent()

    private fun parse(gestures: String) = ConfigParser.parse("""{ "schemaVersion": 2, "gestures": $gestures }""")

    @Test
    fun `every gesture parses, actions by name and apps as objects, with nothing to report`() {
        val result = ConfigParser.parse(everyGesture)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(
            GesturesConfig(
                swipeDown = GestureConfig.Action(GestureActionName.Notifications),
                swipeUp = GestureConfig.Action(GestureActionName.Search),
                swipeLeft = GestureConfig.App(Favorite("com.android.dialer")),
                swipeRight = GestureConfig.App(Favorite("com.example.mail", Profile.Work)),
                doubleTap = GestureConfig.Action(GestureActionName.ScreenLock),
                longPress = GestureConfig.Action(GestureActionName.LauncherSettings),
                homeButton = GestureConfig.Action(GestureActionName.None),
            ),
            result.config?.gestures,
        )
    }

    @Test
    fun `the remaining actions parse by their kebab-case names`() {
        val result = parse("""{ "swipeDown": "quick-settings", "swipeUp": "power-menu", "swipeLeft": "recents" }""")

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(
            GesturesConfig(
                swipeDown = GestureConfig.Action(GestureActionName.QuickSettings),
                swipeUp = GestureConfig.Action(GestureActionName.PowerMenu),
                swipeLeft = GestureConfig.Action(GestureActionName.Recents),
            ),
            result.config?.gestures,
        )
    }

    /**
     * The feed is hidden in release builds (FeatureFlags.feed): a key that
     * accepted it would make the file a route around the flag.
     */
    @Test
    fun `feed is not an action a file can set`() {
        val result = parse("""{ "swipeRight": "feed" }""")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" && it.message.contains("'feed'") })
    }

    /** The widget pages are gone (Migration6); the file never offers the old value. */
    @Test
    fun `widgets is not an action a file can set`() {
        val result = parse("""{ "swipeUp": "widgets" }""")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" && it.message.contains("'widgets'") })
    }

    /** A bare string is an action name; an app is always an object, or a package could shadow an action. */
    @Test
    fun `a bare package name is not an app`() {
        val result = parse("""{ "swipeLeft": "com.android.dialer" }""")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" && it.message.contains("'com.android.dialer'") })
    }

    @Test
    fun `a number is neither an action nor an app`() {
        val result = parse("""{ "swipeLeft": 3 }""")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" })
    }

    @Test
    fun `an app with an invalid package name is refused at its path`() {
        val result = parse("""{ "swipeLeft": { "packageName": "not a package" } }""")

        assertEquals(
            listOf("gestures.swipeLeft.packageName"),
            result.diagnostics.filter { it.code == "invalid-package-name" }.map { it.path },
        )
    }

    @Test
    fun `unknown keys under gestures and inside an app are reported at their paths`() {
        val result = parse("""{ "swipeDiagonal": "search", "swipeLeft": { "packageName": "com.android.dialer", "label": "Phone" } }""")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("gestures.swipeDiagonal", "gestures.swipeLeft.label"),
            result.diagnostics.filter { it.code == "unknown-key" }.map { it.path },
        )
    }

    // ---- differ ----

    @Test
    fun `only the gestures that differ from the device become a mutation`() {
        val desired = LauncherConfig(
            schemaVersion = 2,
            gestures = GesturesConfig(
                swipeDown = GestureConfig.Action(GestureActionName.Search),
                swipeLeft = GestureConfig.App(Favorite("com.android.dialer")),
            ),
        )
        val current = ConfigState(
            gestures = GestureDefaults.All + (Gesture.SwipeDown to GestureConfig.Action(GestureActionName.Search)),
        )

        assertEquals(
            listOf(ConfigMutation.SetGestures(mapOf(Gesture.SwipeLeft to GestureConfig.App(Favorite("com.android.dialer"))))),
            ConfigDiffer.diff(desired, current),
        )
    }

    @Test
    fun `gestures equal to the device produce no mutation`() {
        val desired = LauncherConfig(
            schemaVersion = 2,
            gestures = GesturesConfig(doubleTap = GestureConfig.Action(GestureActionName.ScreenLock)),
        )

        assertEquals(emptyList<ConfigMutation>(), ConfigDiffer.diff(desired, ConfigState()))
    }

    /** A device gesture the file cannot name (a shortcut) is not the file's value: the file wins on reload. */
    @Test
    fun `a gesture the device runs but the file cannot name differs from the configured one`() {
        val desired = LauncherConfig(
            schemaVersion = 2,
            gestures = GesturesConfig(swipeLeft = GestureConfig.App(Favorite("com.android.dialer"))),
        )
        val current = ConfigState(gestures = GestureDefaults.All + (Gesture.SwipeLeft to null))

        assertEquals(
            listOf(ConfigMutation.SetGestures(mapOf(Gesture.SwipeLeft to GestureConfig.App(Favorite("com.android.dialer"))))),
            ConfigDiffer.diff(desired, current),
        )
    }

    // ---- read-back ----

    @Test
    fun `the read-back serves every gesture, the defaults included`() {
        val gestures = ConfigState().toLauncherConfig().gestures

        assertEquals(
            GesturesConfig(
                swipeDown = GestureConfig.Action(GestureActionName.Search),
                swipeUp = GestureConfig.Action(GestureActionName.Search),
                swipeLeft = GestureConfig.Action(GestureActionName.None),
                swipeRight = GestureConfig.Action(GestureActionName.None),
                doubleTap = GestureConfig.Action(GestureActionName.ScreenLock),
                longPress = GestureConfig.Action(GestureActionName.None),
                homeButton = GestureConfig.Action(GestureActionName.None),
            ),
            gestures,
        )
    }

    /** The favorites precedent: the read-back serves what the file can hold. */
    @Test
    fun `the read-back leaves out a gesture the file cannot name`() {
        val state = ConfigState(gestures = GestureDefaults.All + (Gesture.SwipeLeft to null))

        val encoded = ConfigParser.json.encodeToString(LauncherConfig.serializer(), state.toLauncherConfig())

        assertTrue(encoded.contains("\"swipeRight\""))
        assertFalse(encoded.contains("\"swipeLeft\""))
    }

    @Test
    fun `a read-back with every kind of gesture parses back to itself`() {
        val state = ConfigState(
            gestures = GestureDefaults.All + mapOf(
                Gesture.SwipeLeft to GestureConfig.App(Favorite("com.android.dialer")),
                Gesture.SwipeRight to GestureConfig.App(Favorite("com.example.mail", Profile.Work)),
                Gesture.LongPress to GestureConfig.Action(GestureActionName.QuickSettings),
            ),
        )
        val readBack = state.toLauncherConfig()

        val encoded = ConfigParser.json.encodeToString(LauncherConfig.serializer(), readBack)
        val reparsed = ConfigParser.parse(encoded)

        assertEquals(emptyList<Diagnostic>(), reparsed.diagnostics)
        assertEquals(readBack.gestures, reparsed.config?.gestures)
        // An app reads back as the object form, the personal profile left out like a favorite's.
        assertTrue(encoded.filterNot(Char::isWhitespace).contains("\"swipeLeft\":{\"packageName\":\"com.android.dialer\"}"))
    }
}

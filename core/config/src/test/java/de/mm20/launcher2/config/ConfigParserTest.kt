package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserTest {

    private val fullConfig = """
        {
          "schemaVersion": 1,
          "icons": {
            "themed": true,
            "enforceThemed": true,
            "pack": "app.lawnchair.lawnicons"
          },
          "appearance": {
            "transparency": {
              "name": "liquid-glass",
              "background": 0.31,
              "surface": 0.31,
              "elevatedSurface": 0.31
            },
            "wallpaper": { "image": "home.jpg", "target": "lock" }
          },
          "home": {
            "searchBar": { "position": "bottom" },
            "dock": {
              "enabled": true,
              "favorites": [
                { "packageName": "com.example.dialer", "profile": "personal" },
                { "packageName": "com.example.mail", "profile": "work" },
                { "packageName": "com.example.vault", "profile": "private" }
              ]
            },
            "widgets": {
              "enabled": true,
              "widgets": ["apps"]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses full valid config`() {
        val result = ConfigParser.parse(fullConfig)

        assertTrue(result.isSuccess)
        assertTrue(result.diagnostics.isEmpty())
        val config = result.config!!
        assertEquals(1, config.schemaVersion)
        assertEquals(true, config.icons?.themed)
        assertEquals(true, config.icons?.enforceThemed)
        assertEquals("app.lawnchair.lawnicons", config.icons?.pack)
        assertEquals("liquid-glass", config.appearance?.transparency?.name)
        assertEquals(0.31f, config.appearance?.transparency?.background)
        assertEquals(WallpaperConfig("home.jpg", WallpaperTarget.Lock), config.appearance?.wallpaper)
        assertEquals(SearchBarPosition.Bottom, config.home?.searchBar?.position)
        assertEquals(true, config.home?.dock?.enabled)
        assertEquals(
            listOf(
                Favorite("com.example.dialer", Profile.Personal),
                Favorite("com.example.mail", Profile.Work),
                Favorite("com.example.vault", Profile.Private),
            ),
            config.home?.dock?.favorites,
        )
        assertEquals(
            listOf(BuiltinWidget.Apps),
            config.home?.widgets?.widgets,
        )
    }

    @Test
    fun `accepts comments and trailing commas`() {
        val jsonc = """
            {
              // JSONC comment
              "schemaVersion": 1,
              "icons": {
                "themed": true, // trailing comment
              },
              /* block comment */
            }
        """.trimIndent()

        val result = ConfigParser.parse(jsonc)

        assertTrue(result.isSuccess)
        assertEquals(true, result.config?.icons?.themed)
    }

    @Test
    fun `omitted sections remain null`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 1 }""")

        assertTrue(result.isSuccess)
        val config = result.config!!
        assertNull(config.icons)
        assertNull(config.appearance)
        assertNull(config.home)
    }

    @Test
    fun `omitted fields within a section remain null`() {
        val result = ConfigParser.parse(
            """{ "schemaVersion": 1, "icons": { "themed": true } }"""
        )

        assertTrue(result.isSuccess)
        val icons = result.config!!.icons!!
        assertEquals(true, icons.themed)
        assertNull(icons.enforceThemed)
        assertNull(icons.pack)
    }

    @Test
    fun `rejects malformed JSON without throwing`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 1, """)

        assertFalse(result.isSuccess)
        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "malformed-json" && it.severity == Severity.Error })
    }

    @Test
    fun `rejects non-object JSON without throwing`() {
        val result = ConfigParser.parse("""[1, 2, 3]""")

        assertFalse(result.isSuccess)
        assertNull(result.config)
    }

    @Test
    fun `rejects oversize input`() {
        val padding = "x".repeat(ConfigParser.MaxInputBytes)
        val oversized = """{ "schemaVersion": 1, "pad": "$padding" }"""

        val result = ConfigParser.parse(oversized)

        assertFalse(result.isSuccess)
        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "input-too-large" })
    }

    @Test
    fun `accepts input at exactly the size cap`() {
        val filler = "x".repeat(ConfigParser.MaxInputBytes - 40)
        val atCap = """{ "schemaVersion": 1, "pad": "$filler" }"""

        val result = ConfigParser.parse(atCap)

        assertFalse(result.diagnostics.any { it.code == "input-too-large" })
    }

    @Test
    fun `missing schemaVersion fails`() {
        val result = ConfigParser.parse("""{ "icons": { "themed": true } }""")

        assertFalse(result.isSuccess)
        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "missing-schema-version" })
    }

    @Test
    fun `non-integer schemaVersion fails`() {
        val result = ConfigParser.parse("""{ "schemaVersion": "one" }""")

        assertFalse(result.isSuccess)
        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "invalid-schema-version" })
    }

    @Test
    fun `schemaVersion 1 parses`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 1 }""")

        assertTrue(result.isSuccess)
        assertNotNull(result.config)
    }

    @Test
    fun `schemaVersion 2 fails cleanly`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2 }""")

        assertFalse(result.isSuccess)
        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "unsupported-schema-version" })
    }

    @Test
    fun `schemaVersion 0 fails cleanly`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 0 }""")

        assertFalse(result.isSuccess)
        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "unsupported-schema-version" })
    }

    @Test
    fun `unknown keys at multiple nesting levels are reported but accepted`() {
        val input = """
            {
              "schemaVersion": 1,
              "futureTopLevel": true,
              "icons": {
                "themed": true,
                "futureIconsKey": 42
              },
              "home": {
                "dock": {
                  "enabled": true,
                  "futureDockKey": "x",
                  "favorites": [
                    { "packageName": "com.example.app", "futureFavoriteKey": 1 }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertTrue(result.isSuccess)
        assertNotNull(result.config)
        val unknownKeys = result.diagnostics.filter { it.code == "unknown-key" }
        assertEquals(4, unknownKeys.size)
        assertTrue(unknownKeys.all { it.severity == Severity.Warning })
        assertEquals(
            setOf(
                "futureTopLevel",
                "icons.futureIconsKey",
                "home.dock.futureDockKey",
                "home.dock.favorites[0].futureFavoriteKey",
            ),
            unknownKeys.map { it.path }.toSet(),
        )
        assertEquals(true, result.config?.icons?.themed)
        assertEquals(true, result.config?.home?.dock?.enabled)
    }

    @Test
    fun `invalid wallpaper upload names are reported`() {
        for (bad in listOf("../etc", ".hidden", "a/b", "", "x".repeat(65))) {
            val result = ConfigParser.parse(
                """{ "schemaVersion": 1, "appearance": { "wallpaper": { "image": "$bad" } } }"""
            )
            assertTrue("'$bad' should be rejected", result.diagnostics.any { it.code == "invalid-wallpaper-image" })
        }
        val ok = ConfigParser.parse("""{ "schemaVersion": 1, "appearance": { "wallpaper": { "image": "home-1.jpg" } } }""")
        assertTrue(ok.diagnostics.none { it.code == "invalid-wallpaper-image" })
    }

    @Test
    fun `invalid transparency values are reported`() {
        val input = """
            {
              "schemaVersion": 1,
              "appearance": {
                "transparency": {
                  "background": 1.5,
                  "surface": -0.1,
                  "elevatedSurface": 0.5
                }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertNotNull(result.config)
        val invalid = result.diagnostics.filter { it.code == "invalid-transparency" }
        assertEquals(2, invalid.size)
        assertEquals(
            setOf(
                "appearance.transparency.background",
                "appearance.transparency.surface",
            ),
            invalid.map { it.path }.toSet(),
        )
    }

    @Test
    fun `invalid package names are reported`() {
        val input = """
            {
              "schemaVersion": 1,
              "icons": { "pack": "not a package!" },
              "home": {
                "dock": {
                  "favorites": [
                    { "packageName": "com.example.valid" },
                    { "packageName": "nodot" },
                    { "packageName": "com..double" }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertNotNull(result.config)
        val invalid = result.diagnostics.filter { it.code == "invalid-package-name" }
        assertEquals(3, invalid.size)
        assertEquals(
            setOf(
                "icons.pack",
                "home.dock.favorites[1].packageName",
                "home.dock.favorites[2].packageName",
            ),
            invalid.map { it.path }.toSet(),
        )
    }

    @Test
    fun `duplicate favorites are reported`() {
        val input = """
            {
              "schemaVersion": 1,
              "home": {
                "dock": {
                  "favorites": [
                    { "packageName": "com.example.a", "profile": "personal" },
                    { "packageName": "com.example.a", "profile": "work" },
                    { "packageName": "com.example.a", "profile": "personal" }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        val duplicates = result.diagnostics.filter { it.code == "duplicate-favorite" }
        assertEquals(1, duplicates.size)
        assertEquals("home.dock.favorites[2]", duplicates.single().path)
    }

    @Test
    fun `duplicate widgets are reported`() {
        val input = """
            {
              "schemaVersion": 1,
              "home": {
                "widgets": { "widgets": ["apps", "apps"] }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        val duplicates = result.diagnostics.filter { it.code == "duplicate-widget" }
        assertEquals(1, duplicates.size)
        assertEquals("home.widgets.widgets[1]", duplicates.single().path)
    }

    @Test
    fun `blank and oversized names are reported`() {
        val input = """
            {
              "schemaVersion": 1,
              "appearance": { "transparency": { "name": "   " } }
            }
        """.trimIndent()

        val blank = ConfigParser.parse(input)
        assertTrue(blank.diagnostics.any { it.code == "invalid-name" && it.path == "appearance.transparency.name" })

        val longName = "n".repeat(ConfigValidator.MaxNameLength + 1)
        val oversized = ConfigParser.parse(
            """{ "schemaVersion": 1, "appearance": { "transparency": { "name": "$longName" } } }"""
        )
        assertTrue(oversized.diagnostics.any { it.code == "invalid-name" })
    }

    @Test
    fun `enum serial names are stable and lowercase`() {
        val input = """
            {
              "schemaVersion": 1,
              "home": { "searchBar": { "position": "bottom" } }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertTrue(result.isSuccess)
        assertEquals(SearchBarPosition.Bottom, result.config?.home?.searchBar?.position)
    }

    @Test
    fun `invalid enum value fails decode without throwing`() {
        val input = """
            {
              "schemaVersion": 1,
              "home": { "searchBar": { "position": "sideways" } }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" })
    }

    @Test
    fun `serialization round trip`() {
        val config = ConfigParser.parse(fullConfig).config!!

        val encoded = ConfigParser.json.encodeToString(LauncherConfig.serializer(), config)
        val decoded = ConfigParser.parse(encoded)

        assertTrue(decoded.isSuccess)
        assertEquals(config, decoded.config)
    }

    @Test
    fun `round trip of minimal config`() {
        val config = LauncherConfig(schemaVersion = 1)

        val encoded = ConfigParser.json.encodeToString(LauncherConfig.serializer(), config)
        val decoded = ConfigParser.parse(encoded)

        assertEquals(config, decoded.config)
    }
}

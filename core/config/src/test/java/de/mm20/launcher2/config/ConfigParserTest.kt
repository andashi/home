package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import java.io.File

class ConfigParserTest {

    private val fullConfig = """
        {
          "schemaVersion": 2,
          "icons": {
            "themed": true,
            "enforceThemed": true,
            "pack": "app.lawnchair.lawnicons"
          },
          "appearance": {
            "glass": { "blur": 20, "tint": 0.4, "radius": 24, "contrast": "high", "wallpaperBlur": false, "searchWallpaperBlur": false },
            "wallpaper": { "image": "home.jpg", "target": "lock" }
          },
          "home": {
            "searchBar": { "position": "bottom" },
            "favorites": [
              { "packageName": "com.example.dialer", "profile": "personal" },
              { "packageName": "com.example.mail", "profile": "work" },
              { "packageName": "com.example.vault", "profile": "private" }
            ],
            "widgets": { "enabled": true },
            "grid": {
              "columns": 4,
              "locked": false,
              "labels": false,
              "layouts": {
                "phone": {
                  "items": [
                    { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
                    { "id": "clock", "widget": "com.android.deskclock/.DigitalAppWidgetProvider",
                      "x": 0, "y": 0, "w": 4, "h": 2, "profile": "personal",
                      "borderless": true, "background": false, "themeColors": true }
                  ]
                },
                "fold": { "items": [] }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses full valid config`() {
        val result = ConfigParser.parse(fullConfig)

        assertTrue(result.isSuccess)
        // Glass and labels are rendered since #75: nothing to report.
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        val config = result.config!!
        assertEquals(2, config.schemaVersion)
        assertEquals(true, config.icons?.themed)
        assertEquals(true, config.icons?.enforceThemed)
        assertEquals("app.lawnchair.lawnicons", config.icons?.pack)
        assertEquals(
            GlassConfig(20f, 0.4f, 24f, GlassContrast.High, wallpaperBlur = false, searchWallpaperBlur = false),
            config.appearance?.glass,
        )
        assertEquals(WallpaperConfig("home.jpg", WallpaperTarget.Lock), config.appearance?.wallpaper)
        assertEquals(SearchBarPosition.Bottom, config.home?.searchBar?.position)
        assertEquals(
            listOf(
                Favorite("com.example.dialer", Profile.Personal),
                Favorite("com.example.mail", Profile.Work),
                Favorite("com.example.vault", Profile.Private),
            ),
            config.home?.favorites,
        )
        assertEquals(true, config.home?.widgets?.enabled)
        val grid = config.home?.grid!!
        assertEquals(4, grid.columns)
        assertEquals(false, grid.locked)
        assertEquals(false, grid.labels)
        assertEquals(setOf("phone", "fold"), grid.layouts?.keys)
        assertEquals(
            listOf(
                GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1),
                GridItemConfig(
                    id = "clock",
                    widget = "com.android.deskclock/.DigitalAppWidgetProvider",
                    x = 0, y = 0, w = 4, h = 2,
                    profile = Profile.Personal,
                    borderless = true, background = false, themeColors = true,
                ),
            ),
            grid.layouts?.get("phone")?.items,
        )
        assertEquals(emptyList<GridItemConfig>(), grid.layouts?.get("fold")?.items)
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
    fun `schemaVersion 1 still parses through the migration`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 1 }""")

        assertTrue(result.isSuccess)
        assertEquals(2, result.config?.schemaVersion)
    }

    @Test
    fun `schemaVersion 2 parses`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2 }""")

        assertTrue(result.isSuccess)
        assertEquals(2, result.config?.schemaVersion)
    }

    @Test
    fun `schemaVersion 3 fails cleanly`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 3 }""")

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
              "schemaVersion": 2,
              "futureTopLevel": true,
              "icons": {
                "themed": true,
                "futureIconsKey": 42
              },
              "home": {
                "favorites": [
                  { "packageName": "com.example.app", "futureFavoriteKey": 1 }
                ],
                "grid": {
                  "columns": 4,
                  "futureGridKey": "x",
                  "layouts": {
                    "phone": { "items": [ { "id": "a", "widget": "favorites", "futureItemKey": 1 } ] }
                  }
                }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertTrue(result.isSuccess)
        assertNotNull(result.config)
        val unknownKeys = result.diagnostics.filter { it.code == "unknown-key" }
        assertEquals(5, unknownKeys.size)
        assertTrue(unknownKeys.all { it.severity == Severity.Warning })
        assertEquals(
            setOf(
                "futureTopLevel",
                "icons.futureIconsKey",
                "home.favorites[0].futureFavoriteKey",
                "home.grid.futureGridKey",
                "home.grid.layouts.phone.items[0].futureItemKey",
            ),
            unknownKeys.map { it.path }.toSet(),
        )
        assertEquals(true, result.config?.icons?.themed)
        assertEquals(4, result.config?.home?.grid?.columns)
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

    // ---- appearance.glass (#73) ----

    private fun glass(body: String) = ConfigParser.parse(
        """{ "schemaVersion": 2, "appearance": { "glass": { $body } } }"""
    )

    @Test
    fun `all four glass keys parse`() {
        val result = glass(""""blur": 0, "tint": 1, "radius": 64, "contrast": "low"""")

        assertTrue(result.isSuccess)
        assertEquals(GlassConfig(0f, 1f, 64f, GlassContrast.Low), result.config?.appearance?.glass)
    }

    @Test
    fun `searchWallpaperBlur parses on its own, independent of wallpaperBlur`() {
        val result = glass(""""wallpaperBlur": false, "searchWallpaperBlur": true""")

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(
            GlassConfig(wallpaperBlur = false, searchWallpaperBlur = true),
            result.config?.appearance?.glass,
        )
    }

    @Test
    fun `a glass key that is left out stays unmanaged`() {
        val result = glass(""""tint": 0.2""")

        assertEquals(GlassConfig(tint = 0.2f), result.config?.appearance?.glass)
    }

    @Test
    fun `a float blur or radius parses, so a generator writing 24_0 does not lose the zone`() {
        val result = glass(""""blur": 24.0, "radius": 27.5""")

        assertTrue(result.isSuccess)
        assertEquals(24f, result.config?.appearance?.glass?.blur)
        assertEquals(27.5f, result.config?.appearance?.glass?.radius)
    }

    @Test
    fun `out of range glass values are rejected with the field in the path`() {
        val cases = mapOf(
            """"tint": 1.2""" to "appearance.glass.tint",
            """"tint": -0.1""" to "appearance.glass.tint",
            """"blur": -1""" to "appearance.glass.blur",
            """"blur": 65""" to "appearance.glass.blur",
            """"radius": -1""" to "appearance.glass.radius",
            """"radius": 65""" to "appearance.glass.radius",
        )
        for ((body, path) in cases) {
            val result = glass(body)
            val invalid = result.diagnostics.filter { it.code == "invalid-glass" }
            assertEquals(body, listOf(path), invalid.map { it.path })
            assertEquals(body, Severity.Error, invalid.single().severity)
            assertTrue(body, invalid.single().message.contains(path.substringAfterLast('.')))
            assertFalse(body, result.isSuccess)
        }
    }

    @Test
    fun `NaN glass values are rejected`() {
        // JSON cannot spell NaN, so this guards the validator itself.
        val config = LauncherConfig(
            schemaVersion = 2,
            appearance = AppearanceConfig(glass = GlassConfig(blur = Float.NaN, tint = Float.NaN, radius = Float.NaN)),
        )

        assertEquals(
            listOf("appearance.glass.blur", "appearance.glass.tint", "appearance.glass.radius"),
            ConfigValidator.validate(config).filter { it.code == "invalid-glass" }.map { it.path },
        )
    }

    @Test
    fun `an unknown contrast is rejected and the message names the field`() {
        val result = glass(""""contrast": "extreme"""")

        assertNull(result.config)
        val failure = result.diagnostics.single { it.code == "decode-failed" }
        assertTrue(failure.message, failure.message.contains("appearance.glass.contrast"))
    }

    @Test
    fun `a misspelled key inside glass is an unknown key`() {
        val result = glass(""""blurr": 10""")

        assertEquals(
            listOf("unknown-key"),
            result.diagnostics.filter { it.path == "appearance.glass.blurr" }.map { it.code },
        )
    }

    @Test
    fun `transparency is reported once as inert and the file still applies`() {
        val result = ConfigParser.parse(
            """
            {
              "schemaVersion": 2,
              "appearance": {
                "transparency": { "name": "liquid-glass", "background": 0.31, "surface": 7, "typo": 1 }
              }
            }
            """.trimIndent()
        )

        assertTrue(result.isSuccess)
        // One diagnostic for the section: its sub-keys, even a bad value or a
        // typo, no longer mean anything, so they are not validated either.
        assertEquals(listOf("inert-key"), result.diagnostics.map { it.code })
        assertEquals("appearance.transparency", result.diagnostics.single().path)
        assertTrue(result.diagnostics.single().message.contains("appearance.glass"))
    }

    /** #3 D6: "none" is a value of its own: the apps' own icons, no pack and no fallback. */
    @Test
    fun `icons pack none is accepted`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2, "icons": { "pack": "none" } }""")

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals("none", result.config!!.icons!!.pack)
    }

    @Test
    fun `invalid package names are reported`() {
        val input = """
            {
              "schemaVersion": 2,
              "icons": { "pack": "not a package!" },
              "home": {
                "favorites": [
                  { "packageName": "com.example.valid" },
                  { "packageName": "nodot" },
                  { "packageName": "com..double" }
                ]
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
                "home.favorites[1].packageName",
                "home.favorites[2].packageName",
            ),
            invalid.map { it.path }.toSet(),
        )
    }

    @Test
    fun `duplicate favorites are reported`() {
        val input = """
            {
              "schemaVersion": 2,
              "home": {
                "favorites": [
                  { "packageName": "com.example.a", "profile": "personal" },
                  { "packageName": "com.example.a", "profile": "work" },
                  { "packageName": "com.example.a", "profile": "personal" }
                ]
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        val duplicates = result.diagnostics.filter { it.code == "duplicate-favorite" }
        assertEquals(1, duplicates.size)
        assertEquals("home.favorites[2]", duplicates.single().path)
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
        val config = LauncherConfig(schemaVersion = 2)

        val encoded = ConfigParser.json.encodeToString(LauncherConfig.serializer(), config)
        val decoded = ConfigParser.parse(encoded)

        assertEquals(config, decoded.config)
    }

    @Test
    fun `a layout this build does not know is dropped, not fatal`() {
        // A layouts map decodes every key it finds, so a document that names a
        // layout a later build introduced ("tablet", say) would otherwise carry
        // it into state. The entry is dropped with a warning; the rest of the
        // document, phone layout included, survives.
        val input = """
            {
              "schemaVersion": 2,
              "icons": { "themed": true },
              "home": {
                "grid": {
                  "layouts": {
                    "tablet": { "items": [ { "id": "a", "widget": "favorites" } ] },
                    "phone": { "items": [ { "id": "a", "widget": "favorites" } ] }
                  }
                }
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertTrue(result.isSuccess)
        assertEquals(setOf("phone"), result.config?.home?.grid?.layouts?.keys)
        assertEquals(true, result.config?.icons?.themed)

        val dropped = result.diagnostics.filter { it.code == "unknown-layout" }
        assertEquals(1, dropped.size)
        assertEquals("home.grid.layouts.tablet", dropped.single().path)
        assertEquals(Severity.Warning, dropped.single().severity)
        assertEquals(
            "an unknown layout is reported once, not also as an unknown key",
            emptyList<Diagnostic>(),
            result.diagnostics.filter { it.code == "unknown-key" },
        )
    }

    @Test
    fun `a bad scalar enum still fails the document`() {
        // Deliberate asymmetry: a typo in a scalar has no sensible fallback.
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
    fun `a leftover clock block is reported, not silently accepted`() {
        // home.clock left the contract with the clock widget (ADR 0008). A
        // config written before that still carries it, and the promise made
        // there is that an unknown *key* is ignored *with a diagnostic* - which
        // only holds if knownKeys stops listing it.
        val input = """
            {
              "schemaVersion": 1,
              "home": { "clock": { "style": "orbit", "fillHeight": true } }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertTrue(result.isSuccess)
        val unknown = result.diagnostics.filter { it.code == "unknown-key" }
        assertTrue(
            "expected a diagnostic for home.clock, got ${result.diagnostics.map { it.path }}",
            unknown.any { it.path == "home.clock" },
        )
    }

    @Test
    fun `a favorite may be a bare package name`() {
        // provisioning/#1: the config generator emits favorites as strings while
        // the contract declared objects. Without the lenient form one bare
        // string fails the decode and the zone loses wallpaper, dock and icons
        // with it. Never triggered only because every zone ships an empty list.
        val input = """
            {
              "schemaVersion": 2,
              "icons": { "themed": true },
              "home": {
                "favorites": [
                  "com.example.dialer",
                  { "packageName": "com.example.mail", "profile": "work" }
                ]
              }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                Favorite("com.example.dialer", Profile.Personal),
                Favorite("com.example.mail", Profile.Work),
            ),
            result.config?.home?.favorites,
        )
        assertEquals(true, result.config?.icons?.themed)
    }

    @Test
    fun `a favorite that is neither a name nor an object fails`() {
        val input = """
            {
              "schemaVersion": 2,
              "home": { "favorites": [42] }
            }
        """.trimIndent()

        val result = ConfigParser.parse(input)

        assertNull(result.config)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" })
    }

    @Test
    fun `writing a favorite always uses the object form`() {
        val config = LauncherConfig(
            schemaVersion = 2,
            home = HomeConfig(favorites = listOf(Favorite("com.example.dialer"))),
        )

        val serialized = ConfigParser.json.encodeToString(LauncherConfig.serializer(), config)

        assertTrue(
            "a round trip should normalise to the object form, got: $serialized",
            serialized.contains("\"packageName\""),
        )
        assertEquals(config.home?.favorites, ConfigParser.parse(serialized).config?.home?.favorites)
    }

    /**
     * The example document in ADR 0002 is read from the ADR itself and parsed
     * here, so a change to the contract that nobody carried into the
     * documentation fails in L1.
     *
     * #35 is why: the favorites shape existed nowhere except in the Kotlin
     * source, the ADR named "dock favorites" in one line of prose and showed
     * nothing, and the config generator on the other side guessed a shape that
     * failed the whole decode. An example that is only prose is an example
     * that can be wrong.
     */
    @Test
    fun `the example document in ADR 0002 parses`() {
        val adr = repoFile("docs/architecture/adr/0002-config-format-json.md")
        val example = fencedJsonAfter(adr.readText(), marker = "<!-- adr-0002-example -->")

        val result = ConfigParser.parse(example)

        // The example is the contract, and since #23 every key in it is
        // served: no unknown keys, no inert keys, no validation errors.
        assertEquals(
            "the documented example must produce no diagnostic at all",
            emptyList<Diagnostic>(),
            result.diagnostics,
        )
        assertTrue(result.isSuccess)

        val config = result.config!!
        assertEquals(2, config.schemaVersion)
        assertEquals("com.example.iconpack", config.icons?.pack)
        assertEquals(WallpaperTarget.Both, config.appearance?.wallpaper?.target)
        assertEquals(SearchBarPosition.Bottom, config.home?.searchBar?.position)
        assertEquals(true, config.home?.widgets?.enabled)
        assertEquals(
            GlassConfig(24f, 0.12f, 28f, GlassContrast.Medium, wallpaperBlur = true, searchWallpaperBlur = true),
            config.appearance?.glass,
        )
        assertEquals(true, config.home?.grid?.labels)
        // Both spellings, which is the point of showing them.
        assertEquals(
            listOf(
                Favorite("org.thoughtcrime.securesms", Profile.Personal),
                Favorite("com.example.work.mail", Profile.Work),
                Favorite("com.example.dialer", Profile.Personal),
            ),
            config.home?.favorites,
        )
        // The grid: the favorites widget in the bottom row and one AppWidget
        // with full geometry, so the example shows both item shapes.
        val phone = config.home?.grid?.layouts?.get("phone")?.items
        assertEquals(4, config.home?.grid?.columns)
        assertEquals(false, config.home?.grid?.locked)
        assertNotNull(phone)
        assertTrue(phone!!.any { it.isFavorites && it.hasGeometry })
        assertTrue(phone.any { !it.isFavorites && it.hasGeometry })
    }

    /** The unit test runs with the module directory as its working directory. */
    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$path not found above ${File("").absolutePath}")
    }

    /**
     * The fenced block after [marker], by line rather than by substring.
     *
     * Only a run of backticks at least as long as the opening fence, with
     * nothing but whitespace around it, closes the block - a language-tagged
     * line such as ```` ```json ```` does not. A substring search would end the
     * block there and hand the parser a prefix, and a guard that reads less
     * than it thinks is the failure this whole test exists to prevent.
     */
    private fun fencedJsonAfter(markdown: String, marker: String): String {
        val lines = markdown.lines()

        val markerAt = lines.indexOfFirst { it.trim() == marker }
        assertTrue("$marker is missing from the ADR", markerAt >= 0)

        val openAt = (markerAt + 1..lines.lastIndex)
            .firstOrNull { lines[it].trimStart().startsWith("```") }
        assertNotNull("no fenced block follows $marker", openAt)

        val opener = lines[openAt!!].trim()
        val fence = opener.takeWhile { it == '`' }
        assertEquals(
            "the block after $marker must be tagged json",
            "json",
            opener.removePrefix(fence).trim(),
        )

        val closeAt = (openAt + 1..lines.lastIndex).firstOrNull {
            val line = lines[it].trim()
            line.length >= fence.length && line.all { char -> char == '`' }
        }
        assertNotNull("the block after $marker is never closed", closeAt)

        return lines.subList(openAt + 1, closeAt!!).joinToString("\n")
    }

    /**
     * What `content://<applicationId>.state/config` hands a host back is the
     * document re-serialised from the decoded model, and the config Json does
     * not set `encodeDefaults`. A favorite in the personal profile therefore
     * comes back *without* its `profile` key, and one written as a bare
     * package name comes back as an object.
     *
     * Pinned because a reader has been assuming otherwise: the convergence
     * check in the provisioning repo's `45-launcher-config.sh` compares the
     * read-back field by field against the file it pushed, on the stated
     * assumption that "the provider serves a fully populated document". It
     * does not, and the first zone to configure a dock favorite in the
     * personal profile fails that check - measured on the GrapheneOS emulator,
     * 2026-09-22, six of six profiles (andashi/home#35).
     *
     * If this test ever fails because the output grew the key, the contract
     * became friendlier and the note above can go. If it fails because the
     * input shape changed, the host comparing against it needs to hear about
     * it first.
     */
    @Test
    fun `the effective document drops a default profile and normalises the short form`() {
        fun roundTrip(document: String): String {
            val config = ConfigParser.parse(document).config
            assertNotNull(document, config)
            return ConfigParser.json.encodeToString(LauncherConfig.serializer(), config!!)
                .replace(Regex("\\s+"), "")
        }

        val canonical =
            """{"schemaVersion":2,"home":{"favorites":[{"packageName":"com.example.app"}]}}"""

        assertEquals(
            "an explicit personal profile is the default and is not written back",
            canonical,
            roundTrip("""{"schemaVersion":2,"home":{"favorites":[{"packageName":"com.example.app","profile":"personal"}]}}"""),
        )
        assertEquals(
            "the bare package name becomes an object",
            canonical,
            roundTrip("""{"schemaVersion":2,"home":{"favorites":["com.example.app"]}}"""),
        )

        val work =
            """{"schemaVersion":2,"home":{"favorites":[{"packageName":"com.example.app","profile":"work"}]}}"""
        assertEquals(
            "a non-default profile survives, which is why the asymmetry is easy to miss",
            work,
            roundTrip(work),
        )
    }

    // ---- #47: reporting which contract keys this build actually serves ----

    /**
     * No key of the current contract is inert (the grid gave the last one,
     * `home.dock.enabled`, a renderer and then removed it, #46), so the
     * mechanism is exercised against a table of its own. The mechanism has
     * to stay: the next build that accepts a key without serving it must say
     * so, and a test that only passes while such a key exists would vanish
     * with it.
     */
    private val tableWithAnInertKey: Map<String, Map<String, KeyEffect>> = mapOf(
        "" to mapOf("schemaVersion" to KeyEffect.Applied, "home" to KeyEffect.Applied),
        "home" to mapOf(
            "legacy" to KeyEffect.Inert("nothing reads it any more"),
            "favorites" to KeyEffect.Applied,
        ),
        "home.favorites[]" to mapOf("packageName" to KeyEffect.Applied, "profile" to KeyEffect.Applied),
    )

    private fun keysOf(document: String, table: Map<String, Map<String, KeyEffect>>): List<Diagnostic> {
        val root = ConfigParser.json.parseToJsonElement(document) as JsonObject
        return ConfigParser.diagnoseKeys(root, table)
    }

    @Test
    fun `an inert key is reported while it is present, not only when it changes`() {
        val document = """{"schemaVersion":2,"home":{"legacy":true,"favorites":[]}}"""

        val inert = keysOf(document, tableWithAnInertKey).filter { it.code == "inert-key" }

        assertEquals(1, inert.size)
        assertEquals("home.legacy", inert.single().path)
        assertEquals(Severity.Warning, inert.single().severity)
        // The message has to say what actually happens, or a host reads
        // "not served" and guesses the rest.
        assertTrue(inert.single().message.contains("nothing reads it any more"))
    }

    @Test
    fun `a document that does not mention the inert key says nothing about it`() {
        val document = """{"schemaVersion":2,"home":{"favorites":["com.example.app"]}}"""

        assertEquals(emptyList<Diagnostic>(), keysOf(document, tableWithAnInertKey))
    }

    @Test
    fun `an inert key is a warning, which does not stop a config from applying`() {
        val document = """{"schemaVersion":2,"home":{"legacy":true}}"""

        val diagnostics = keysOf(document, tableWithAnInertKey)

        assertEquals(listOf("inert-key"), diagnostics.map { it.code })
        assertTrue(diagnostics.none { it.severity == Severity.Error })
    }

    @Test
    fun `an unknown key is still an unknown key, not an inert one`() {
        val document = """{"schemaVersion":2,"home":{"grid":{"colums":4}}}"""

        val codes = ConfigParser.parse(document).diagnostics.map { it.code }

        assertEquals(listOf("unknown-key"), codes)
    }

    /**
     * `transparency` left the contract (#73) and is the one inert key; glass
     * and labels became [KeyEffect.Applied] with the renderer (#75). A key
     * that becomes inert by accident fails here.
     */
    @Test
    fun `the only inert key of the current contract is transparency`() {
        val inert = ConfigParser.keyEffects.flatMap { (path, keys) ->
            keys.filter { it.value is KeyEffect.Inert }.map { "$path.${it.key}" }
        }

        assertEquals(listOf("appearance.transparency"), inert)
    }

    /**
     * Ties the effect table to the mutations that actually exist. A section
     * that gains a mutation without an entry here fails, which is the property
     * that keeps the table from becoming true-on-the-day-it-was-written: the
     * classification cannot be skipped, only decided.
     */
    @Test
    fun `every section the differ can produce is classified`() {
        val everything = LauncherConfig(
            schemaVersion = 2,
            icons = IconsConfig(themed = true, enforceThemed = true, pack = "com.example.pack"),
            appearance = AppearanceConfig(
                glass = GlassConfig(blur = 10f, tint = 0.5f, radius = 12f, contrast = GlassContrast.High),
                wallpaper = WallpaperConfig(image = "w.jpg", target = WallpaperTarget.Both),
            ),
            home = HomeConfig(
                searchBar = SearchBarConfig(position = SearchBarPosition.Bottom),
                favorites = listOf(Favorite("com.example.app", Profile.Personal)),
                widgets = WidgetsConfig(enabled = true),
                grid = GridConfig(
                    columns = 5,
                    locked = true,
                    labels = false,
                    layouts = mapOf(
                        "phone" to GridLayoutConfig(
                            listOf(GridItemConfig(id = "dock", widget = "favorites", x = 0, y = 5, w = 4, h = 1)),
                        ),
                    ),
                ),
            ),
            search = SearchConfig(favorites = false, actions = listOf(SearchActionConfig("websearch"))),
        )

        val sections = ConfigDiffer.diff(everything, ConfigState()).map { it.section }.distinct()
        assertTrue("the fixture must actually produce mutations", sections.isNotEmpty())

        val unclassified = sections.filter { section ->
            val parent = section.substringBeforeLast('.', missingDelimiterValue = "")
            val key = section.substringAfterLast('.')
            ConfigParser.keyEffects[parent]?.get(key) == null
        }

        assertEquals(
            "sections the differ produces but the key table does not classify",
            emptyList<String>(),
            unclassified,
        )
    }

    // ---- search (#91) ----

    private val everySearchKey = """
        {
          "schemaVersion": 2,
          "search": {
            "favorites": false, "allApps": false, "layout": "list", "labels": false,
            "contacts": false, "shortcuts": false, "filterBar": false, "openKeyboard": false,
            "launchOnEnter": false, "reversed": true, "hiddenItemsButton": true
          }
        }
    """.trimIndent()

    @Test
    fun `every search key parses, all away from its default, with nothing to report`() {
        val result = ConfigParser.parse(everySearchKey)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(
            SearchConfig(
                favorites = false, allApps = false, layout = SearchResultLayout.List, labels = false,
                contacts = false, shortcuts = false, filterBar = false, openKeyboard = false,
                launchOnEnter = false, reversed = true, hiddenItemsButton = true,
            ),
            result.config?.search,
        )
    }

    @Test
    fun `a misspelled key inside search is reported at its path`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2, "search": { "favourites": false } }""")

        assertTrue(result.isSuccess)
        assertEquals(listOf("search.favourites"), result.diagnostics.filter { it.code == "unknown-key" }.map { it.path })
    }

    /** Control: the enum's own serializer rejects it in both states. */
    @Test
    fun `an unknown search layout fails with the field in the message`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2, "search": { "layout": "carousel" } }""")

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" && it.message.contains("search.layout") })
    }

    // ----- search.barPosition (#107) -----

    @Test
    fun `search barPosition parses with nothing to report`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2, "search": { "barPosition": "top" } }""")

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
        assertEquals(SearchBarPosition.Top, result.config?.search?.barPosition)
    }

    @Test
    fun `an unknown search bar position fails with the field in the message`() {
        val result = ConfigParser.parse("""{ "schemaVersion": 2, "search": { "barPosition": "middle" } }""")

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "decode-failed" && it.message.contains("search.barPosition") })
    }

    /**
     * Reversed results put the best match at the bottom; with the bar at the
     * top in search it is the farthest from the field. Applied, but said.
     */
    @Test
    fun `reversed results with a top bar in search are a warning`() {
        val result = ConfigParser.parse(
            """{ "schemaVersion": 2, "search": { "barPosition": "top", "reversed": true } }"""
        )

        assertTrue(result.isSuccess)
        val warning = result.diagnostics.single { it.code == "search-reversed-with-top-bar" }
        assertEquals(Severity.Warning, warning.severity)
        assertEquals("search.reversed", warning.path)
    }

    /** Control: reversed with the bar at the bottom is the combination it is for. */
    @Test
    fun `reversed results with a bottom bar in search are fine`() {
        val result = ConfigParser.parse(
            """{ "schemaVersion": 2, "search": { "barPosition": "bottom", "reversed": true } }"""
        )

        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
    }
}

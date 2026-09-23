package de.mm20.launcher2.ui.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Everything on the search screen is glass (#91). The screen is built from
 * upstream composables that each picked their own Material container; this
 * guard lists what drew an opaque or transparency-scheme surface there and
 * fails when one is used again, so a later upstream cherry-pick cannot
 * quietly bring a white card back. The glass look itself is tested where it
 * is drawn (GlassSurface, the result segments, the chip, the background).
 *
 * The files are declared as inputs of the test task (app/ui/build.gradle.kts),
 * or a change to one of them alone would leave the task up to date.
 */
class SearchScreenGlassGuardTest {

    private val ui = File(System.getProperty("user.dir"), "src/main/java/de/mm20/launcher2/ui")

    /**
     * What draws the search screen and nothing else. Components it shares
     * with the settings screens and the Material sheets (Banner, TagChip,
     * SearchBar) keep a Material branch for those and are tested by
     * behavior instead: glass where LocalOnGlass is set (SharedGlassSwitchTest,
     * SearchBarGlassTest).
     */
    private val searchScreen: List<File> = listOf(
        "launcher/search",
        "launcher/searchbar",
        "launcher/scaffold/LauncherScaffold.kt",
        "launcher/scaffold/components/SearchComponent.kt",
        "launcher/sheets/HiddenItemsSheet.kt",
        "component/MissingPermissionBanner.kt",
        "component/Toolbar.kt",
        "common/FavoritesTagSelector.kt",
    ).flatMap { path ->
        val file = File(ui, path)
        assertTrue("$path exists - update the guard when a file moves", file.exists())
        if (file.isDirectory) file.walkTopDown().filter { it.extension == "kt" }.toList() else listOf(file)
    }

    private val forbidden = mapOf(
        "the old transparency scheme" to Regex("""\btransparency\b"""),
        "an opaque LauncherCard" to Regex("""\bLauncherCard\("""),
        "an opaque surfaceContainer color" to Regex("""\bsurfaceContainer\w*"""),
        "an opaque elevated surface color" to Regex("""\bsurfaceColorAtElevation\("""),
        "a Material chip" to Regex("""\b(AssistChip|FilterChip|InputChip|SuggestionChip|ElevatedAssistChip|ElevatedFilterChip)\("""),
        "a Material card" to Regex("""(?<![A-Za-z])(Card|OutlinedCard|ElevatedCard)\("""),
        "a Material menu container" to Regex("""\bDropdownMenuGroup\("""),
    )

    @Test
    fun `nothing on the search screen draws a non-glass surface`() {
        val hits = searchScreen.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trimStart()
                val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                if (code.startsWith("import ") || comment) return@mapIndexedNotNull null
                forbidden.entries.firstOrNull { it.value.containsMatchIn(line) }?.let { (what, _) ->
                    "${file.relativeTo(ui)}:${index + 1}: $what: ${line.trim()}"
                }
            }
        }
        assertEquals("non-glass surfaces on the search screen:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun `the hidden-items sheet asks for the glass sheet`() {
        // DismissableBottomSheet is shared with sheets outside search and keeps
        // its Material surface for them; the search screen's sheet opts in.
        val sheet = File(ui, "launcher/sheets/HiddenItemsSheet.kt").readText()
        assertTrue("HiddenItemsSheet passes glass = true", Regex("""\bglass\s*=\s*true\b""").containsMatchIn(sheet))
    }

    @Test
    fun `the guard sees the files it guards`() {
        // A guard over zero files passes forever.
        assertTrue(searchScreen.size > 20)
        assertTrue(searchScreen.any { it.name == "GridResults.kt" })
    }
}

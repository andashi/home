package de.mm20.launcher2.appshortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `LauncherApps` call that needs the HOME role goes through
 * [queryShortcutHost] (andashi/home#30).
 *
 * The guard itself is tested in `ShortcutHostDegradationTest`; this one tests
 * that it is actually used. Without it a new `launcherApps.getShortcuts(...)`
 * written next to the existing ones simply bypasses it, nothing fails, and the
 * launcher dies again the next time provisioning moves the role - the five
 * present call sites were found by hand, which does not scale to the sixth.
 *
 * A source scan is normally a poor test. Here the rule is itself textual - "go
 * through the helper" - so the check matches the shape of what it guards, and
 * the failure message can name the line. It cannot see a call made through an
 * alias or reflection; it catches the way this mistake is actually made, which
 * is by copying the line above.
 */
class ShortcutHostCallSitesTest {

    /** Calls `LauncherApps` only answers for the holder of the HOME role. */
    private val roleGated = listOf(
        "getShortcuts(",
        "pinShortcuts(",
        "getShortcutConfigActivityList(",
        "getShortcutConfigActivityIntent(",
    )

    /** Where the wrapping happens, and therefore the one file allowed to call directly. */
    private val helper = "ShortcutHost.kt"

    @Test
    fun `no LauncherApps call bypasses queryShortcutHost`() {
        val sources = moduleSources()
        assertTrue("no sources found - the scan would pass vacuously", sources.isNotEmpty())

        val offenders = sources
            .filter { it.name != helper }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val code = line.substringBefore("//")
                    if (roleGated.any { code.contains("launcherApps.$it") || code.contains("launcherApps?.$it") }) {
                        "${file.name}:${index + 1}  ${line.trim()}"
                    } else null
                }
            }
            // A call inside a queryShortcutHost block is exactly what is wanted;
            // the lambda is on its own line, so the call sites that are already
            // wrapped are excluded by their surrounding block, not by this list.
            .filterNot { it.contains("queryShortcutHost") }

        assertEquals(
            "these call LauncherApps directly instead of through queryShortcutHost, " +
                    "so they crash the launcher when it is not the default: ",
            emptyList<String>(),
            offenders.filterNot { line -> wrapped(sources, line) },
        )
    }

    /**
     * True when the call sits inside a [queryShortcutHost] block. Looks back a
     * few lines rather than parsing: the wrapper opens two to four lines above
     * its call in every present use, and a deeper nesting is a rewrite that
     * should re-read this test anyway.
     */
    private fun wrapped(sources: List<File>, offender: String): Boolean {
        val (name, number) = offender.substringBefore("  ").split(":")
        val file = sources.first { it.name == name }
        val lines = file.readLines()
        val at = number.toInt() - 1
        return (maxOf(0, at - 6) until at).any { lines[it].contains("queryShortcutHost(") }
    }

    private fun moduleSources(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val main = File(dir, "data/appshortcuts/src/main")
            if (main.isDirectory) {
                return main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("data/appshortcuts/src/main not found above ${File("").absolutePath}")
    }
}

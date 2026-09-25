package de.mm20.launcher2.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fork's own strings must exist in every locale.
 *
 * Upstream's Crowdin project does not know them - `crowdin.yml` in this
 * repository describes that pipeline, not one we have - so nothing translates
 * them for us and they stay English unless we write them (#124).
 *
 * The test lives here rather than in `:core:i18n` because that module has no
 * test wiring, and adding it would put a coverage gate on an upstream module
 * for a test that covers no Kotlin code at all. It reads files, so it declares
 * them as inputs of the test task (see this module's build file).
 */
class ForkStringsTranslatedTest {

    private val res = File(repoRoot(), "core/i18n/src/main/res")
    private val nameRegex = Regex("""<string\s+name="([^"]+)"""")

    private fun repoRoot(): File =
        System.getProperty("repoRoot")?.let(::File)
            ?: error("repoRoot system property missing; see the test task in core/base/build.gradle.kts")

    private fun namesIn(file: File): Set<String> =
        nameRegex.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun forkOwned(): List<String> =
        File(repoRoot(), "core/i18n/fork-owned-strings.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Locales we translate into: every `values-<locale>` except English variants. */
    private fun localeFiles(): List<File> =
        res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && !it.name.startsWith("values-en") }
            .mapNotNull { File(it, "strings.xml").takeIf(File::exists) }
            .sortedBy { it.parentFile.name }

    @Test
    fun everyForkStringExistsInTheSource() {
        val source = namesIn(File(res, "values/strings.xml"))
        val missing = forkOwned().filterNot { it in source }
        assertEquals("fork-owned-strings.txt names strings that values/strings.xml does not have", emptyList<String>(), missing)
    }

    @Test
    fun everyForkStringIsTranslatedInEveryLocale() {
        val owned = forkOwned()
        val gaps = localeFiles().flatMap { file ->
            val names = namesIn(file)
            owned.filterNot { it in names }.map { "${file.parentFile.name}: $it" }
        }
        assertEquals("fork strings missing from these locales", emptyList<String>(), gaps)
    }

    /**
     * A string no locale translates is what an unregistered fork string looks
     * like. Registering it is the fix; it then has to be translated like the
     * rest. This fork takes no merges from upstream (ADR 0007), so a new
     * upstream string arriving untranslated is a deliberate cherry-pick, not an
     * accident, and adding it here is a conscious act.
     */
    @Test
    fun aStringNoLocaleTranslatesIsRegisteredAsForkOwned() {
        val owned = forkOwned().toSet()
        val locales = localeFiles().map(::namesIn)
        assertTrue("expected the repository to carry locale translations", locales.size > 10)
        val unregistered = namesIn(File(res, "values/strings.xml"))
            .filter { name -> name !in owned && locales.none { name in it } }
            .sorted()
        assertEquals(
            "these strings are translated nowhere; add them to core/i18n/fork-owned-strings.txt and translate them",
            emptyList<String>(), unregistered,
        )
    }
}

package de.mm20.launcher2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * No committed screenshot golden is blank (#251).
 *
 * A Roborazzi capture sometimes comes out as a single flat colour: the
 * window's background, with nothing of the composition drawn. Verifying
 * against a good golden already fails on such a capture. What nothing caught
 * is recording one: a blank written over a good golden and committed turns
 * every later verification into blank against blank, which passes, and the
 * golden has asserted nothing from then on. This test is the gate at that
 * point, on the artifact rather than on the capture, so it catches a blank
 * however it arrived.
 *
 * Its scope is chosen, not overlooked: it rejects a golden of **one colour**,
 * the defect that has been observed. A partial render (a header drawn, the
 * content missing) is out of scope until somebody sees one; widening the rule
 * on a guess would make it a rule about images nobody has. The most uniform
 * legitimate golden when this was written had 716 colours, the fewest 176.
 *
 * It reaches `:app:ui`'s goldens only, under `app/ui/src/test/roborazzi/`: the
 * one module that applies Roborazzi when this was written. A module that gains
 * goldens is not covered by this test and needs its own, or an extension of
 * this one; a guard that covers less than its reputation is how a blank gets
 * through somewhere nobody looks.
 *
 * The directory is a declared input of the test task (build.gradle.kts), or a
 * change to a golden alone would leave the task up to date and this would not
 * run.
 */
class GoldensNotBlankTest {

    private val goldens = File(
        System.getProperty("repoRoot") ?: error("repoRoot is not set (app/ui/build.gradle.kts)"),
        "app/ui/src/test/roborazzi",
    )

    /** True as soon as a second colour turns up; a blank frame never shows one. */
    private fun hasTwoColours(file: File): Boolean {
        val image = ImageIO.read(file) ?: error("not an image: ${file.name}")
        val first = image.getRGB(0, 0)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) != first) return true
        }
        return false
    }

    @Test
    fun `no committed golden is a single colour`() {
        // The whole tree, not only the top level: a golden in a subdirectory is
        // still a golden (review on #255). Named by relative path so nested
        // names stay unambiguous.
        val files = goldens.walkTopDown().filter { it.isFile && it.extension == "png" }.sortedBy { it.path }.toList()
        // A guard over nothing passes: a moved or emptied directory must fail here instead.
        assertTrue("no goldens found under $goldens", files.isNotEmpty())

        val blank = files.filterNot(::hasTwoColours).map { it.relativeTo(goldens).path }

        assertEquals("goldens that are a single colour (a blank capture was recorded)", emptyList<String>(), blank)
    }
}

package de.mm20.launcher2.themes

import de.mm20.launcher2.themes.colors.Colors
import de.mm20.launcher2.themes.colors.DefaultDarkColorScheme
import de.mm20.launcher2.themes.colors.DefaultLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.UUID

/**
 * An imported theme file is untrusted input, and it used to reach logcat
 * whole (#15). What is logged is its shape - which parts it has - never its
 * content. The import must run for the absence to mean anything, so each
 * case first finds the line the import logs, then looks for the payload.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeBundleLoggingTest {

    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    private val bundle = ThemeBundle(
        name = "Secret-theme-name",
        author = "secret-author",
        colors = Colors(
            id = UUID(0L, 43L),
            name = "Secret-colors-name",
            lightColorScheme = DefaultDarkColorScheme,
            darkColorScheme = DefaultLightColorScheme,
        ),
    )

    @Test
    fun `an imported bundle logs its shape, not its content`() {
        val imported = ThemeBundle.fromJson(bundle.toJson())

        assertNotNull(imported)
        val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }
        // The import ran and said so: otherwise the absence below proves nothing.
        assertEquals(lines.toString(), 1, lines.count { "Theme bundle read" in it })
        for (payload in listOf("Secret-theme-name", "secret-author", "Secret-colors-name")) {
            assertTrue("$payload in $lines", lines.none { payload in it })
        }
    }

    @Test
    fun `the shape says which parts the bundle has`() {
        ThemeBundle.fromJson(bundle.toJson())

        val line = ShadowLog.getLogs().single { "Theme bundle read" in it.msg }.msg
        assertTrue(line, "colors=true" in line)
        assertTrue(line, "typography=false" in line)
        assertTrue(line, "shapes=false" in line)
    }
}

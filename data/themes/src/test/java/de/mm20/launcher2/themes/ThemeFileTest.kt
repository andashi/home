package de.mm20.launcher2.themes

import de.mm20.launcher2.themes.colors.Colors
import de.mm20.launcher2.themes.colors.DefaultDarkColorScheme
import de.mm20.launcher2.themes.colors.DefaultLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Reading a theme file handed to the launcher (#15). The import activity is
 * exported, so what arrives is untrusted: only a content URI is opened, the
 * read stops at [ThemeFile.MAX_BYTES], and every way it can fail ends in a
 * rejection the screen shows - never a crash, a spinner that does not stop,
 * or the file's content in the log.
 *
 * Each rejection is asserted by its reason, not as "the import failed": a
 * failure for an unrelated reason would otherwise pass for the one a case is
 * about. The fakes are able to give what the code must refuse - a stream past
 * the cap, one that never ends, one that throws - since a fixture the code
 * accepts anyway proves nothing about the refusal.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeFileTest {

    private val bundle = ThemeBundle(
        name = "Lagoon",
        author = "andashi",
        colors = Colors(
            id = UUID(0L, 7L),
            name = "Lagoon",
            lightColorScheme = DefaultLightColorScheme,
            darkColorScheme = DefaultDarkColorScheme,
        ),
    )
    private val exported: ByteArray = bundle.toJson().toByteArray()

    private fun read(scheme: String? = "content", open: () -> InputStream?) = ThemeFile.read(scheme, open = open)

    private fun rejected(reason: ThemeFileRejection) = ThemeFileResult.Rejected(reason)

    @Test
    fun `a content URI with an exported theme is read`() {
        // A control: the path every other case refuses.
        val result = read { ByteArrayInputStream(exported) }
        assertEquals(ThemeFileResult.Read(bundle), result)
    }

    @Test
    fun `a file URI is refused before anything is opened`() {
        var opened = false
        val result = read("file") { opened = true; ByteArrayInputStream(exported) }
        assertEquals(rejected(ThemeFileRejection.NotContent), result)
        assertFalse("a file URI must not be opened", opened)
    }

    @Test
    fun `any scheme but content is refused, a missing one too`() {
        for (scheme in listOf("file", "https", "http", "android.resource", "FILE", null)) {
            var opened = false
            assertEquals("scheme $scheme", rejected(ThemeFileRejection.NotContent), read(scheme) { opened = true; ByteArrayInputStream(exported) })
            assertFalse("scheme $scheme was opened", opened)
        }
    }

    @Test
    fun `a file one byte over the cap is refused`() {
        val padded = exported + ByteArray(ThemeFile.MAX_BYTES + 1 - exported.size) { ' '.code.toByte() }
        assertEquals(ThemeFile.MAX_BYTES + 1, padded.size)
        assertEquals(rejected(ThemeFileRejection.TooLarge), read { ByteArrayInputStream(padded) })
    }

    @Test
    fun `a file of exactly the cap is read`() {
        // The boundary, and a control for the case above: the same theme,
        // padded with whitespace JSON allows, one byte shorter.
        val padded = exported + ByteArray(ThemeFile.MAX_BYTES - exported.size) { ' '.code.toByte() }
        assertEquals(ThemeFile.MAX_BYTES, padded.size)
        assertEquals(ThemeFileResult.Read(bundle), read { ByteArrayInputStream(padded) })
    }

    @Test(timeout = 10_000)
    fun `a stream that never ends is refused, and the read stops at the cap`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return ' '.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(' '.code.toByte(), off, off + len); served += len; return len
            }
        }
        assertEquals(rejected(ThemeFileRejection.TooLarge), read { endless })
        assertTrue("read $served bytes of an endless stream", served <= ThemeFile.MAX_BYTES + 8192L)
    }

    @Test
    fun `a stream the resolver cannot open is refused`() {
        assertEquals(rejected(ThemeFileRejection.Unreadable), read { null })
    }

    @Test
    fun `a stream that fails while it is read is refused`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("pipe broke")
        }
        assertEquals(rejected(ThemeFileRejection.Unreadable), read { failing })
    }

    @Test
    fun `a URI the launcher may not open is refused`() {
        assertEquals(rejected(ThemeFileRejection.Unreadable), read { throw SecurityException("no grant") })
    }

    @Test
    fun `a file that does not exist is refused`() {
        assertEquals(rejected(ThemeFileRejection.Unreadable), read { throw java.io.FileNotFoundException("gone") })
    }

    @Test
    fun `a file that is not a theme is refused`() {
        assertEquals(rejected(ThemeFileRejection.NotATheme), read { ByteArrayInputStream("{ not json".toByteArray()) })
        assertEquals(rejected(ThemeFileRejection.NotATheme), read { ByteArrayInputStream("[1, 2, 3]".toByteArray()) })
    }

    @Test
    fun `neither a rejected nor an accepted file reaches the log`() {
        val marker = "PAYLOAD-7f3a91"
        ShadowLog.clear()
        read { ByteArrayInputStream("""{"version": 2, "name": "$marker", "colors": {"id": "$marker"}}""".toByteArray()) }
        read { ByteArrayInputStream("""{"version": 2, "name": 7 $marker""".toByteArray()) }
        read { ByteArrayInputStream(ThemeBundle(name = marker, author = marker).toJson().toByteArray()) }
        val leaked = ShadowLog.getLogs().filter { marker in it.msg || (it.throwable?.toString()?.contains(marker) == true) }
        assertEquals("log lines carrying the file's content", emptyList<String>(), leaked.map { "${it.tag}: ${it.msg}" })
    }
}

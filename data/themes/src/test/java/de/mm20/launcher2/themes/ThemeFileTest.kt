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

    private fun read(scheme: String? = "content", open: () -> InputStream?) = ThemeFile.read(scheme, open)

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
    fun `a provider that throws on open is refused`() {
        // #234 review: a provider can answer openInputStream with a runtime
        // exception, not only with the IO and security ones.
        assertEquals(rejected(ThemeFileRejection.Unreadable), read { throw IllegalArgumentException("Unknown URI") })
        assertEquals(rejected(ThemeFileRejection.Unreadable), read { throw IllegalStateException("provider died") })
    }

    @Test(timeout = 20_000)
    fun `a stream that stalls is refused within the time bound, and closed`() {
        // A pipe that never delivers data or its end: the byte cap does not
        // bound the time (#234 review). The fake blocks until it is closed,
        // then fails the read, as a descriptor closed under a blocked read
        // does on Android.
        val closed = java.util.concurrent.CountDownLatch(1)
        val stalled = object : InputStream() {
            override fun read(): Int {
                // A read on a pipe ignores interrupts; only closing the
                // descriptor ends it.
                awaitUninterruptibly(closed)
                throw IOException("closed")
            }
            override fun close() = closed.countDown()
        }
        val started = System.nanoTime()
        val result = ThemeFile.read("content", open = { stalled }, maxBytes = ThemeFile.MAX_BYTES, timeoutMillis = 300, slots = ownSlot())
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(rejected(ThemeFileRejection.TooSlow), result)
        assertTrue("took $tookMs ms for a 300 ms bound", tookMs < 5_000)
        // Closed off the caller's thread (a close may block), so awaited.
        assertTrue("the stalled stream was not closed", closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test(timeout = 20_000)
    fun `a stream that opens only after the time bound is closed as well`() {
        // #234 review: the provider answers openInputStream after the caller
        // has given up, so there was no stream to close at the timeout. The
        // late stream stalls as the one above does; it must still be closed,
        // or its descriptor and the worker stay alive.
        val callerGaveUp = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val stalled = object : InputStream() {
            override fun read(): Int {
                // A read on a pipe ignores interrupts; only closing the
                // descriptor ends it.
                awaitUninterruptibly(closed)
                throw IOException("closed")
            }
            override fun close() = closed.countDown()
        }
        val result = ThemeFile.read(
            "content",
            // A binder call into the provider's openFile is not interrupted
            // by the caller giving up; it returns when the provider answers.
            open = { awaitUninterruptibly(callerGaveUp); stalled },
            maxBytes = ThemeFile.MAX_BYTES,
            timeoutMillis = 300,
            slots = ownSlot(),
        )
        assertEquals(rejected(ThemeFileRejection.TooSlow), result)
        callerGaveUp.countDown()
        assertTrue(
            "a stream opened after the time bound was never closed",
            closed.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a stream whose close blocks does not hold the caller past the time bound`() {
        // #234 review: close() has no promise to return promptly, and a
        // provider's descriptor can hold it. The caller must still answer
        // TooSlow on time; closing is not its to wait for.
        val release = java.util.concurrent.CountDownLatch(1)
        val closeCalled = java.util.concurrent.CountDownLatch(1)
        val stuck = object : InputStream() {
            override fun read(): Int {
                awaitUninterruptibly(release)
                throw IOException("closed")
            }
            override fun close() {
                closeCalled.countDown()
                awaitUninterruptibly(release)
            }
        }
        // The read runs on a helper thread and the test waits for it with a
        // bound, so a caller stuck in close fails this case instead of
        // holding the test JVM; the fake is released either way.
        val answer = java.util.concurrent.ArrayBlockingQueue<ThemeFileResult>(1)
        Thread {
            answer.put(ThemeFile.read("content", open = { stuck }, maxBytes = ThemeFile.MAX_BYTES, timeoutMillis = 300, slots = ownSlot()))
        }.apply { isDaemon = true }.start()
        try {
            val result = answer.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("no answer within 5 s for a 300 ms bound", rejected(ThemeFileRejection.TooSlow), result)
            assertTrue("close was never attempted", closeCalled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `while a provider still holds a worker, the next file is refused without opening it`() {
        // #234 review: a provider that never answers openFile keeps its worker
        // however the caller gives up, and the import activity is exported,
        // so repeated imports could pile such workers up. One open at a time:
        // the next is refused at once, and a free slot takes files again.
        val slots = java.util.concurrent.Semaphore(1)
        val answer = java.util.concurrent.CountDownLatch(1)
        val hung = ThemeFile.read(
            "content",
            open = { awaitUninterruptibly(answer); ByteArrayInputStream(exported) },
            maxBytes = ThemeFile.MAX_BYTES,
            timeoutMillis = 200,
            slots = slots,
        )
        assertEquals(rejected(ThemeFileRejection.TooSlow), hung)
        var opened = false
        val next = ThemeFile.read(
            "content",
            open = { opened = true; ByteArrayInputStream(exported) },
            maxBytes = ThemeFile.MAX_BYTES,
            timeoutMillis = 200,
            slots = slots,
        )
        assertEquals(rejected(ThemeFileRejection.Busy), next)
        assertFalse("the next file was opened while the slot was taken", opened)
        answer.countDown()
        // The hung worker finishes and gives its slot back.
        assertTrue("the slot never came back", slots.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS))
        slots.release()
        val after = ThemeFile.read(
            "content",
            open = { ByteArrayInputStream(exported) },
            maxBytes = ThemeFile.MAX_BYTES,
            timeoutMillis = 5_000,
            slots = slots,
        )
        assertEquals(ThemeFileResult.Read(bundle), after)
    }

    /**
     * A slot of its own for a case that leaves a worker running past its
     * end: on the shared one, the next case could find it still taken and be
     * refused as Busy.
     */
    private fun ownSlot() = java.util.concurrent.Semaphore(1)

    private fun awaitUninterruptibly(latch: java.util.concurrent.CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
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

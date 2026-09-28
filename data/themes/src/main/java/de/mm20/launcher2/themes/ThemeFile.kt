package de.mm20.launcher2.themes

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Why a theme file handed to the launcher was not read. */
enum class ThemeFileRejection { NotContent, Unreadable, TooLarge, NotATheme, TooSlow }

sealed interface ThemeFileResult {
    data class Read(val bundle: ThemeBundle) : ThemeFileResult
    data class Rejected(val reason: ThemeFileRejection) : ThemeFileResult
}

/**
 * Reads a theme file handed to the launcher (#15). The import activity is
 * exported, so any app can send it a URI: only a content URI is opened, the
 * read stops at [MAX_BYTES], and every failure is a [ThemeFileResult.Rejected]
 * the import screen shows, never an exception.
 *
 * Nothing of the file reaches the log: a rejection logs its reason and the
 * exception's class, never a message, since kotlinx.serialization quotes
 * the input in its messages.
 */
object ThemeFile {
    /**
     * The largest theme file read. A theme is text that names its fonts
     * rather than embedding them: the largest realistic bundle - every field
     * set, the full colour schemes and text styles, all nine shapes and
     * 200-character names - exports as 5,902 bytes, a colours-only one as
     * 1,915 and a legacy one as 411. 64 KiB is eleven times the largest, room
     * for the format to grow, and still nothing to hold in memory.
     */
    const val MAX_BYTES = 64 * 1024

    /**
     * How long opening and reading may take before the file is refused. The
     * byte cap does not bound time: a provider can serve a pipe that never
     * delivers data or its end. A local file reads in milliseconds; 30 s
     * leaves a documents provider that fetches the file from a server when
     * it is opened the time to do so.
     */
    const val TIMEOUT_MILLIS = 30_000L

    private const val TAG = "ThemeFile"

    /**
     * Reads the theme file behind a URI.
     *
     * @param scheme the URI's scheme; anything but `content` is refused
     *   before [open] is called. The manifest's intent filter says the same,
     *   but an explicit intent to an exported activity bypasses its filters.
     * @param open opens the stream, typically `contentResolver.openInputStream`.
     *   It runs on a worker thread, bounded by [TIMEOUT_MILLIS].
     */
    fun read(scheme: String?, open: () -> InputStream?): ThemeFileResult =
        read(scheme, open, MAX_BYTES, TIMEOUT_MILLIS)

    /**
     * [read] with its bounds as parameters, for the tests. Internal: a
     * caller outside this module cannot lift them (#234 review).
     */
    internal fun read(
        scheme: String?,
        open: () -> InputStream?,
        maxBytes: Int,
        timeoutMillis: Long,
    ): ThemeFileResult {
        if (scheme != "content") return rejected(ThemeFileRejection.NotContent, null)
        val opened = AtomicReference<InputStream?>(null)
        val gaveUp = AtomicBoolean(false)
        val task = FutureTask { readBytes(open, maxBytes, opened, gaveUp) }
        Thread(task, "theme-file-read").apply { isDaemon = true }.start()
        val bytes = try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Closing the stream fails a read blocked on it, which frees the
            // worker. A provider still inside openFile has handed over no
            // stream yet: the worker closes that one itself once it arrives
            // (see readBytes), and the caller does not wait for it.
            giveUp(task, opened, gaveUp)
            return rejected(ThemeFileRejection.TooSlow, null)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            giveUp(task, opened, gaveUp)
            return rejected(ThemeFileRejection.Unreadable, e)
        } catch (e: ExecutionException) {
            // The worker's exception: the stream could not be opened or read.
            return rejected(ThemeFileRejection.Unreadable, e.cause as? Exception)
        }
        val content = when (bytes) {
            is Bytes.Read -> bytes.content
            is Bytes.Rejected -> return rejected(bytes.reason, bytes.cause)
        }
        val bundle = try {
            ThemeBundle.fromJson(content.toString(Charsets.UTF_8))
        } catch (e: RuntimeException) {
            // Untrusted input: whatever a serializer throws on it is a file
            // that is not a theme, not a crash.
            return rejected(ThemeFileRejection.NotATheme, e)
        }
        return if (bundle != null) ThemeFileResult.Read(bundle) else rejected(ThemeFileRejection.NotATheme, null)
    }

    private sealed interface Bytes {
        class Read(val content: ByteArray) : Bytes
        class Rejected(val reason: ThemeFileRejection, val cause: Exception?) : Bytes
    }

    /**
     * Runs on the worker: opens, reads at most [max] + 1 bytes, closes.
     * Whatever it throws - an IOException, a SecurityException, anything a
     * provider answers openInputStream with - reaches [read] as an
     * ExecutionException, the one place that turns it into a rejection.
     */
    private fun readBytes(
        open: () -> InputStream?,
        max: Int,
        opened: AtomicReference<InputStream?>,
        gaveUp: AtomicBoolean,
    ): Bytes {
        val stream = open() ?: return Bytes.Rejected(ThemeFileRejection.Unreadable, null)
        // Published first, checked second; giveUp does the reverse. Whichever
        // runs last sees the other, so a stream that opens after the caller
        // gave up is closed here and never read (#234 review).
        opened.set(stream)
        if (gaveUp.get()) {
            closeQuietly(stream)
            return Bytes.Rejected(ThemeFileRejection.TooSlow, null)
        }
        return stream.use { readAtMost(it, max) }
            ?.let { Bytes.Read(it) }
            ?: Bytes.Rejected(ThemeFileRejection.TooLarge, null)
    }

    /** The stream's bytes, or null once it holds more than [max]. Reads at most [max] + 1. */
    private fun readAtMost(input: InputStream, max: Int): ByteArray? {
        val buffer = ByteArray(max + 1)
        var filled = 0
        while (filled < buffer.size) {
            val n = input.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled > max) null else buffer.copyOf(filled)
    }

    /**
     * The caller's half of the handshake in [readBytes]: flag first, close
     * second. The close runs on a thread of its own: close() has no promise
     * to return promptly, and the caller answers on time whatever it does
     * (#234 review).
     */
    private fun giveUp(task: FutureTask<*>, opened: AtomicReference<InputStream?>, gaveUp: AtomicBoolean) {
        gaveUp.set(true)
        task.cancel(true)
        val stream = opened.get() ?: return
        Thread({ closeQuietly(stream) }, "theme-file-close").apply { isDaemon = true }.start()
    }

    private fun closeQuietly(stream: InputStream?) {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
    }

    private fun rejected(reason: ThemeFileRejection, e: Exception?): ThemeFileResult {
        Log.w(TAG, "theme file rejected: $reason${e?.let { " (${it.javaClass.simpleName})" } ?: ""}")
        return ThemeFileResult.Rejected(reason)
    }
}

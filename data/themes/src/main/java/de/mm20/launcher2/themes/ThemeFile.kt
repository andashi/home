package de.mm20.launcher2.themes

import android.util.Log
import java.io.IOException
import java.io.InputStream

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

    private const val TAG = "ThemeFile"

    /**
     * @param scheme the URI's scheme; anything but `content` is refused
     *   before [open] is called. The manifest's intent filter says the same,
     *   but an explicit intent to an exported activity bypasses its filters.
     * @param open opens the stream, typically `contentResolver.openInputStream`.
     */
    fun read(scheme: String?, open: () -> InputStream?): ThemeFileResult = read(scheme, open, MAX_BYTES, 30_000)

    internal fun read(scheme: String?, open: () -> InputStream?, maxBytes: Int, timeoutMillis: Long): ThemeFileResult {
        if (scheme != "content") return rejected(ThemeFileRejection.NotContent, null)
        val bytes = try {
            val stream = open() ?: return rejected(ThemeFileRejection.Unreadable, null)
            stream.use { readAtMost(it, maxBytes) } ?: return rejected(ThemeFileRejection.TooLarge, null)
        } catch (e: IOException) {
            return rejected(ThemeFileRejection.Unreadable, e)
        } catch (e: SecurityException) {
            return rejected(ThemeFileRejection.Unreadable, e)
        }
        val bundle = try {
            ThemeBundle.fromJson(bytes.toString(Charsets.UTF_8))
        } catch (e: RuntimeException) {
            // Untrusted input: whatever a serializer throws on it is a file
            // that is not a theme, not a crash.
            return rejected(ThemeFileRejection.NotATheme, e)
        }
        return if (bundle != null) ThemeFileResult.Read(bundle) else rejected(ThemeFileRejection.NotATheme, null)
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

    private fun rejected(reason: ThemeFileRejection, e: Exception?): ThemeFileResult {
        Log.w(TAG, "theme file rejected: $reason${e?.let { " (${it.javaClass.simpleName})" } ?: ""}")
        return ThemeFileResult.Rejected(reason)
    }
}

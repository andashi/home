package de.mm20.launcher2.themes

import de.mm20.launcher2.crashreporter.CrashReporter
import java.io.InputStream

/** Why a theme file handed to the launcher was not read. */
enum class ThemeFileRejection { NotContent, Unreadable, TooLarge, NotATheme }

sealed interface ThemeFileResult {
    data class Read(val bundle: ThemeBundle) : ThemeFileResult
    data class Rejected(val reason: ThemeFileRejection) : ThemeFileResult
}

object ThemeFile {
    const val MAX_BYTES = 64 * 1024

    /**
     * Today's behaviour, moved out of ImportThemeSettingsScreenVM.init
     * unchanged, so the tests show what it does before it is changed.
     */
    fun read(scheme: String?, maxBytes: Int = MAX_BYTES, open: () -> InputStream?): ThemeFileResult {
        try {
            val stream = open() ?: error("no result: the import screen keeps loading")
            stream.reader().use {
                val theme = ThemeBundle.fromJson(it.readText())
                return if (theme != null) ThemeFileResult.Read(theme) else ThemeFileResult.Rejected(ThemeFileRejection.NotATheme)
            }
        } catch (e: SecurityException) {
            CrashReporter.logException(e)
            return ThemeFileResult.Rejected(ThemeFileRejection.Unreadable)
        }
    }
}

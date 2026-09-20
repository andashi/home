package de.mm20.launcher2.crashreporter

import android.util.Log

/**
 * Records an exception that was caught and handled, so that a caller does not
 * have to choose between swallowing it and crashing.
 *
 * This used to hand the exception to a vendored copy of the
 * com.balsikandar.crashreporter library as well, which persisted reports to
 * external storage and surfaced them in a debug screen. That went with the
 * module diet (#20): the persisted copy covered nothing that `adb logcat` and
 * the platform's own crash records do not, and it carried an arbitrary file
 * read through SettingsActivity (#6) and a database dump to external storage
 * (#12) along with it.
 *
 * The facade stays rather than being inlined at its ~77 call sites, so that
 * removing the library did not have to touch any of them.
 *
 * Behaviour is unchanged: the library call was already skipped for
 * CancellationException while this log line ran for every exception, and it
 * still does. Whether cancellation deserves an error log at all is a separate
 * question from removing the reporter.
 */
object CrashReporter {
    fun logException(e: Exception) {
        Log.e("MM20", Log.getStackTraceString(e))
    }
}

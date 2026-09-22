package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What one [GridWriteBack.write] did to `launcher.json`. */
sealed class WriteBackResult {
    /** The file was replaced; [sha256] is the hash of the bytes now on disk. */
    data class Written(val sha256: String) : WriteBackResult()

    /**
     * The database was updated but the file was left alone. [code] is
     * stable and machine-readable, [reason] is for a log line or a snackbar.
     */
    data class Skipped(val code: String, val reason: String) : WriteBackResult()
}

/**
 * Writes the home grid back into `launcher.json` after an edit-mode change
 * (ADR 0003, revised 2026-09-22: the config is the source of truth in both
 * directions).
 *
 * Skips are reported through [lastResult] and the log, not through the
 * reload report: a skip must never overwrite the last good report, which is
 * what the read-back provider and the startup check rely on.
 */
class GridWriteBack(
    context: Context,
    private val repository: HomeGridRepository,
    private val configStore: ConfigStore,
    private val reportStore: ReloadReportStore,
    private val lock: ConfigFileLock,
    private val fileProvider: () -> File? = { ConfigLocation.configFile(context.applicationContext) },
) {
    private val _lastResult = MutableStateFlow<WriteBackResult?>(null)

    /** The outcome of the most recent [write], for the UI to show a skip. */
    val lastResult: StateFlow<WriteBackResult?> = _lastResult

    suspend fun write(layout: String, items: List<HomeGridItem>): WriteBackResult = TODO()
}

package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ReloadReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/**
 * Fork addition (Phase 2, ADR 0003): persists the [ReloadReport] of the
 * latest config reload as JSON in app-internal storage
 * (`files/config/last-reload-report.json`), so the read-back provider
 * (next milestone) can serve the diagnostics of the last reload.
 *
 * Writes are atomic (write to a temp file, then rename); a failed rename
 * keeps the previous report and throws. A missing or corrupt report reads
 * back as null.
 */
class ReloadReportStore(
    context: Context,
) {
    private val file = File(context.filesDir, "config/last-reload-report.json")

    suspend fun save(report: ReloadReport) = withContext(Dispatchers.IO) {
        // Never written in place: a concurrent provider query could read a
        // torn report. A failed rename keeps the previous one and throws.
        file.replaceAtomically(ConfigParser.json.encodeToString(ReloadReport.serializer(), report))
    }

    suspend fun read(): ReloadReport? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            ConfigParser.json.decodeFromString(ReloadReport.serializer(), file.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}

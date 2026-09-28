package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.agreesWithTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.UUID

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

    /**
     * The store's own record of its identity and the last number it gave,
     * apart from the report: an unreadable report must not restart the count.
     * It lives with the report, so what wipes one (`pm clear`, a reinstall)
     * wipes both, and a missing or unreadable record is a new store.
     */
    private val numbering = File(context.filesDir, "config/report-numbering.json")

    @Serializable
    private data class Numbering(val storeId: String, val sequence: Long)

    /**
     * Saves [report] numbered one up from the last one, under this store's
     * id, and returns it as saved: the number is the store's, whatever the
     * report carries (write-back saves an edited copy of the last one).
     */
    suspend fun save(report: ReloadReport): ReloadReport = withContext(Dispatchers.IO) {
        NumberingLock.withLock {
            val last = readNumbering()
            val next = Numbering(last?.storeId ?: UUID.randomUUID().toString(), (last?.sequence ?: 0) + 1)
            val numbered = report.copy(sequence = next.sequence, storeId = next.storeId)
            // The record first: a report saved under a number the record does
            // not hold yet would be given that number again by the next save.
            numbering.replaceAtomically(ConfigParser.json.encodeToString(Numbering.serializer(), next))
            // Never written in place: a concurrent provider query could read a
            // torn report. A failed rename keeps the previous one and throws.
            file.replaceAtomically(ConfigParser.json.encodeToString(ReloadReport.serializer(), numbered))
            numbered
        }
    }

    private fun readNumbering(): Numbering? = try {
        if (numbering.exists()) ConfigParser.json.decodeFromString(Numbering.serializer(), numbering.readText()) else null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IOException) {
        null
    }

    private companion object {
        /** One count per process: reloads and write-backs save through separate instances. */
        val NumberingLock = Mutex()
    }

    suspend fun read(): ReloadReport? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            ConfigParser.json.decodeFromString(ReloadReport.serializer(), file.readText())
                // Written under an older severity table (a report survives an
                // update): no report of this build's, so the startup check
                // reloads once and writes one (review on #215).
                .takeIf { report -> report.diagnostics.all { it.agreesWithTable } }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}

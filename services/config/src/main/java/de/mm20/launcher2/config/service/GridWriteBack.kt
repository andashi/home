package de.mm20.launcher2.config.service

import android.content.Context
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridRepository
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * An edit-mode change of the home grid, written to the device and then into
 * `launcher.json` (ADR 0003).
 *
 * The lock first, then the database, then the file, all under the one
 * [ConfigFileLock]: a reload that already read the old file must not apply
 * its grid over the new rows between the two writes (review on #68). The
 * file half is [ConfigWriteBack]'s, the same engine every other section's
 * change goes through.
 */
class GridWriteBack(
    context: Context,
    private val repository: HomeGridRepository,
    configStore: ConfigStore,
    reportStore: ReloadReportStore,
    private val lock: ConfigFileLock,
    fileProvider: () -> File? = { ConfigLocation.configFile(context.applicationContext) },
    baselineStore: AppliedBaselineStore = AppliedBaselineStore(context),
    private val engine: ConfigWriteBack =
        ConfigWriteBack(context, configStore, reportStore, baselineStore, lock, fileProvider),
) {
    /** The outcome of the most recent write-back, for the UI to show a skip. */
    val lastResult: StateFlow<WriteBackResult?> get() = engine.lastResult

    suspend fun write(layout: String, items: List<HomeGridItem>): WriteBackResult = lock.withLock {
        repository.replace(layout, items)
        engine.writeLocked(gridEdit = true)
    }
}

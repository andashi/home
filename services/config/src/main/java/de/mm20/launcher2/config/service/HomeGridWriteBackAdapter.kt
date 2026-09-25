package de.mm20.launcher2.config.service

import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridWriteBack
import de.mm20.launcher2.homegrid.HomeGridWriteResult

/**
 * [HomeGridWriteBack] over [GridWriteBack]: the UI module depends on the
 * grid's interface, not on the config service, so this is where the two
 * meet.
 */
class HomeGridWriteBackAdapter(
    private val writeBack: GridWriteBack,
) : HomeGridWriteBack {
    override suspend fun write(layout: String, items: List<HomeGridItem>): HomeGridWriteResult =
        when (val result = writeBack.write(layout, items)) {
            is WriteBackResult.Written, WriteBackResult.Unchanged -> HomeGridWriteResult.Written
            is WriteBackResult.Skipped -> HomeGridWriteResult.Skipped(result.code, result.reason)
        }
}

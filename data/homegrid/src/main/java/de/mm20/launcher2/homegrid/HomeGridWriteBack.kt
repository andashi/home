package de.mm20.launcher2.homegrid

/** What writing a layout back did to `launcher.json` (ADR 0003, section 5). */
sealed class HomeGridWriteResult {
    /** The database and the file now describe [items] alike. */
    data object Written : HomeGridWriteResult()

    /**
     * The database was updated but the file was left alone; [code] is
     * stable and machine-readable, [reason] fit for a snackbar.
     */
    data class Skipped(val code: String, val reason: String) : HomeGridWriteResult()
}

/**
 * The grid's way out of edit mode: persist [items] as the whole [layout]
 * and mirror them into the config file. The implementation lives with the
 * config service (`GridWriteBack`); this interface keeps the UI free of it
 * and lets tests record what was written.
 */
interface HomeGridWriteBack {
    suspend fun write(layout: String, items: List<HomeGridItem>): HomeGridWriteResult
}

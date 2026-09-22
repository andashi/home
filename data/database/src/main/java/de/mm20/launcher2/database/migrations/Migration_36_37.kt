package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Drops the `Widget` table of the old widget column (PR 5b, 2026-09-22).
 *
 * The home screen is the grid (`HomeGridItem`, ADR 0001) and the widget
 * pages reached by gestures are gone with the column, so nothing reads the
 * table any more. Its rows are not converted: there was never a stable
 * release whose widgets would need carrying over, and the one row every
 * install had, the favorites widget, is what `HomeGridDefaults` writes into
 * the grid on a never-configured launcher.
 */
class Migration_36_37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        TODO("PR 5b")
    }
}

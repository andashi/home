package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `HomeGridItem` table for the single-page home grid (ADR 0001,
 * revised 2026-09-22; andashi/home#23).
 *
 * The table starts empty. Converting the existing `Widget` rows of the home
 * screen into grid items is not done here on purpose: the target cells depend
 * on the cell size in dp, which only the display knows, so that conversion
 * runs once from the UI (`HomeGridSeeder`) and not in SQL. The `Widget` table
 * stays as it is; the secondary widget pages reached by gestures keep using it.
 *
 * The statement must match the schema Room exports for version 36 exactly
 * (`schemas/…/36.json`), which `MigrationTest` validates.
 */
class Migration_35_36 : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `HomeGridItem` (" +
                    "`layout` TEXT NOT NULL, " +
                    "`id` TEXT NOT NULL, " +
                    "`widget` TEXT NOT NULL, " +
                    "`profile` TEXT, " +
                    "`x` INTEGER NOT NULL, " +
                    "`y` INTEGER NOT NULL, " +
                    "`w` INTEGER NOT NULL, " +
                    "`h` INTEGER NOT NULL, " +
                    "`appWidgetId` INTEGER, " +
                    "`config` TEXT, " +
                    "`position` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`layout`, `id`))"
        )
    }
}

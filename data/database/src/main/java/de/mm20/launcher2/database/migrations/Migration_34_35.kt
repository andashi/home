package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Drops the tables of features this fork no longer has (ADR 0008).
 *
 * - `Plugins`: the plugin system is gone, so nothing reads or writes the
 *   enabled-plugin records any more.
 * - `forecasts`: cached weather forecasts, orphaned when the weather module
 *   was removed.
 * - `Currency`: cached exchange rates, orphaned when the currency converter
 *   was removed.
 *
 * All three are pure caches or feature state, so dropping them loses nothing
 * the user would miss. Note that a downgrade is not supported either way.
 *
 * Also deletes the `Widget` rows of those features. Their types are no longer
 * known to `Widget.fromDatabaseEntity`, which returns null for them, so they
 * were invisible but still occupied positions in the widget column.
 */
class Migration_34_35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `Plugins`")
        db.execSQL("DROP TABLE IF EXISTS `forecasts`")
        db.execSQL("DROP TABLE IF EXISTS `Currency`")
        db.execSQL(
            "DELETE FROM `Widget` WHERE `type` IN " +
                    "('weather', 'music', 'calendar', 'notes', 'clock')"
        )
    }
}

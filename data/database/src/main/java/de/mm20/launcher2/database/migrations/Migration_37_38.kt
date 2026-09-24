package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Drops the `Transparencies` table of upstream's transparency scheme (#97).
 *
 * Nothing on the launcher reads it any more: the home screen and search are
 * glass (ADR 0004), the config file stopped feeding it with #73, and the
 * scheme's settings screens are removed. The rows are not carried over;
 * there is nothing left to apply them to.
 */
class Migration_37_38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `Transparencies`")
    }
}

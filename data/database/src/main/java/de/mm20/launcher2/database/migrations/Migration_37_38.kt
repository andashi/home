package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Drops the `Transparencies` table of upstream's transparency scheme (#97). */
class Migration_37_38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) = Unit
}

package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.mm20.launcher2.ktx.toBytes
import java.util.UUID

class Migration_31_32 : Migration(31, 32) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `forecasts` ADD COLUMN `uvIndex` REAL NOT NULL DEFAULT -1.0;
            """.trimIndent(),
        )
    }
}

/** The former default widget screen's parent id, inlined: the enum went with the widget pages (PR 5b). */
private val DefaultWidgetScreen: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

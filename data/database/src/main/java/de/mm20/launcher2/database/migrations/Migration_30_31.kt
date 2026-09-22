package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.mm20.launcher2.ktx.toBytes
import java.util.UUID

class Migration_30_31 : Migration(30, 31) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE Widget 
            SET parentId = ? 
            WHERE parentId IS NULL    
            """.trimIndent(),
            arrayOf(DefaultWidgetScreen.toBytes()),
        )
    }
}

/** The former default widget screen's parent id, inlined: the enum went with the widget pages (PR 5b). */
private val DefaultWidgetScreen: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

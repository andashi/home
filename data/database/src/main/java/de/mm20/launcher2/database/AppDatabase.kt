@file:Suppress("ClassName")

package de.mm20.launcher2.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import de.mm20.launcher2.database.daos.ThemeDao
import de.mm20.launcher2.database.entities.ColorsEntity
import de.mm20.launcher2.database.entities.CustomAttributeEntity
import de.mm20.launcher2.database.entities.HomeGridItemEntity
import de.mm20.launcher2.database.entities.IconEntity
import de.mm20.launcher2.database.entities.IconPackEntity
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.database.entities.SearchActionEntity
import de.mm20.launcher2.database.entities.ShapesEntity
import de.mm20.launcher2.database.entities.TypographyEntity
import de.mm20.launcher2.database.migrations.Migration_10_11
import de.mm20.launcher2.database.migrations.Migration_11_12
import de.mm20.launcher2.database.migrations.Migration_12_13
import de.mm20.launcher2.database.migrations.Migration_13_14
import de.mm20.launcher2.database.migrations.Migration_14_15
import de.mm20.launcher2.database.migrations.Migration_15_16
import de.mm20.launcher2.database.migrations.Migration_16_17
import de.mm20.launcher2.database.migrations.Migration_17_18
import de.mm20.launcher2.database.migrations.Migration_18_19
import de.mm20.launcher2.database.migrations.Migration_19_20
import de.mm20.launcher2.database.migrations.Migration_20_21
import de.mm20.launcher2.database.migrations.Migration_21_22
import de.mm20.launcher2.database.migrations.Migration_22_23
import de.mm20.launcher2.database.migrations.Migration_23_24
import de.mm20.launcher2.database.migrations.Migration_24_25
import de.mm20.launcher2.database.migrations.Migration_25_26
import de.mm20.launcher2.database.migrations.Migration_26_27
import de.mm20.launcher2.database.migrations.Migration_27_28
import de.mm20.launcher2.database.migrations.Migration_28_29
import de.mm20.launcher2.database.migrations.Migration_29_30
import de.mm20.launcher2.database.migrations.Migration_30_31
import de.mm20.launcher2.database.migrations.Migration_31_32
import de.mm20.launcher2.database.migrations.Migration_32_33
import de.mm20.launcher2.database.migrations.Migration_33_34
import de.mm20.launcher2.database.migrations.Migration_34_35
import de.mm20.launcher2.database.migrations.Migration_35_36
import de.mm20.launcher2.database.migrations.Migration_36_37
import de.mm20.launcher2.database.migrations.Migration_37_38
import de.mm20.launcher2.database.migrations.Migration_6_7
import de.mm20.launcher2.database.migrations.Migration_7_8
import de.mm20.launcher2.database.migrations.Migration_8_9
import de.mm20.launcher2.database.migrations.Migration_9_10
import de.mm20.launcher2.ktx.toBytes
import java.util.UUID

@Database(
    entities = [
        SavedSearchableEntity::class,
        IconEntity::class,
        IconPackEntity::class,
        CustomAttributeEntity::class,
        SearchActionEntity::class,
        ColorsEntity::class,
        ShapesEntity::class,
        TypographyEntity::class,
        HomeGridItemEntity::class,
    ], version = 38, exportSchema = true
)
@TypeConverters(ComponentNameConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun iconDao(): IconDao

    abstract fun searchableDao(): SearchableDao
    abstract fun homeGridItemDao(): HomeGridItemDao
    abstract fun backupDao(): BackupRestoreDao
    abstract fun customAttrsDao(): CustomAttrsDao

    abstract fun searchActionDao(): SearchActionDao

    abstract fun themeDao(): ThemeDao

    companion object {
        private var _instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            val instance = _instance
                ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "room")
                    //.fallbackToDestructiveMigration()
                    .addCallback(SeedCallback())
                    .addMigrations(
                        Migration_6_7(),
                        Migration_7_8(),
                        Migration_8_9(),
                        Migration_9_10(),
                        Migration_10_11(),
                        Migration_11_12(),
                        Migration_12_13(),
                        Migration_13_14(),
                        Migration_14_15(),
                        Migration_15_16(),
                        Migration_16_17(),
                        Migration_17_18(),
                        Migration_18_19(),
                        Migration_19_20(),
                        Migration_20_21(),
                        Migration_21_22(),
                        Migration_22_23(),
                        Migration_23_24(),
                        Migration_24_25(),
                        Migration_25_26(),
                        Migration_26_27(),
                        Migration_27_28(),
                        Migration_28_29(),
                        Migration_29_30(),
                        Migration_30_31(),
                        Migration_31_32(),
                        Migration_32_33(),
                        Migration_33_34(),
                        Migration_34_35(),
                        Migration_35_36(),
                        Migration_36_37(),
                    Migration_37_38(),
                    ).build()
            if (_instance == null) _instance = instance
            return instance
        }
    }
}

/**
 * Seeds a new database (never an upgraded one) with the launcher's search
 * actions: the built-ins and one neutral web search, which uses the browser's
 * own engine. Upstream also seeded YouTube and Google Play; Andashi does not
 * (decided 2026-09-24, #106). A config can set any list (`search.actions`).
 */
internal class SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        db.execSQL(
            "INSERT INTO `SearchAction` (`position`, `type`) VALUES" +
                    "(0, 'call')," +
                    "(1, 'message')," +
                    "(2, 'email')," +
                    "(3, 'contact')," +
                    "(4, 'alarm')," +
                    "(5, 'timer')," +
                    "(6, 'calendar')," +
                    "(7, 'website')," +
                    "(8, 'websearch')"
        )
    }
}

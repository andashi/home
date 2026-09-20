package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_7_8 : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS `forecasts2` (`timestamp` INTEGER NOT NULL, `temperature` REAL NOT NULL, `minTemp` REAL NOT NULL, `maxTemp` REAL NOT NULL, `pressure` REAL NOT NULL, `humidity` REAL NOT NULL, `icon` INTEGER NOT NULL, `condition` TEXT NOT NULL, `clouds` INTEGER NOT NULL, `windSpeed` REAL NOT NULL, `windDirection` REAL NOT NULL, `rain` REAL NOT NULL, `snow` REAL NOT NULL, `night` INTEGER NOT NULL, `location` TEXT NOT NULL, `provider` TEXT NOT NULL, `providerUrl` TEXT NOT NULL, `rainPropability` INTEGER NOT NULL, `snowProbability` INTEGER NOT NULL, PRIMARY KEY(`timestamp`))")
        database.execSQL("INSERT INTO forecasts2 SELECT *, -1 as rainPropability, -1 as snowPropability FROM forecasts")
        database.execSQL("DROP TABLE forecasts")
        database.execSQL("ALTER TABLE forecasts2 RENAME TO forecasts")
    }
}
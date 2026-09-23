package de.mm20.launcher2.database

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
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
import de.mm20.launcher2.database.migrations.Migration_6_7
import de.mm20.launcher2.database.migrations.Migration_7_8
import de.mm20.launcher2.database.migrations.Migration_8_9
import de.mm20.launcher2.database.migrations.Migration_9_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Template for migration tests. Validates the full migration chain 6 -> 37
 * against the exported schema JSONs in `schemas/` (wired as test assets).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val allMigrations = arrayOf(
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
    )

    @Test
    fun `migrate 6 to 37`() {
        helper.createDatabase(testDb, 6).close()

        val db = helper.runMigrationsAndValidate(testDb, 37, true, *allMigrations)

        assertTrue(db.isOpen)
        db.close()
    }

    @Test
    fun `search action survives migration 24 to 37`() {
        helper.createDatabase(testDb, 24).apply {
            execSQL(
                "INSERT INTO `SearchAction` (`position`, `type`, `data`, `label`, `icon`, `color`, `customIcon`, `options`) " +
                        "VALUES (42, 'website', 'https://example.com', 'Example', NULL, 0, NULL, NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 37, true, *allMigrations.copyOfRange(18, 31))

        db.query("SELECT `data` FROM `SearchAction` WHERE `position` = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://example.com", cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun `migration 34 to 35 drops the tables of removed features`() {
        helper.createDatabase(testDb, 34).apply {
            execSQL("INSERT INTO `Plugins` (`authority`, `label`, `type`, `enabled`, `packageName`, `className`) " +
                    "VALUES ('com.example.p', 'Example', 'FileSearch', 1, 'com.example', 'P')")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 35, true, Migration_34_35())

        for (table in listOf("Plugins", "forecasts", "Currency")) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
                .use { cursor ->
                    assertEquals("table $table should have been dropped", 0, cursor.count)
                }
        }
        db.close()
    }

    @Test
    fun `migration 34 to 35 deletes widget rows of removed features`() {
        helper.createDatabase(testDb, 34).apply {
            execSQL("DELETE FROM `Widget`")
            execSQL(
                "INSERT INTO `Widget` (`type`, `position`, `id`, `parentId`) VALUES " +
                        "('weather', 0, X'00000000000000000000000000000001', NULL)," +
                        "('music', 1, X'00000000000000000000000000000002', NULL)," +
                        "('calendar', 2, X'00000000000000000000000000000003', NULL)," +
                        "('apps', 3, X'00000000000000000000000000000004', NULL)," +
                        "('favorites', 4, X'00000000000000000000000000000005', NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 35, true, Migration_34_35())

        db.query("SELECT `type` FROM `Widget` ORDER BY `position`").use { cursor ->
            val types = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            // 'apps' is rewritten, not kept: Widget.fromDatabaseEntity only
            // knows AppsWidget.Type, which is "favorites"
            assertEquals(listOf("favorites", "favorites"), types)
        }
        db.close()
    }

    @Test
    fun `migration 35 to 36 creates the HomeGridItem table`() {
        helper.createDatabase(testDb, 35).close()

        val db = helper.runMigrationsAndValidate(testDb, 36, true, Migration_35_36())

        db.execSQL(
            "INSERT INTO `HomeGridItem` (`layout`, `id`, `widget`, `profile`, `x`, `y`, `w`, `h`, " +
                    "`appWidgetId`, `config`, `position`) VALUES " +
                    "('phone', 'dock', 'favorites', NULL, 0, 5, 4, 1, NULL, NULL, 0)"
        )
        db.query("SELECT `widget`, `w` FROM `HomeGridItem` WHERE `layout` = 'phone' AND `id` = 'dock'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("favorites", cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
            }
        db.close()
    }

    @Test
    fun `migration 35 to 36 keeps the widget rows`() {
        helper.createDatabase(testDb, 35).apply {
            execSQL("DELETE FROM `Widget`")
            execSQL(
                "INSERT INTO `Widget` (`type`, `position`, `id`, `parentId`) VALUES " +
                        "('favorites', 0, X'00000000000000000000000000000005', X'00000000000000000000000000000001')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 36, true, Migration_35_36())

        db.query("SELECT COUNT(*) FROM `Widget`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun `migration 36 to 37 drops the Widget table and keeps the grid`() {
        helper.createDatabase(testDb, 36).apply {
            execSQL(
                "INSERT INTO `Widget` (`type`, `position`, `id`, `parentId`) VALUES " +
                        "('favorites', 0, X'00000000000000000000000000000001', X'00000000000000000000000000000001')"
            )
            execSQL(
                "INSERT INTO `HomeGridItem` (`layout`, `id`, `widget`, `x`, `y`, `w`, `h`, `position`) VALUES " +
                        "('phone', 'dock', 'favorites', 0, 5, 4, 1, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 37, true, Migration_36_37())

        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'Widget'").use { cursor ->
            assertEquals("table Widget should have been dropped", 0, cursor.count)
        }
        db.query("SELECT `id` FROM `HomeGridItem` WHERE `layout` = 'phone'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("dock", cursor.getString(0))
        }
        db.close()
    }
}

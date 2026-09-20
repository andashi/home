package de.mm20.launcher2.widgets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.preferences.WidgetScreenTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The widgets a fresh database seeds must be widgets this build can read back.
 *
 * `AppDatabase`'s onCreate callback writes the type as a raw SQL string while
 * `Widget.fromDatabaseEntity` matches it against `AppsWidget.Type`. Nothing
 * makes those two agree: seeding `'apps'` where the constant says `"favorites"`
 * compiles, inserts a row, and produces a home screen that is empty for no
 * visible reason, because the row is silently dropped on read. It is also
 * invisible to a check that only looks at the database - the row is there
 * either way.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetSeedTest {

    @Test
    fun `every seeded widget decodes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getInstance(context)

        // the seed writes under the default screen, not at the root
        val seeded = database.widgetDao()
            .queryByParent(WidgetScreenTarget.Default.id, 100, 0).first()

        assertTrue("onCreate should seed at least one widget", seeded.isNotEmpty())
        for (entity in seeded) {
            assertNotNull(
                "seeded widget type '${entity.type}' is not one Widget.fromDatabaseEntity knows",
                Widget.fromDatabaseEntity(entity),
            )
        }
    }

    @Test
    fun `the seeded widget is the favorites widget`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getInstance(context)

        val seeded = database.widgetDao()
            .queryByParent(WidgetScreenTarget.Default.id, 100, 0).first()

        assertEquals(listOf(AppsWidget.Type), seeded.map { it.type })
    }
}

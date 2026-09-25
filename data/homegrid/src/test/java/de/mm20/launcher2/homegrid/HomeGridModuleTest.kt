package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.preferences.preferencesModule
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/** The Koin module wires what the grid needs, and the rows source is one object. */
@RunWith(RobolectricTestRunner::class)
class HomeGridModuleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        stopKoin()
        startKoin {
            androidContext(context)
            modules(
                homeGridModule,
                preferencesModule,
                module {
                    single { database }
                    // UiSettings is internal to construct: the real module provides it.
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        database.close()
    }

    @Test
    fun `every grid dependency resolves and the rows source is the measured one`() {
        val koin = GlobalContext.get()

        assertTrue(koin.get<HomeGridRepository>() is HomeGridRepositoryImpl)
        assertTrue(koin.get<FormFactorDetector>() is AndroidFormFactorDetector)
        assertTrue(koin.get<HomeGridInitFlag>() is UiSettingsInitFlag)
        assertSame(koin.get<MeasuredGridRows>(), koin.get<GridRowsSource>())
        assertSame(koin.get<HomeGridRepository>(), koin.get<HomeGridRepository>())
    }
}

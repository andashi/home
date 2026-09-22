package de.mm20.launcher2.homegrid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UiSettingsSeedFlagTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // A minimal settings file, so DataStore never reaches the Context
        // constructor of LauncherSettingsData (its resource does not resolve
        // from this module under Robolectric; same workaround as app/ui).
        val dir = File(context.filesDir, "datastore").apply { mkdirs() }
        File(dir, "settings.json").writeText("""{"schemaVersion":6}""")
        stopKoin()
        startKoin {
            androidContext(context)
            modules(preferencesModule)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `the flag starts clear and stays set once marked`() = runTest {
        val settings: UiSettings = GlobalContext.get().get()
        val flag = UiSettingsSeedFlag(settings)

        assertFalse(flag.isSeeded())

        flag.markSeeded()

        assertTrue(flag.isSeeded())
        assertTrue(settings.homeGridSeeded.first())
    }
}

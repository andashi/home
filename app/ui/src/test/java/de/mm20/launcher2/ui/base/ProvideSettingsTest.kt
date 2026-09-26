package de.mm20.launcher2.ui.base

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner

/**
 * ProvideSettings holds the first frame until the settings are loaded, so
 * the launcher never draws one frame with default icons and then the stored
 * ones. Characterization: the hold used to hang on the time format, a value
 * nothing reads any more; it has to survive that value's removal.
 */
@RunWith(RobolectricTestRunner::class)
class ProvideSettingsTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun `the first composition already sees the stored icon size, never the default`() {
        val settings: UiSettings = GlobalContext.get().get()
        settings.setGridIconSize(64)
        runBlocking { settings.gridSettings.first { it.iconSize == 64 } }
        val seen = mutableListOf<Int>()

        composeRule.setContent {
            ProvideSettings {
                val size = LocalGridSettings.current.iconSize
                seen += size
                Text("$size")
            }
        }
        composeRule.waitForIdle()

        assertEquals("what the content composed with", listOf(64), seen.distinct())
    }
}

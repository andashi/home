package de.mm20.launcher2.ui.base

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.preferences.ui.SearchUiSettings
import de.mm20.launcher2.ui.locals.LocalFavoritesEnabled
import de.mm20.launcher2.ui.settings.KoinSettingsRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Config

/**
 * #3 D5: a key does one thing. `search.favorites` switches the favorites row
 * in search and nothing else. It used to reach the "Pin to favorites" action
 * on every search result as well, through [LocalFavoritesEnabled], so hiding
 * the row also took away the only way to pin something for the dock.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-normal-long-notround-port-420dpi")
class ProvideSettingsTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun pinActionAvailable(searchFavorites: Boolean): Boolean {
        val settings: SearchUiSettings = GlobalContext.get().get()
        runBlocking {
            settings.setFavorites(searchFavorites)
            // DataStore writes asynchronously; wait until the value is read back.
            while (settings.favorites.first() != searchFavorites) Thread.sleep(10)
        }
        var available: Boolean? = null
        composeRule.setContent {
            ProvideSettings {
                available = LocalFavoritesEnabled.current
            }
        }
        composeRule.waitUntil(5_000) { available != null }
        composeRule.waitForIdle()
        return available!!
    }

    @Test
    fun `the pin action stays when the favorites row in search is off`() {
        assertEquals(true, pinActionAvailable(searchFavorites = false))
    }

    /** Control: on is on, before and after. */
    @Test
    fun `the pin action is there when the favorites row is on`() {
        assertEquals(true, pinActionAvailable(searchFavorites = true))
    }
}

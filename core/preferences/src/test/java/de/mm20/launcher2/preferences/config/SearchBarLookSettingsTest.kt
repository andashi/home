package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.SystemBarIcons
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import de.mm20.launcher2.preferences.SearchBarColors
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.preferences.seedSettingsFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `home.searchBar.hidden` and `.colors` (#229) at the bridge, every value in
 * both directions. SettingsContractTest's round trip proves a field moves its
 * key, not which way: a bridge reading hidden as shown, or light as dark,
 * agrees with itself through every stage and passes it.
 */
@RunWith(RobolectricTestRunner::class)
class SearchBarLookSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val styles = mapOf(SearchBarStyle.Transparent to false, SearchBarStyle.Hidden to true)
    private val colors = mapOf(
        SearchBarColors.Auto to SystemBarIcons.Auto,
        SearchBarColors.Light to SystemBarIcons.Light,
        SearchBarColors.Dark to SystemBarIcons.Dark,
    )

    @Test
    fun `the tables name every stored value once`() {
        assertEquals(SearchBarStyle.entries.toSet(), styles.keys)
        assertEquals(SearchBarColors.entries.toSet(), colors.keys)
        assertEquals(SystemBarIcons.entries.toSet(), colors.values.toSet())
    }

    @Test
    fun `each stored value reads back as its word, and each word writes its value`() = runBlocking {
        seedSettingsFile(context, LauncherSettingsData())
        val store = LauncherDataStore(context)
        val bridge = LauncherConfigSettingsImpl(store)

        for ((style, hidden) in styles) {
            store.updateAndAwait { it.copy(searchBarStyle = style) }
            assertEquals("$style reads", hidden, bridge.readState().searchBarHidden)
            store.updateAndAwait { it.copy(searchBarStyle = if (hidden) SearchBarStyle.Transparent else SearchBarStyle.Hidden) }
            bridge.apply(listOf(ConfigMutation.SetSearchBarHidden(hidden)))
            assertEquals("hidden=$hidden writes", style, store.data.first().searchBarStyle)
        }
        for ((stored, word) in colors) {
            store.updateAndAwait { it.copy(searchBarColors = stored) }
            assertEquals("$stored reads", word, bridge.readState().searchBarColors)
            // From another value each time, so a write that does nothing is seen.
            store.updateAndAwait { it.copy(searchBarColors = if (stored == SearchBarColors.Light) SearchBarColors.Dark else SearchBarColors.Light) }
            bridge.apply(listOf(ConfigMutation.SetSearchBarColors(word)))
            assertEquals("$word writes", stored, store.data.first().searchBarColors)
        }
    }
}

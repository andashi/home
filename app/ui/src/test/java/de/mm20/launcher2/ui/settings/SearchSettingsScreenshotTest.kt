package de.mm20.launcher2.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.ui.settings.search.SearchSettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 golden for the search settings.
 *
 * This screen silently lost its Favorites, Apps, App shortcuts and Contacts
 * entries in the step-4 removal: the values were still collected from the view
 * model, so nothing failed to compile and no test noticed for three commits.
 * A golden of the rendered screen is what would have caught it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class SearchSettingsScreenshotTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun searchSettings() {
        composeRule.setContent {
            SettingsScreenFrame {
                SearchSettingsScreen()
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}

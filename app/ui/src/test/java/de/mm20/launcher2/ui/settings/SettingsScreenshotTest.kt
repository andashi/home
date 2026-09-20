package de.mm20.launcher2.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.mm20.launcher2.ui.settings.contacts.ContactsSettingsScreen
import de.mm20.launcher2.ui.settings.homescreen.HomescreenSettingsScreen
import de.mm20.launcher2.ui.settings.locale.LocaleSettingsScreen
import de.mm20.launcher2.ui.settings.main.MainSettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L3 goldens for the settings screens (ADR 0005).
 *
 * These exist because of what the module diet (ADR 0008) kept doing to them:
 * a removal took neighbouring entries out of a shared `PreferenceCategory`,
 * the values behind them were still collected from the view model, and so
 * nothing failed to compile and no test noticed. It happened to the media and
 * feed entries, and again to favorites, apps, app shortcuts and contacts,
 * where it stood for three commits.
 *
 * A golden asserts the whole screen without anyone having to name each entry,
 * which is the property that makes it worth the bytes.
 *
 * Screens whose view model reaches past the settings into the icon pipeline or
 * the app index are not here: they would need most of the Koin graph and their
 * output would depend on what is installed, which is the opposite of what a
 * golden is for. Those belong in L2 or L4.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class SettingsScreenshotTest {

    @get:Rule(order = 0)
    val koin = KoinSettingsRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun golden(content: @Composable () -> Unit) {
        composeRule.setContent {
            SettingsScreenFrame(content)
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun mainSettings() = golden { MainSettingsScreen() }

    @Test
    fun homescreenSettings() = golden { HomescreenSettingsScreen() }

    @Test
    fun localeSettings() = golden { LocaleSettingsScreen() }

    @Test
    fun contactsSettings() = golden { ContactsSettingsScreen() }
}

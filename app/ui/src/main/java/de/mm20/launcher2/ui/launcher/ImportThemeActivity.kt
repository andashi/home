package de.mm20.launcher2.ui.launcher

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.runtime.rememberNavBackStack
import de.mm20.launcher2.ui.base.BaseActivity
import de.mm20.launcher2.ui.base.ProvideSettings
import de.mm20.launcher2.ui.locals.LocalBackStack
import de.mm20.launcher2.ui.overlays.OverlayHost
import de.mm20.launcher2.ui.settings.appearance.ImportThemeSettingsRoute
import de.mm20.launcher2.ui.settings.appearance.ImportThemeSettingsScreen
import de.mm20.launcher2.ui.theme.LauncherTheme

class ImportThemeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: return finish()

        setContent {
            // ImportThemeSettingsScreen renders a PreferenceScreen, and its top bar
            // reads LocalBackStack. That local has no default value, so without a
            // provider the composition throws before anything is drawn.
            //
            // A single-entry back stack is also the correct behaviour here: the top
            // bar falls back to Activity.onBackPressed() while the stack holds one
            // entry, so the back arrow finishes this activity instead of trying to
            // navigate inside it.
            val backStack = rememberNavBackStack(ImportThemeSettingsRoute(uri))
            CompositionLocalProvider(LocalBackStack provides backStack) {
                LauncherTheme {
                    ProvideSettings {
                        OverlayHost {
                            ImportThemeSettingsScreen(uri)
                        }
                    }
                }
            }
        }
    }
}
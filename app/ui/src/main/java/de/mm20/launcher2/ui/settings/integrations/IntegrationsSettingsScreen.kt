package de.mm20.launcher2.ui.settings.integrations

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.FeatureFlags
import de.mm20.launcher2.ktx.isAtLeastApiLevel
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.PreferenceCategory
import de.mm20.launcher2.ui.component.preferences.PreferenceScreen
import de.mm20.launcher2.ui.locals.LocalBackStack
import de.mm20.launcher2.ui.settings.feed.FeedIntegrationSettingsRoute
import de.mm20.launcher2.ui.settings.media.MediaIntegrationSettingsRoute
import de.mm20.launcher2.ui.settings.smartspacer.SmartspacerSettingsRoute
import kotlinx.serialization.Serializable

@Serializable
data object IntegrationsSettingsRoute : NavKey

@Composable
fun IntegrationsSettingsScreen() {
    val viewModel: IntegrationsSettingsScreenVM = viewModel()
    val backStack = LocalBackStack.current

    PreferenceScreen(title = stringResource(R.string.preference_screen_integrations)) {
        item {
            PreferenceCategory {
            }
        }
        item {
            PreferenceCategory {
                if (isAtLeastApiLevel(29)) {
                    if (FeatureFlags.smartspacerIntegration) {
                        Preference(
                            title = stringResource(R.string.preference_smartspacer_integration),
                            icon = R.drawable.smartspacer,
                            onClick = {
                                backStack.add(SmartspacerSettingsRoute)
                            }
                        )
                    }
                }
            }
        }
    }
}
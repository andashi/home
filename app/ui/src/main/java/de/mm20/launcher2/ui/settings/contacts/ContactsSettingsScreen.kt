package de.mm20.launcher2.ui.settings.contacts

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.preferences.GuardedPreference
import de.mm20.launcher2.ui.component.preferences.PreferenceCategory
import de.mm20.launcher2.ui.component.preferences.PreferenceScreen
import de.mm20.launcher2.ui.component.preferences.SwitchPreference
import kotlinx.serialization.Serializable

@Serializable
data object ContactsSettingsRoute: NavKey

@Composable
fun ContactsSettingsScreen() {
    val viewModel: ContactsSettingsScreenVM = viewModel()
    val context = LocalContext.current

    val hasContactsPermission by viewModel.hasContactsPermission.collectAsStateWithLifecycle(null)
    val hasCallPermission by viewModel.hasCallPermission.collectAsStateWithLifecycle(null)
    val enabledProviders by viewModel.enabledProviders.collectAsState(emptySet())
    val callOnTap by viewModel.callOnTap.collectAsStateWithLifecycle(null)

    PreferenceScreen(
        title = stringResource(R.string.preference_search_contacts)
    ) {
        item {
            PreferenceCategory {
                GuardedPreference(
                    locked = hasContactsPermission == false,
                    onUnlock = {
                        viewModel.requestContactsPermission(context as AppCompatActivity)
                    },
                    description = stringResource(R.string.missing_permission_contact_search_settings),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.preference_search_contacts),
                        summary = stringResource(R.string.preference_search_contacts_summary),
                        icon = R.drawable.person_24px,
                        value = enabledProviders.contains("local"),
                        onValueChanged = {
                            viewModel.setProviderEnabled("local", it)
                        }
                    )
                }
            }
        }
        item {
            PreferenceCategory {
                GuardedPreference(
                    locked = hasCallPermission == false,
                    onUnlock = {
                        viewModel.requestCallPermission(context as AppCompatActivity)
                    },
                    description = stringResource(R.string.missing_permission_call_contacts_settings),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.preference_contacts_call_on_tap),
                        summary = stringResource(R.string.preference_contacts_call_on_tap_summary),
                        icon = R.drawable.call_24px,
                        value = callOnTap == true && hasCallPermission == true,
                        onValueChanged = {
                            viewModel.setCallOnTap(it)
                        },
                        enabled = hasCallPermission == true
                    )
                }
            }
        }
    }

}
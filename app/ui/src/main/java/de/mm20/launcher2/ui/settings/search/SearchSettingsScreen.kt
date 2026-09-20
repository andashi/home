package de.mm20.launcher2.ui.settings.search

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.DismissableBottomSheet
import de.mm20.launcher2.ui.component.SmallMessage
import de.mm20.launcher2.ui.component.preferences.GuardedPreference
import de.mm20.launcher2.ui.component.preferences.ListPreference
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.PreferenceCategory
import de.mm20.launcher2.ui.component.preferences.PreferenceScreen
import de.mm20.launcher2.ui.component.preferences.PreferenceWithSwitch
import de.mm20.launcher2.ui.component.preferences.SwitchPreference
import de.mm20.launcher2.ui.launcher.search.filters.SearchFilters
import de.mm20.launcher2.ui.locals.LocalBackStack
import de.mm20.launcher2.ui.settings.apps.AppSearchSettingsRoute
import de.mm20.launcher2.ui.settings.appshortcuts.AppShortcutsSettingsRoute
import de.mm20.launcher2.ui.settings.contacts.ContactsSettingsRoute
import de.mm20.launcher2.ui.settings.favorites.FavoritesSettingsRoute
import de.mm20.launcher2.ui.settings.filterbar.FilterBarSettingsRoute
import de.mm20.launcher2.ui.settings.hiddenitems.HiddenItemsSettingsRoute
import de.mm20.launcher2.ui.settings.searchactions.SearchActionsSettingsRoute
import de.mm20.launcher2.ui.settings.tags.TagsSettingsRoute
import kotlinx.serialization.Serializable

@Serializable
data object SearchSettingsRoute : NavKey

@Composable
fun SearchSettingsScreen() {

    val viewModel: SearchSettingsScreenVM = viewModel()
    val context = LocalContext.current

    val backStack = LocalBackStack.current

    var showFilterEditor by remember { mutableStateOf(false) }


    val hasAppShortcutsPermission by viewModel.hasAppShortcutPermission.collectAsStateWithLifecycle(
        null
    )
    val hasContactsPermission by viewModel.hasContactsPermission.collectAsStateWithLifecycle(null)

    val favorites by viewModel.favorites.collectAsStateWithLifecycle(null)
    val allApps by viewModel.allApps.collectAsStateWithLifecycle(null)
    val appShortcuts by viewModel.appShortcuts.collectAsStateWithLifecycle(null)
    val contacts by viewModel.contacts.collectAsStateWithLifecycle(null)


    val autoFocus by viewModel.autoFocus.collectAsStateWithLifecycle(null)
    val launchOnEnter by viewModel.launchOnEnter.collectAsStateWithLifecycle(null)
    val reverseSearchResults by viewModel.reverseSearchResults.collectAsStateWithLifecycle(null)
    val filterBar by viewModel.filterBar.collectAsStateWithLifecycle(null)

    PreferenceScreen(title = stringResource(R.string.preference_screen_search)) {
        item {
            PreferenceCategory {


                Preference(
                    title = stringResource(R.string.preference_screen_search_actions),
                    summary = stringResource(R.string.preference_search_search_actions_summary),
                    icon = R.drawable.arrow_outward_24px,
                    onClick = {
                        backStack.add(SearchActionsSettingsRoute)
                    }
                )
            }
        }
        item {
            PreferenceCategory {
                Preference(
                    title = stringResource(R.string.preference_hidden_items),
                    summary = stringResource(R.string.preference_hidden_items_summary),
                    icon = R.drawable.visibility_off_24px,
                    onClick = {
                        backStack.add(HiddenItemsSettingsRoute)
                    }
                )
                Preference(
                    title = stringResource(R.string.preference_screen_tags),
                    summary = stringResource(R.string.preference_screen_tags_summary),
                    icon = R.drawable.tag_24px,
                    onClick = {
                        backStack.add(TagsSettingsRoute)
                    }
                )
            }
        }
        item {
            PreferenceCategory {
                Preference(
                    title = stringResource(R.string.preference_default_filter),
                    summary = stringResource(R.string.preference_default_filter_summary),
                    icon = R.drawable.filter_alt_24px,
                    onClick = {
                        showFilterEditor = true
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.preference_filter_bar),
                    iconPadding = true,
                    summary = stringResource(R.string.preference_filter_bar_summary),
                    value = filterBar == true,
                    onValueChanged = {
                        viewModel.setFilterBar(it)
                    }
                )
                AnimatedVisibility(filterBar == true) {
                    Preference(
                        title = stringResource(R.string.preference_customize_filter_bar),
                        iconPadding = true,
                        summary = stringResource(R.string.preference_customize_filter_bar_summary),
                        onClick = {
                            backStack.add(FilterBarSettingsRoute)
                        }
                    )
                }
            }
        }
        item {
            PreferenceCategory {
                SwitchPreference(
                    title = stringResource(R.string.preference_search_bar_auto_focus),
                    summary = stringResource(R.string.preference_search_bar_auto_focus_summary),
                    icon = R.drawable.keyboard_24px,
                    value = autoFocus == true,
                    onValueChanged = {
                        viewModel.setAutoFocus(it)
                    }
                )
                SwitchPreference(
                    title = stringResource(R.string.preference_search_bar_launch_on_enter),
                    iconPadding = true,
                    summary = stringResource(R.string.preference_search_bar_launch_on_enter_summary),
                    value = launchOnEnter == true,
                    onValueChanged = {
                        viewModel.setLaunchOnEnter(it)
                    }
                )
            }
        }
        item {
            PreferenceCategory {
                ListPreference(
                    title = stringResource(R.string.preference_layout_search_results),
                    items = listOf(
                        stringResource(R.string.search_results_order_top_down) to false,
                        stringResource(R.string.search_results_order_bottom_up) to true,
                    ),
                    value = reverseSearchResults,
                    onValueChanged = {
                        if (it != null) viewModel.setReverseSearchResults(it)
                    },
                    icon = R.drawable.sort_24px
                )
            }
        }
    }

    DismissableBottomSheet(
        expanded = showFilterEditor,
        onDismissRequest = { showFilterEditor = false }) {
        val filters by viewModel.searchFilters.collectAsStateWithLifecycle()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            AnimatedVisibility(filters.allowNetwork) {
                SmallMessage(
                    modifier = Modifier.padding(bottom = 16.dp),
                    icon = R.drawable.warning_24px,
                    text = stringResource(R.string.filter_settings_network_warning)
                )
            }
            SearchFilters(
                filters = filters,
                onFiltersChange = {
                    viewModel.setSearchFilters(it)
                },
                settings = true,
            )
        }
    }
}
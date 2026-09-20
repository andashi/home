package de.mm20.launcher2.ui.launcher.search.filters

import android.content.Context
import de.mm20.launcher2.preferences.KeyboardFilterBarItem
import de.mm20.launcher2.search.SearchFilters
import de.mm20.launcher2.ui.R

val KeyboardFilterBarItem.iconMedium
    get() = when (this) {
        KeyboardFilterBarItem.Apps -> R.drawable.apps_24px
        KeyboardFilterBarItem.Contacts -> R.drawable.person_24px
        KeyboardFilterBarItem.Shortcuts -> R.drawable.mobile_arrow_up_right_24px
        KeyboardFilterBarItem.HiddenResults -> R.drawable.visibility_off_24px
        KeyboardFilterBarItem.OnlineResults -> R.drawable.language_24px
    }

val KeyboardFilterBarItem.iconSmall
    get() = when (this) {
        KeyboardFilterBarItem.Apps -> R.drawable.apps_20px
        KeyboardFilterBarItem.Contacts -> R.drawable.person_20px
        KeyboardFilterBarItem.Shortcuts -> R.drawable.mobile_arrow_up_right_20px
        KeyboardFilterBarItem.HiddenResults -> R.drawable.visibility_off_20px
        KeyboardFilterBarItem.OnlineResults -> R.drawable.language_20px
    }

fun KeyboardFilterBarItem.getLabel(context: Context): String {
    return when (this) {
        KeyboardFilterBarItem.Apps -> context.getString(R.string.search_filter_apps)
        KeyboardFilterBarItem.Contacts -> context.getString(R.string.preference_search_contacts)
        KeyboardFilterBarItem.Shortcuts -> context.getString(R.string.preference_search_appshortcuts)
        KeyboardFilterBarItem.HiddenResults -> context.getString(R.string.preference_hidden_items)
        KeyboardFilterBarItem.OnlineResults -> context.getString(R.string.search_filter_online)
    }
}

val KeyboardFilterBarItem.isCategory
    get() = when (this) {
        KeyboardFilterBarItem.OnlineResults, KeyboardFilterBarItem.HiddenResults -> false
        else -> true
    }

fun SearchFilters.isSelected(item: KeyboardFilterBarItem): Boolean {
    if (item.isCategory && allCategoriesEnabled) return false
    return when (item) {
        KeyboardFilterBarItem.Apps -> apps
        KeyboardFilterBarItem.Contacts -> contacts
        KeyboardFilterBarItem.Shortcuts -> shortcuts
        KeyboardFilterBarItem.HiddenResults -> hiddenItems
        KeyboardFilterBarItem.OnlineResults -> allowNetwork
    }
}

fun SearchFilters.toggle(item: KeyboardFilterBarItem): SearchFilters {
    return when (item) {
        KeyboardFilterBarItem.Apps -> return toggleApps()
        KeyboardFilterBarItem.Contacts -> return toggleContacts()
        KeyboardFilterBarItem.Shortcuts -> return toggleShortcuts()
        KeyboardFilterBarItem.HiddenResults -> return copy(hiddenItems = !hiddenItems)
        KeyboardFilterBarItem.OnlineResults -> return copy(allowNetwork = !allowNetwork)
    }
}
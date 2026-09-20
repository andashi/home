package de.mm20.launcher2.preferences

import de.mm20.launcher2.preferences.config.LauncherConfigSettings
import de.mm20.launcher2.preferences.config.LauncherConfigSettingsImpl
import de.mm20.launcher2.preferences.feed.FeedSettings
import de.mm20.launcher2.preferences.search.ContactSearchSettings
import de.mm20.launcher2.preferences.media.MediaSettings
import de.mm20.launcher2.preferences.search.FavoritesSettings
import de.mm20.launcher2.preferences.search.RankingSettings
import de.mm20.launcher2.preferences.search.SearchFilterSettings
import de.mm20.launcher2.preferences.search.ShortcutSearchSettings
import de.mm20.launcher2.preferences.ui.BadgeSettings
import de.mm20.launcher2.preferences.ui.ClockWidgetSettings
import de.mm20.launcher2.preferences.ui.GestureSettings
import de.mm20.launcher2.preferences.ui.IconSettings
import de.mm20.launcher2.preferences.ui.LocaleSettings
import de.mm20.launcher2.preferences.ui.SearchUiSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.preferences.ui.UiState
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val preferencesModule = module {
    single { LauncherDataStore(androidContext()) }
    factory { MediaSettings(get()) }
    factory { ContactSearchSettings(get()) }
    factory { BadgeSettings(get()) }
    factory { UiSettings(get()) }
    factory { ShortcutSearchSettings(get()) }
    factory { FavoritesSettings(get()) }
    factory { IconSettings(get()) }
    factory { RankingSettings(get()) }
    factory { UiState(get()) }
    factory { SearchUiSettings(get()) }
    factory { GestureSettings(get()) }
    factory { ClockWidgetSettings(get()) }
    factory { SearchFilterSettings(get()) }
    factory { LocaleSettings(get()) }
    factory { FeedSettings(get()) }
    // Fork addition (Phase 2): config reload settings gateway, consumed by
    // :services:config.
    factory<LauncherConfigSettings> { LauncherConfigSettingsImpl(get()) }
}
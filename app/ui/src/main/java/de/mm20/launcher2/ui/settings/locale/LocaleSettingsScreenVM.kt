package de.mm20.launcher2.ui.settings.locale

import androidx.lifecycle.ViewModel
import de.mm20.launcher2.preferences.TimeFormat
import de.mm20.launcher2.preferences.ui.LocaleSettings
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LocaleSettingsScreenVM : ViewModel(), KoinComponent {
    private val localeSettings: LocaleSettings by inject()

    val timeFormat = localeSettings.timeFormat
    fun setTimeFormat(timeFormat: TimeFormat) {
        localeSettings.setTimeFormat(timeFormat)
    }
    val transliterator = localeSettings.transliterator
    fun setTransliterator(transliterator: String?) {
        localeSettings.setTransliterator(transliterator)
    }

    val calendars = combine(localeSettings.primaryCalendar, localeSettings.secondaryCalendar) {
        it.toImmutableList()
    }
}
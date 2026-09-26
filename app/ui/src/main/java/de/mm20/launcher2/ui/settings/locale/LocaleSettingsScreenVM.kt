package de.mm20.launcher2.ui.settings.locale

import androidx.lifecycle.ViewModel
import de.mm20.launcher2.preferences.ui.LocaleSettings
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LocaleSettingsScreenVM : ViewModel(), KoinComponent {
    private val localeSettings: LocaleSettings by inject()

    val transliterator = localeSettings.transliterator
    fun setTransliterator(transliterator: String?) {
        localeSettings.setTransliterator(transliterator)
    }
}

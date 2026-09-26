package de.mm20.launcher2.preferences.ui

import de.mm20.launcher2.preferences.LauncherDataStore
import kotlinx.coroutines.flow.map


class LocaleSettings internal constructor(
    private val launcherDataStore: LauncherDataStore,
) {
    val transliterator
        get() = launcherDataStore.data.map { it.localeTransliterator }

    fun setTransliterator(transliterator: String?) {
        launcherDataStore.update {
            it.copy(localeTransliterator = transliterator)
        }
    }
}

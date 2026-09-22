package de.mm20.launcher2.homegrid

import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.flow.first

/** The seed flag as a DataStore field (`homeGridSeeded`). */
class UiSettingsSeedFlag(
    private val uiSettings: UiSettings,
) : HomeGridSeedFlag {
    override suspend fun isSeeded(): Boolean = uiSettings.homeGridSeeded.first()

    override suspend fun markSeeded() {
        uiSettings.setHomeGridSeeded(true)
        // The setter writes asynchronously; return once the store holds it,
        // so a caller that reads the flag next sees what it just set.
        uiSettings.homeGridSeeded.first { it }
    }
}

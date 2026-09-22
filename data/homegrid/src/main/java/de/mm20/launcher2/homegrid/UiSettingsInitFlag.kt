package de.mm20.launcher2.homegrid

import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.flow.first

/** The init flag as a DataStore field (`homeGridInitialized`). */
class UiSettingsInitFlag(
    private val uiSettings: UiSettings,
) : HomeGridInitFlag {
    override suspend fun isInitialized(): Boolean = uiSettings.homeGridInitialized.first()

    override suspend fun markInitialized() {
        uiSettings.setHomeGridInitialized(true)
        // The setter writes asynchronously; return once the store holds it,
        // so a caller that reads the flag next sees what it just set.
        uiSettings.homeGridInitialized.first { it }
    }
}

package de.mm20.launcher2.preferences.migrations

import androidx.datastore.core.DataMigration
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherSettingsData

/**
 * The widget pages reached by gestures are gone (PR 5b). A gesture that
 * still points at one opens search instead, which is what the home surface
 * has left to open. Keyed on the content, not on a schema version, so it
 * also cleans a file that was written at the current version.
 */
@Suppress("DEPRECATION")
class Migration6 : DataMigration<LauncherSettingsData> {
    override suspend fun cleanUp() {
    }

    override suspend fun migrate(currentData: LauncherSettingsData): LauncherSettingsData {
        fun GestureAction.mapped() = if (this is GestureAction.Widgets) GestureAction.Search else this
        return currentData.copy(
            gesturesSwipeDown = currentData.gesturesSwipeDown.mapped(),
            gesturesSwipeLeft = currentData.gesturesSwipeLeft.mapped(),
            gesturesSwipeRight = currentData.gesturesSwipeRight.mapped(),
            gesturesSwipeUp = currentData.gesturesSwipeUp.mapped(),
            gesturesDoubleTap = currentData.gesturesDoubleTap.mapped(),
            gesturesLongPress = currentData.gesturesLongPress.mapped(),
            gesturesHomeButton = currentData.gesturesHomeButton.mapped(),
        )
    }

    override suspend fun shouldMigrate(currentData: LauncherSettingsData): Boolean {
        return listOf(
            currentData.gesturesSwipeDown, currentData.gesturesSwipeLeft, currentData.gesturesSwipeRight,
            currentData.gesturesSwipeUp, currentData.gesturesDoubleTap, currentData.gesturesLongPress,
            currentData.gesturesHomeButton,
        ).any { it is GestureAction.Widgets }
    }
}

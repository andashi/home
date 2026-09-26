package de.mm20.launcher2.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import de.mm20.launcher2.preferences.IconShape
import de.mm20.launcher2.preferences.ui.GridSettings
import de.mm20.launcher2.preferences.ui.SearchUiSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.component.ProvideIconShape
import de.mm20.launcher2.ui.locals.LocalShowAppDetails
import de.mm20.launcher2.ui.locals.LocalGridSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject

@Composable
fun ProvideSettings(
    content: @Composable () -> Unit
) {
    val settings: UiSettings = koinInject()
    val searchUiSettings: SearchUiSettings = koinInject()

    val iconShape by remember {
        settings.iconShape.distinctUntilChanged()
    }.collectAsState(IconShape.Circle)

    val gridSettings by remember {
        settings.gridSettings.distinctUntilChanged()
    }.collectAsState<GridSettings, GridSettings?>(null)

    val showAppDetails by remember {
        searchUiSettings.showAppDetails.distinctUntilChanged()
    }.collectAsState(false)

    // Nothing is drawn before the settings are loaded, or the first frame
    // would show default icons and the next the stored ones. The hold used to
    // hang on the time format, which nothing reads since the clock went (#20).
    val grid = gridSettings ?: return

    CompositionLocalProvider(
        LocalShowAppDetails provides showAppDetails,
        LocalGridSettings provides grid,
    ) {
        ProvideIconShape(iconShape) {
            content()
        }
    }

}

package de.mm20.launcher2.ui.launcher.grid

import android.appwidget.AppWidgetManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.homegrid.HomeGridCell
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.Banner
import de.mm20.launcher2.ui.component.LauncherCard
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.ui.launcher.widgets.external.AppWidgetHost
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import de.mm20.launcher2.ui.theme.transparency.transparency
import de.mm20.launcher2.widgets.AppWidget

/** One cell of the grid: the favorites widget or a hosted AppWidget, on a card. */
@Composable
internal fun GridCell(
    cell: HomeGridCell,
    viewModel: HomeGridVM,
) {
    val item = cell.item
    if (item.isFavorites) {
        GridCard {
            FavoritesGridWidget(
                columns = cell.span.w,
                rows = cell.span.h,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        AppWidgetCell(
            item = item,
            onRemove = { viewModel.remove(item) },
            onReplace = { widget, appWidgetId -> viewModel.replace(item, widget, appWidgetId) },
        )
    }
}

/**
 * The card behind a cell, with the opacity rule of the old `WidgetItem`: an
 * AppWidget that asked for no background gets a fully transparent card.
 */
@Composable
internal fun GridCard(
    modifier: Modifier = Modifier,
    transparent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val backgroundOpacity by animateFloatAsState(
        if (transparent) 0f else MaterialTheme.transparency.surface,
        label = "gridCellBackgroundOpacity",
    )
    LauncherCard(
        modifier = modifier.fillMaxSize(),
        backgroundOpacity = backgroundOpacity,
        content = content,
    )
}

/**
 * A hosted AppWidget, or the "loading failed" banner with Replace and Remove
 * when the item has no host id yet (binding refused, see the reconciler) or
 * its provider is gone. The host view is keyed by (item, host id) so it
 * survives every recomposition that does not change the binding.
 */
@Composable
internal fun AppWidgetCell(
    item: HomeGridItem,
    onRemove: () -> Unit,
    onReplace: (widget: String, appWidgetId: Int) -> Unit,
) {
    val context = LocalContext.current
    val appWidgetId = item.appWidgetId
    val widgetInfo = remember(appWidgetId) {
        appWidgetId?.let { AppWidgetManager.getInstance(context).getAppWidgetInfo(it) }
    }

    if (appWidgetId == null || widgetInfo == null) {
        var replaceWidget by rememberSaveable { mutableStateOf(false) }
        GridCard {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Banner(
                    modifier = Modifier.padding(8.dp),
                    text = stringResource(R.string.app_widget_loading_failed),
                    icon = R.drawable.warning_24px,
                    secondaryAction = {
                        OutlinedButton(onClick = onRemove) {
                            Text(stringResource(R.string.widget_action_remove))
                        }
                    },
                    primaryAction = {
                        Button(onClick = { replaceWidget = true }) {
                            Text(stringResource(R.string.widget_action_replace))
                        }
                    },
                )
            }
        }
        WidgetPickerSheet(
            expanded = replaceWidget,
            includeBuiltinWidgets = false,
            onDismiss = { replaceWidget = false },
            onWidgetSelected = { picked ->
                if (picked is AppWidget) {
                    val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(picked.config.widgetId)
                    val provider = info?.provider?.flattenToString()
                    if (provider != null) onReplace(provider, picked.config.widgetId)
                }
                replaceWidget = false
            },
        )
        return
    }

    val lightBackground =
        if (item.config.background) !LocalDarkTheme.current else LocalPreferDarkContentOverWallpaper.current
    GridCard(transparent = !item.config.background) {
        key(item.id, appWidgetId) {
            AppWidgetHost(
                widgetInfo = widgetInfo,
                widgetId = appWidgetId,
                modifier = Modifier.fillMaxSize(),
                borderless = item.config.borderless,
                useThemeColors = item.config.themeColors,
                onLightBackground = lightBackground,
            )
        }
    }
}

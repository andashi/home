package de.mm20.launcher2.ui.launcher.grid

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.profiles.ProfileManager
import org.koin.compose.koinInject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.homegrid.HomeGridCell
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.Banner
import de.mm20.launcher2.ui.launcher.glass.GlassSurface
import de.mm20.launcher2.ui.launcher.glass.LocalGlassStyle
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.ui.launcher.widgets.external.AppWidgetHost
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import de.mm20.launcher2.services.widgets.PickedWidget

/**
 * One cell of the grid: the favorites widget or a hosted AppWidget, on a
 * card. [favoritesContent] draws the favorites widget for a span; the
 * default is [FavoritesGridWidget], tests pass a placeholder.
 */
@Composable
internal fun GridCell(
    cell: HomeGridCell,
    viewModel: HomeGridVM,
    favoritesContent: @Composable (columns: Int, rows: Int) -> Unit,
    showLabel: Boolean = false,
) {
    val item = cell.item
    if (item.isFavorites) {
        // The dock: a glass surface like every card, never a label (ADR 0004).
        GridCard {
            favoritesContent(cell.span.w, cell.span.h)
        }
    } else {
        // An item the host could not bind silently (no bind-widget grant yet)
        // offers the system's dialog for its own provider, if it is installed.
        val context = LocalContext.current
        val profileManager: ProfileManager = koinInject()
        val arrivals by viewModel.arrivals.collectAsStateWithLifecycle()
        val providerInfo = rememberAcrossArrivals(item.widget to item.profile, arrivals) {
            installedProvider(context, profileManager, item.widget, item.profile)
        }
        val bindLauncher = rememberLauncherForActivityResult(BindProviderContract()) { appWidgetId ->
            if (appWidgetId != null) viewModel.bind(item, appWidgetId)
        }
        val label = if (showLabel) {
            // Keyed on the arrival itself too: the app's name is read from the
            // package, not only through providerInfo.
            rememberAcrossArrivals(item.widget to providerInfo, arrivals) {
                val pm = context.packageManager
                gridItemLabel(
                    item,
                    providerLabel = { providerInfo?.loadLabel(pm) },
                    appLabel = { appLabel(pm, item.widget) },
                )
            }
        } else {
            null
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AppWidgetCell(
                    item = item,
                    onRemove = { viewModel.remove(item) },
                    onReplace = { widget, appWidgetId -> viewModel.replace(item, widget, appWidgetId) },
                    onAllow = providerInfo?.let { info -> { bindLauncher.launch(info) } },
                    arrivals = arrivals,
                )
            }
            if (label != null) GridLabel(item.id, label)
        }
    }
}

/**
 * The label under a cell (`home.grid.labels`): `labelSmall`, white, on the
 * contrast scrim when `contrast` is `high`, one line.
 */
@Composable
internal fun GridLabel(id: String, text: String) {
    val scrim = LocalGlassStyle.current.scrimAlpha
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag("grid-label:$id")
            .then(
                if (scrim > 0f) {
                    Modifier.background(Color.Black.copy(alpha = scrim), RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            ),
    )
}

/**
 * A value that depends on whether a package is present, remembered per
 * [identity] and looked up again whenever [arrivals] moves.
 *
 * Keyed on identity alone, a value looked up while the package was missing
 * stayed missing after it arrived (review on #213):
 * - the installed provider, so the banner of a widget whose bind the device
 *   refused offered no Allow, and the label kept its fallback;
 * - a bound widget's info, so a provider unavailable when the cell first
 *   showed (an app mid-update) left "could not load" up for good, the host id
 *   never changing.
 */
@Composable
internal fun <T : Any> rememberAcrossArrivals(identity: Any?, arrivals: Int, lookup: () -> T?): T? =
    remember(identity, arrivals) { lookup() }

/** The name of the app a `pkg/cls` provider belongs to, or null. */
private fun appLabel(pm: PackageManager, widget: String): CharSequence? {
    val component = ComponentName.unflattenFromString(widget) ?: return null
    return try {
        pm.getApplicationLabel(pm.getApplicationInfo(component.packageName, 0))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

/** The installed provider an item names, in its profile, or null. */
private fun installedProvider(
    context: Context,
    profileManager: ProfileManager,
    widget: String,
    profile: String?,
): AppWidgetProviderInfo? {
    val component = ComponentName.unflattenFromString(widget) ?: return null
    val userHandle = when (profile) {
        null, "personal" -> Process.myUserHandle()
        "work" -> profileManager.getProfile(Profile.Type.Work)?.userHandle
        "private" -> profileManager.getProfile(Profile.Type.Private)?.userHandle
        else -> null
    } ?: return null
    return AppWidgetManager.getInstance(context)
        ?.getInstalledProvidersForProfile(userHandle)
        ?.firstOrNull { it.provider == component }
}

/**
 * The card behind a cell: a glass surface (#75). An AppWidget that asked for
 * no background gets no surface at all, the rule of the old `WidgetItem`.
 */
@Composable
internal fun GridCard(
    modifier: Modifier = Modifier,
    transparent: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (transparent) {
        Box(modifier.fillMaxSize()) { content() }
    } else {
        GlassSurface(modifier.fillMaxSize(), content = content)
    }
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
    onAllow: (() -> Unit)? = null,
    /** [HomeGridVM.arrivals]: a provider missing at the first look is looked up again when its package arrives. */
    arrivals: Int = 0,
) {
    val context = LocalContext.current
    val appWidgetId = item.appWidgetId
    val widgetInfo = rememberAcrossArrivals(appWidgetId, arrivals) {
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
                        if (onAllow != null) {
                            OutlinedButton(onClick = { replaceWidget = true }) {
                                Text(stringResource(R.string.widget_action_replace))
                            }
                        }
                    },
                    primaryAction = {
                        if (onAllow != null) {
                            Button(onClick = onAllow) {
                                Text(stringResource(R.string.widget_action_allow))
                            }
                        } else {
                            Button(onClick = { replaceWidget = true }) {
                                Text(stringResource(R.string.widget_action_replace))
                            }
                        }
                    },
                )
            }
        }
        // Composed only while open: the sheet carries its own view model.
        if (replaceWidget) {
            WidgetPickerSheet(
                expanded = true,
                includeBuiltinWidgets = false,
                onDismiss = { replaceWidget = false },
                onWidgetSelected = { picked ->
                    if (picked is PickedWidget.App) {
                        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(picked.appWidgetId)
                        val provider = info?.provider?.flattenToString()
                        if (provider != null) onReplace(provider, picked.appWidgetId)
                    }
                    replaceWidget = false
                },
            )
        }
        return
    }

    // With or without a background the widget sees the wallpaper: its card is
    // glass (#75), not the theme's opaque surface the old rule assumed, so the
    // theme was the wrong answer whenever theme and wallpaper disagreed (#242).
    val lightBackground = LocalPreferDarkContentOverWallpaper.current
    GridCard(transparent = !item.config.background) {
        key(item.id, appWidgetId) {
            AppWidgetHost(
                widgetInfo = widgetInfo,
                widgetId = appWidgetId,
                modifier = Modifier.fillMaxSize(),
                borderless = item.config.borderless,
                useThemeColors = item.config.themeColors,
                mute = item.config.mute,
                onLightBackground = lightBackground,
            )
        }
    }
}

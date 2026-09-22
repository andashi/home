package de.mm20.launcher2.ui.launcher.grid

import android.provider.Settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.GridGeometry
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.ui.base.LocalAppWidgetHost
import org.koin.compose.koinInject

/**
 * The single-page widget grid (ADR 0001). One measurement at the top decides
 * the geometry; everything below is placed at fixed cell rectangles.
 */
/** Semantics of the grid root: true while the cells wiggle in edit mode (tests read it). */
val GridWiggling = SemanticsPropertyKey<Boolean>("GridWiggling")

@Composable
fun HomeGrid(
    modifier: Modifier = Modifier,
    viewModel: HomeGridVM = viewModel(factory = HomeGridVM.factory()),
    reducedMotion: Boolean = rememberReducedMotion(),
    onEditFavorites: (() -> Unit)? = null,
    favoritesContent: @Composable (columns: Int, rows: Int) -> Unit = { columns, rows ->
        FavoritesGridWidget(columns = columns, rows = rows, modifier = Modifier.fillMaxSize())
    },
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        LaunchedEffect(widthDp, heightDp) {
            viewModel.onWindowMeasured(widthDp, heightDp)
        }

        val state by viewModel.state.collectAsStateWithLifecycle()
        val uiState = state ?: return@BoxWithConstraints

        val context = LocalContext.current
        val host = LocalAppWidgetHost.current
        val profileManager: ProfileManager = koinInject()
        // Re-run whenever an item appears, disappears or changes its host id;
        // a pass that finds nothing to do costs one query.
        val bindingKey = uiState.cells.map { it.item.id to it.item.appWidgetId }
        LaunchedEffect(bindingKey) {
            val port = AndroidAppWidgetHostPort(context, host) { type ->
                profileManager.getProfile(type)?.userHandle
            }
            viewModel.reconcile(port)
        }

        val cellsById = remember(uiState.cells) { uiState.cells.associateBy { it.item.id } }
        HomeGridLayout(
            geometry = uiState.geometry,
            cells = uiState.cells.map { it.item.id to it.span },
            modifier = Modifier.fillMaxSize(),
        ) { id ->
            val cell = cellsById[id] ?: return@HomeGridLayout
            GridCell(cell = cell, viewModel = viewModel, favoritesContent = favoritesContent)
        }
    }
}

/**
 * Places every cell at its rectangle in one layout pass (performance rules of
 * the plan): no lazy grid, no per-cell measurement of constraints, and the
 * geometry in pixels is remembered on (geometry, cells) so a recomposition
 * for any other reason does not recompute it.
 *
 * Each child carries `contentDescription = "grid-item:<id>"`, which is how
 * the L4 scenario finds cells through `uiautomator dump`.
 *
 * [onRecomposed] exists for the recomposition-count test only.
 */
@Composable
fun HomeGridLayout(
    geometry: GridGeometry,
    cells: List<Pair<String, Span>>,
    modifier: Modifier = Modifier,
    visual: GridEditVisual? = null,
    onRecomposed: (() -> Unit)? = null,
    overlay: (@Composable (id: String) -> Unit)? = null,
    content: @Composable (id: String) -> Unit,
) {
    if (onRecomposed != null) {
        SideEffect { onRecomposed() }
    }
    val density = LocalDensity.current
    val measurePolicy = remember(geometry, cells, density) {
        gridMeasurePolicy(geometry, cells, cellPx = with(density) { geometry.cellDp.dp.toPx() },
            gapPx = with(density) { geometry.gapDp.dp.toPx() })
    }
    Layout(
        content = {
            for ((id, _) in cells) {
                key(id) {
                    Box(
                        modifier = Modifier
                            .layoutId(id)
                            .semantics { contentDescription = "grid-item:$id" },
                    ) {
                        content(id)
                    }
                }
            }
        },
        modifier = modifier,
        measurePolicy = measurePolicy,
    )
}

/**
 * Measures each child exactly to its cell rectangle and places it there.
 * Rectangle edges are rounded from the accumulated float position, so no
 * gap or cell drifts by a pixel across the row.
 */
internal fun gridMeasurePolicy(
    geometry: GridGeometry,
    cells: List<Pair<String, Span>>,
    cellPx: Float,
    gapPx: Float,
): MeasurePolicy {
    val pitch = cellPx + gapPx
    val spansById = cells.toMap()
    return MeasurePolicy { measurables, constraints ->
        val placed = measurables.map { measurable ->
            val span = spansById.getValue(measurable.layoutId as String)
            val left = (span.x * pitch).roundToInt()
            val top = (span.y * pitch).roundToInt()
            val right = (span.right * pitch - gapPx).roundToInt()
            val bottom = (span.bottom * pitch - gapPx).roundToInt()
            val placeable = measurable.measure(
                Constraints.fixed(
                    width = (right - left).coerceAtLeast(0),
                    height = (bottom - top).coerceAtLeast(0),
                ),
            )
            PlacedCell(placeable, left, top)
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placed.maxOfOrNull { it.left + it.placeable.width } ?: 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placed.maxOfOrNull { it.top + it.placeable.height } ?: 0
        layout(width, height) {
            for (cell in placed) {
                cell.placeable.placeRelative(cell.left, cell.top)
            }
        }
    }
}

private class PlacedCell(val placeable: Placeable, val left: Int, val top: Int)

/** Whether the platform asks for no animation (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

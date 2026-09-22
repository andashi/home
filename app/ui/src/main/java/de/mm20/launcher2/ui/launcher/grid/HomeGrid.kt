package de.mm20.launcher2.ui.launcher.grid

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
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.GridGeometry
import de.mm20.launcher2.ui.base.LocalAppWidgetHost

/**
 * The single-page widget grid (ADR 0001). One measurement at the top decides
 * the geometry; everything below is placed at fixed cell rectangles.
 */
@Composable
fun HomeGrid(
    modifier: Modifier = Modifier,
    viewModel: HomeGridVM = viewModel(factory = HomeGridVM.factory()),
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
        // Re-run whenever an item appears, disappears or changes its host id;
        // a pass that finds nothing to do costs one query.
        val bindingKey = uiState.cells.map { it.item.id to it.item.appWidgetId }
        LaunchedEffect(bindingKey) {
            viewModel.reconcile(AndroidAppWidgetHostPort(context, host))
        }

        val cellsById = remember(uiState.cells) { uiState.cells.associateBy { it.item.id } }
        HomeGridLayout(
            geometry = uiState.geometry,
            cells = uiState.cells.map { it.item.id to it.span },
            modifier = Modifier.fillMaxSize(),
        ) { id ->
            val cell = cellsById[id] ?: return@HomeGridLayout
            GridCell(cell = cell, viewModel = viewModel)
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
    onRecomposed: (() -> Unit)? = null,
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
    TODO("PR 4")
}

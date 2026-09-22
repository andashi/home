package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.homegrid.GridGeometry
import de.mm20.launcher2.homegrid.HomeGridCell
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.ui.R
import kotlin.math.roundToInt

/**
 * The edit bar: Add opens the widget picker, Done leaves edit mode and
 * writes the layout back. It floats over the grid's top-end corner so the
 * grid keeps its geometry while editing (a bar that took space would change
 * the row count under the cells being moved).
 */
@Composable
internal fun GridEditBar(
    onAdd: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            FilledTonalIconButton(
                onClick = onAdd,
                modifier = Modifier.semantics { contentDescription = "grid-edit-add" },
            ) {
                Icon(painterResource(R.drawable.add_24px), contentDescription = stringResource(R.string.widget_add_widget))
            }
            FilledTonalIconButton(
                onClick = onDone,
                modifier = Modifier.semantics { contentDescription = "grid-edit-done" },
            ) {
                Icon(painterResource(R.drawable.check_24px), contentDescription = stringResource(R.string.action_done))
            }
        }
    }
}

/**
 * Edit mode's gestures on one cell, drawn over its content so a hosted
 * widget receives nothing while editing (as on iOS): a tap selects (the
 * favorites cell opens its editor instead), a drag moves the cell with
 * live push-down, and the selected cell shows the remove badge, the +/-
 * resize buttons that hide at the item's limits, and a corner handle that
 * resizes in whole cells.
 */
@Composable
internal fun GridCellEditOverlay(
    cell: HomeGridCell,
    geometry: GridGeometry,
    visual: GridEditVisual,
    viewModel: HomeGridVM,
    selected: Boolean,
    onEditFavorites: () -> Unit,
    onRemoved: (HomeGridItem) -> Unit,
) {
    val id = cell.item.id
    val density = LocalDensity.current
    val pitchPx = with(density) { (geometry.cellDp + geometry.gapDp).dp.toPx() }
    val gapPx = with(density) { geometry.gapDp.dp.toPx() }
    val span by rememberUpdatedState(cell.span)
    val limits = remember(id, selected) { viewModel.limitsOf(id) }
    val isFavorites = cell.item.isFavorites

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(id, isFavorites) {
                detectTapGestures(
                    onTap = {
                        viewModel.select(id)
                        if (isFavorites) onEditFavorites()
                    },
                )
            }
            .pointerInput(id, pitchPx) {
                detectDragGestures(
                    onDragStart = {
                        viewModel.select(id)
                        val origin = cellTopLeft(span, pitchPx - gapPx, gapPx)
                        visual.ghostLeftPx = origin.x.toFloat()
                        visual.ghostTopPx = origin.y.toFloat()
                        visual.draggedId = id
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        visual.ghostLeftPx += amount.x
                        visual.ghostTopPx += amount.y
                        val targetX = (visual.ghostLeftPx / pitchPx).roundToInt()
                            .coerceIn(0, (geometry.visibleColumns - span.w).coerceAtLeast(0))
                        val targetY = (visual.ghostTopPx / pitchPx).roundToInt()
                            .coerceIn(0, (geometry.rows - span.h).coerceAtLeast(0))
                        if (targetX != span.x || targetY != span.y) {
                            viewModel.move(id, targetX, targetY)
                        }
                    },
                    onDragEnd = { visual.draggedId = null },
                    onDragCancel = { visual.draggedId = null },
                )
            }
            .then(
                if (selected) {
                    Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        MaterialTheme.shapes.medium,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        if (!selected) return@Box

        val current = span
        FilledTonalIconButton(
            onClick = { viewModel.removeEditing(id)?.let(onRemoved) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(32.dp)
                .semantics { contentDescription = "grid-remove" },
        ) {
            Icon(painterResource(R.drawable.close_20px), contentDescription = stringResource(R.string.widget_action_remove))
        }

        Row(modifier = Modifier.align(Alignment.BottomStart).padding(2.dp)) {
            if (current.w > limits.minW) {
                ResizeButton("grid-resize-narrower", "W−") { viewModel.resize(id, current.w - 1, current.h) }
            }
            if (current.w < limits.maxW && current.x + current.w < geometry.visibleColumns) {
                ResizeButton("grid-resize-wider", "W+") { viewModel.resize(id, current.w + 1, current.h) }
            }
            if (current.h > limits.minH) {
                ResizeButton("grid-resize-shorter", "H−") { viewModel.resize(id, current.w, current.h - 1) }
            }
            if (current.h < limits.maxH && current.y + current.h < geometry.rows) {
                ResizeButton("grid-resize-taller", "H+") { viewModel.resize(id, current.w, current.h + 1) }
            }
        }

        // The corner handle: drag in whole cells, clamped by the same limits.
        var dragW = 0f
        var dragH = 0f
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(28.dp)
                .semantics { contentDescription = "grid-resize-handle" }
                .pointerInput(id, pitchPx) {
                    detectDragGestures(
                        onDragStart = { dragW = 0f; dragH = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dragW += amount.x
                            dragH += amount.y
                            val w = (span.w + (dragW / pitchPx).roundToInt()).coerceIn(limits.minW, limits.maxW)
                            val h = (span.h + (dragH / pitchPx).roundToInt()).coerceIn(limits.minH, limits.maxH)
                            if (w != span.w || h != span.h) {
                                dragW -= (w - span.w) * pitchPx
                                dragH -= (h - span.h) * pitchPx
                                viewModel.resize(id, w, h)
                            }
                        },
                    )
                },
        ) {
            Surface(
                modifier = Modifier.align(Alignment.Center).size(16.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
        }
    }
}

@Composable
private fun ResizeButton(description: String, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .semantics { contentDescription = description },
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

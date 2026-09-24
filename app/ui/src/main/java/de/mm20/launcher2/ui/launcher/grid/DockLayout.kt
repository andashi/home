package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Where the [index]th favorite of a dock sits, in cells from the dock's top left. */
data class DockPlacement(val index: Int, val row: Float, val column: Float)

/**
 * The cells of a [columns] × [rows] dock that [count] favorites take (#111).
 * They fill rows of [columns] in order; each row is centred, and the used
 * rows are centred in the dock's height. A full dock is favorite k in cell
 * k; beyond `columns × rows`, the rest are not placed (search has them).
 */
fun dockPlacements(count: Int, columns: Int, rows: Int): List<DockPlacement> {
    val shown = count.coerceIn(0, columns * rows)
    if (shown == 0) return emptyList()
    val usedRows = (shown + columns - 1) / columns
    val top = (rows - usedRows) / 2f
    return (0 until shown).map { index ->
        val row = index / columns
        val inRow = minOf(columns, shown - row * columns)
        DockPlacement(index, top + row, (columns - inRow) / 2f + index % columns)
    }
}

/**
 * Lays out [count] icons of a [columns] × [rows] dock: every icon one cell in
 * size, at the cell [dockPlacements] gives it. [content] emits the icons in
 * order, at most `columns × rows` of them.
 */
@Composable
fun DockIcons(
    count: Int,
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cellWidth = width / columns.toFloat()
        val cellHeight = height / rows.toFloat()
        val cell = Constraints.fixed(cellWidth.roundToInt(), cellHeight.roundToInt())
        val placements = dockPlacements(count, columns, rows)
        val placeables = measurables.take(placements.size).map { it.measure(cell) }
        layout(width, height) {
            for (p in placements) {
                placeables[p.index].place((p.column * cellWidth).roundToInt(), (p.row * cellHeight).roundToInt())
            }
        }
    }
}

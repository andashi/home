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
 * Today's behavior until the fix: row by row from the top left.
 */
fun dockPlacements(count: Int, columns: Int, rows: Int): List<DockPlacement> {
    val shown = count.coerceIn(0, columns * rows)
    return (0 until shown).map { DockPlacement(it, (it / columns).toFloat(), (it % columns).toFloat()) }
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

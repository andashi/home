package de.mm20.launcher2.homegrid

import de.mm20.launcher2.grid.LayoutIssue
import de.mm20.launcher2.grid.Span

/** One item as drawn: its row from the database and the span it ended up with. */
data class HomeGridCell(val item: HomeGridItem, val span: Span)

/** The cells of one window plus what the engine had to correct to draw them. */
data class HomeGridArrangementResult(
    val cells: List<HomeGridCell>,
    val issues: List<LayoutIssue>,
)

/**
 * Turns what the repository holds into what one window draws: the layout is
 * normalised against the device's [GridGeometry.spec] (nothing overlaps,
 * nothing sticks out, nothing but the favorites widget crosses the fold), and
 * on the cover clipped to the left half.
 */
object HomeGridArrangement {
    /**
     * Invariants: no two result cells overlap; every cell fits in
     * [GridGeometry.visibleColumns] x rows; on the cover no cell starts at or
     * beyond the fold column; the favorites widget is the only cell allowed to
     * span the fold and is clipped, not dropped, on the cover.
     */
    fun arrange(geometry: GridGeometry, items: List<HomeGridItem>): HomeGridArrangementResult {
        TODO("PR 4")
    }
}

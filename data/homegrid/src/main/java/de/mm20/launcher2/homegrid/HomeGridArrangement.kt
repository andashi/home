package de.mm20.launcher2.homegrid

import de.mm20.launcher2.grid.GridItem
import de.mm20.launcher2.grid.GridLayout
import de.mm20.launcher2.grid.LayoutIssue
import de.mm20.launcher2.grid.SizeLimits
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
 * on the cover clipped to the right half (#93). Spans stay in layout
 * coordinates; the grid draws them from [GridGeometry.firstVisibleColumn].
 */
object HomeGridArrangement {
    /**
     * Invariants: no two result cells overlap; every cell lies in
     * [GridGeometry.visibleRange] x rows; on the cover no cell starts left of
     * the fold column; the favorites widget is the only cell allowed to span
     * the fold and is clipped, not dropped, on the cover.
     */
    fun arrange(geometry: GridGeometry, items: List<HomeGridItem>): HomeGridArrangementResult {
        val byId = items.associateBy { it.id }
        val gridItems = items.map {
            GridItem(
                id = it.id,
                span = Span(it.x, it.y, it.w, it.h),
                // Provider limits were applied when the config was stored;
                // here every item may shrink or grow to what the grid holds.
                limits = SizeLimits.Unbounded,
                mayCrossFold = it.isFavorites,
            )
        }
        val normalized = GridLayout.normalize(geometry.spec, gridItems)
        val visible = if (geometry.isCover) {
            GridLayout.clampToWindow(normalized.items, geometry.visibleRange)
        } else {
            normalized.items
        }
        return HomeGridArrangementResult(
            cells = visible.map { HomeGridCell(byId.getValue(it.id), it.span) },
            issues = normalized.issues,
        )
    }
}

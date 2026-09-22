package de.mm20.launcher2.grid

import org.junit.Assert.assertTrue

/** A 4x6 phone grid, the default of ADR 0001 / D1. */
val Phone = GridSpec(columns = 4, rows = 6)

/** An 8x6 fold grid, the cover being columns 0..3 (D7). */
val Fold = GridSpec(columns = 8, rows = 6, foldColumn = 4)

fun item(
    id: String,
    x: Int,
    y: Int,
    w: Int = 1,
    h: Int = 1,
    limits: SizeLimits = SizeLimits.Unbounded,
    mayCrossFold: Boolean = false,
) = GridItem(id, Span(x, y, w, h), limits, mayCrossFold)

fun favorites(x: Int, y: Int, w: Int, h: Int) =
    GridItem("favorites", Span(x, y, w, h), SizeLimits.Unbounded, mayCrossFold = true)

fun List<GridItem>.byId(id: String): GridItem = first { it.id == id }

fun List<GridItem>.spanOf(id: String): Span = byId(id).span

/** Fails with the offending pair when any two items share a cell. */
fun assertNoOverlap(items: List<GridItem>) {
    for (i in items.indices) for (j in i + 1 until items.size) {
        assertTrue(
            "${items[i].id}@${items[i].span} overlaps ${items[j].id}@${items[j].span}",
            !items[i].span.overlaps(items[j].span),
        )
    }
}

fun assertInside(spec: GridSpec, items: List<GridItem>) {
    for (it in items) {
        assertTrue("${it.id}@${it.span} leaves the ${spec.columns}x${spec.rows} grid", it.span.fitsIn(spec.columns, spec.rows))
    }
}

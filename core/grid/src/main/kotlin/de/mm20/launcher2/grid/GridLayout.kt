package de.mm20.launcher2.grid

import java.util.TreeSet

/**
 * The layout engine: pure functions over [GridItem] lists. Every function is
 * deterministic (same input, same output), never mutates its input, and its
 * KDoc states the invariant the result satisfies.
 */
object GridLayout {

    /**
     * Reports every issue in [items] without changing anything: items outside
     * the grid, pairs of overlapping items (each pair once, in list order),
     * and items that cross [GridSpec.foldColumn] without permission.
     * Invariant: an empty result means [items] is a valid layout for [spec].
     */
    fun validate(spec: GridSpec, items: List<GridItem>): List<LayoutIssue> {
        val issues = mutableListOf<LayoutIssue>()
        for (item in items) {
            if (!item.span.fitsIn(spec.columns, spec.rows)) {
                issues += LayoutIssue.OutOfBounds(item.id, item.span)
            }
            if (crossesFold(spec, item.span) && !item.mayCrossFold) {
                issues += LayoutIssue.CrossesFold(item.id)
            }
        }
        for (i in items.indices) for (j in i + 1 until items.size) {
            if (items[i].span.overlaps(items[j].span)) {
                issues += LayoutIssue.Overlap(items[i].id, items[j].id)
            }
        }
        return issues
    }

    /**
     * Finds the first position in reading order (row by row, left to right)
     * where [newItem] fits with its current size, or returns null when the
     * grid has no room. The size is clamped to the item's limits and the grid
     * first. [columns] limits the search to the columns a window shows (the
     * cover, #93). Invariant: the returned item overlaps nothing in [items],
     * lies inside the grid and [columns], and respects the fold rule; [items]
     * is not modified.
     */
    fun place(
        spec: GridSpec,
        items: List<GridItem>,
        newItem: GridItem,
        columns: IntRange = 0 until spec.columns,
    ): GridItem? {
        val w = clampWidth(spec, newItem, newItem.span.w)
        val h = clampHeight(spec, newItem, newItem.span.h)
        val occupied = items.filter { it.id != newItem.id }.map { it.span }
        val span = firstFree(spec, occupied, w, h, newItem.mayCrossFold, columns) ?: return null
        return newItem.copy(span = span)
    }

    /**
     * Moves the item [id] to [to] (position and size; size clamped to the
     * item's limits and the grid, position clamped so the item stays inside
     * the grid, and nudged to one side of the fold line when it may not cross
     * it). Items the moved item now covers are pushed down: displaced items are
     * handled in (y, x, id) order, each re-placed at the first row at or below
     * its own row where it overlaps no settled item, and whatever it lands on
     * is displaced in turn. Invariants: no two result items overlap; no item
     * moved up or sideways except the one being moved; items lying entirely
     * above `to.y` are untouched; the same input always yields the same
     * output. When a displaced item runs out of rows, the item's minimum size
     * is larger than the grid, or the item may not cross the fold and cannot
     * be nudged clear of it, the input is returned unchanged with an
     * [LayoutIssue.Overflow] or [LayoutIssue.CrossesFold].
     */
    fun move(spec: GridSpec, items: List<GridItem>, id: String, to: Span): LayoutResult {
        val moving = items.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("no item with id '$id' in the layout")
        val w = clampWidth(spec, moving, to.w)
        val h = clampHeight(spec, moving, to.h)
        // A minimum larger than the grid cannot be clamped into it; answer
        // instead of letting coerceIn throw on an inverted range.
        if (w > spec.columns || h > spec.rows) {
            return LayoutResult(items, listOf(LayoutIssue.Overflow(id)))
        }
        val y = to.y.coerceIn(0, spec.rows - h)
        val x = nudgeClearOfFold(spec, to.x.coerceIn(0, spec.columns - w), w, moving.mayCrossFold)
            ?: return LayoutResult(items, listOf(LayoutIssue.CrossesFold(id)))
        val target = Span(x, y, w, h)

        // Settled spans never move again; pending items keep their span until
        // something settled lands on them.
        val settled = linkedMapOf(id to target)
        val pending = LinkedHashMap<String, Span>()
        for (item in items) if (item.id != id) pending[item.id] = item.span

        val queue = TreeSet<String>(compareBy<String>({ pending[it]!!.y }, { pending[it]!!.x }, { it }))
        for ((pid, span) in pending) if (span.overlaps(target)) queue += pid

        while (queue.isNotEmpty()) {
            val current = queue.pollFirst()!!
            val span = pending.remove(current)!!
            val landed = firstRowBelow(spec, settled.values, span)
                ?: return LayoutResult(items, listOf(LayoutIssue.Overflow(current)))
            settled[current] = landed
            for ((pid, pspan) in pending) {
                if (pid !in queue && pspan.overlaps(landed)) queue += pid
            }
        }

        val result = items.map { item ->
            val span = settled[item.id] ?: pending[item.id]!!
            if (span == item.span) item else item.copy(span = span)
        }
        return LayoutResult(result)
    }

    /**
     * Resizes the item [id] to [w] x [h] in place, clamped to its limits and to
     * the grid (the item slides left or up if the new size would stick out),
     * then pushes down whatever it now covers exactly as [move] does.
     * Invariants: those of [move].
     */
    fun resize(spec: GridSpec, items: List<GridItem>, id: String, w: Int, h: Int): LayoutResult {
        val item = items.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("no item with id '$id' in the layout")
        return move(spec, items, id, Span(item.span.x, item.span.y, w, h))
    }

    /**
     * Turns a layout as written in a config file into one that can be drawn:
     * spans below an item's minimum are enlarged ([LayoutIssue.BelowMinimum]),
     * spans above its maximum or the grid are shrunk, items sticking out of
     * the grid are slid back in when possible and dropped otherwise
     * ([LayoutIssue.OutOfBounds]), items crossing the fold are nudged to one
     * side or dropped ([LayoutIssue.CrossesFold]), and an item overlapping an
     * earlier one is pushed down to the first free row at or below its own
     * ([LayoutIssue.Overlap]) or dropped when there is none
     * ([LayoutIssue.Overflow]). Items are processed in list order, so the
     * earlier item always keeps its place. Invariant: the result passes
     * [validate] with no issues.
     */
    fun normalize(spec: GridSpec, items: List<GridItem>): LayoutResult {
        val issues = mutableListOf<LayoutIssue>()
        val result = mutableListOf<GridItem>()
        for (item in items) {
            val requested = item.span
            val w = clampWidth(spec, item, requested.w)
            val h = clampHeight(spec, item, requested.h)
            if (w > spec.columns || h > spec.rows) {
                // the minimum itself does not fit; nothing to slide
                issues += LayoutIssue.OutOfBounds(item.id, requested)
                continue
            }
            if (w > requested.w || h > requested.h) {
                issues += LayoutIssue.BelowMinimum(item.id, requested, Span(requested.x, requested.y, w, h))
            }
            val y = requested.y.coerceIn(0, spec.rows - h)
            val x = nudgeClearOfFold(spec, requested.x.coerceIn(0, spec.columns - w), w, item.mayCrossFold)
            if (x == null) {
                issues += LayoutIssue.CrossesFold(item.id)
                continue
            }
            var span = Span(x, y, w, h)
            val blocker = result.firstOrNull { it.span.overlaps(span) }
            if (blocker != null) {
                issues += LayoutIssue.Overlap(blocker.id, item.id)
                // Pushed down like move() does: the first row at or below its
                // own, in its own column band. Nothing slides sideways.
                val below = firstRowBelow(spec, result.map { it.span }, span)
                if (below == null) {
                    issues += LayoutIssue.Overflow(item.id)
                    continue
                }
                span = below
            }
            result += if (span == item.span) item else item.copy(span = span)
        }
        return LayoutResult(result, issues)
    }

    /**
     * The layout as seen through a window of [columns] (layout coordinates):
     * on a fold's cover, the right half (#93). Items outside the window are
     * left out; an item reaching past its edge (the favorites widget spanning
     * the fold) is clipped to it. Spans stay in layout coordinates.
     * Invariant: every result item lies inside [columns].
     */
    fun clampToWindow(items: List<GridItem>, columns: IntRange): List<GridItem> {
        val start = columns.first
        val end = columns.last + 1
        return items.mapNotNull { item ->
            val span = item.span
            val left = maxOf(span.x, start)
            val right = minOf(span.right, end)
            when {
                right <= left -> null
                left == span.x && right == span.right -> item
                else -> item.copy(span = span.copy(x = left, w = right - left))
            }
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun clampWidth(spec: GridSpec, item: GridItem, w: Int): Int =
        w.coerceIn(item.limits.minW, maxOf(item.limits.minW, minOf(item.limits.maxW, spec.columns)))

    private fun clampHeight(spec: GridSpec, item: GridItem, h: Int): Int =
        h.coerceIn(item.limits.minH, maxOf(item.limits.minH, minOf(item.limits.maxH, spec.rows)))

    private fun crossesFold(spec: GridSpec, span: Span): Boolean {
        val fold = spec.foldColumn ?: return false
        return span.x < fold && span.right > fold
    }

    /**
     * Returns [x] when the span may stay there, the x of the side of the fold
     * holding more of the span (ties go left) when it must not cross, or null
     * when the span is wider than either side.
     */
    private fun nudgeClearOfFold(spec: GridSpec, x: Int, w: Int, mayCrossFold: Boolean): Int? {
        val fold = spec.foldColumn ?: return x
        if (mayCrossFold || !crossesFold(spec, Span(x, 0, w, 1))) return x
        val leftCapacity = fold
        val rightCapacity = spec.columns - fold
        val onLeft = fold - x
        val onRight = x + w - fold
        val preferLeft = onLeft >= onRight
        return when {
            preferLeft && w <= leftCapacity -> fold - w
            w <= rightCapacity -> fold
            w <= leftCapacity -> fold - w
            else -> null
        }
    }

    private fun firstFree(
        spec: GridSpec,
        occupied: Collection<Span>,
        w: Int,
        h: Int,
        mayCrossFold: Boolean,
        columns: IntRange,
    ): Span? {
        val start = maxOf(columns.first, 0)
        val end = minOf(columns.last + 1, spec.columns)
        if (w > end - start || h > spec.rows) return null
        for (y in 0..spec.rows - h) for (x in start..end - w) {
            val candidate = Span(x, y, w, h)
            if (!mayCrossFold && crossesFold(spec, candidate)) continue
            if (occupied.none { it.overlaps(candidate) }) return candidate
        }
        return null
    }

    /** The first row at or below [span]'s own row where it overlaps none of [settled]. */
    private fun firstRowBelow(spec: GridSpec, settled: Collection<Span>, span: Span): Span? {
        for (y in span.y..spec.rows - span.h) {
            val candidate = span.copy(y = y)
            if (settled.none { it.overlaps(candidate) }) return candidate
        }
        return null
    }
}

package de.mm20.launcher2.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Property-style tests: a seeded generator builds hundreds of random valid
 * layouts and random operations, and every result is checked against the
 * invariants the engine promises. The seed is fixed so a failure is
 * reproducible; it is printed in the assertion message.
 */
class GridLayoutPropertyTest {

    private val seed = 20260922L
    private val iterations = 400

    private fun randomSpec(rnd: Random): GridSpec {
        val fold = rnd.nextBoolean()
        val columns = if (fold) 8 else 4
        val rows = 4 + rnd.nextInt(5)
        return GridSpec(columns, rows, if (fold) columns / 2 else null)
    }

    /** A valid layout: items placed with [GridLayout.place], so it never overlaps. */
    private fun randomLayout(rnd: Random, spec: GridSpec): List<GridItem> {
        var items = emptyList<GridItem>()
        val count = rnd.nextInt(7)
        repeat(count) { n ->
            val w = 1 + rnd.nextInt(3)
            val h = 1 + rnd.nextInt(3)
            val limits = SizeLimits(
                minW = 1 + rnd.nextInt(w),
                minH = 1 + rnd.nextInt(h),
                maxW = w + rnd.nextInt(3),
                maxH = h + rnd.nextInt(3),
            )
            val candidate = GridItem("i$n", Span(0, 0, w, h), limits, mayCrossFold = n == 0 && rnd.nextBoolean())
            GridLayout.place(spec, items, candidate)?.let { items = items + it }
        }
        return items
    }

    private fun forEachCase(block: (rnd: Random, spec: GridSpec, items: List<GridItem>, label: String) -> Unit) {
        val rnd = Random(seed)
        repeat(iterations) { i ->
            val spec = randomSpec(rnd)
            val items = randomLayout(rnd, spec)
            val label = "seed=$seed iteration=$i spec=$spec items=$items"
            assertEquals("generator produced an invalid layout: $label", emptyList<LayoutIssue>(), GridLayout.validate(spec, items))
            block(rnd, spec, items, label)
        }
    }

    @Test
    fun `no two items overlap after move`() {
        forEachCase { rnd, spec, items, label ->
            if (items.isEmpty()) return@forEachCase
            val target = items[rnd.nextInt(items.size)]
            val to = Span(rnd.nextInt(spec.columns), rnd.nextInt(spec.rows), target.span.w, target.span.h)
            val result = GridLayout.move(spec, items, target.id, to)
            try {
                assertNoOverlap(result.items)
            } catch (e: AssertionError) {
                throw AssertionError("$label move=${target.id} to=$to result=$result: ${e.message}", e)
            }
        }
    }

    @Test
    fun `nothing leaves the grid after move or resize`() {
        forEachCase { rnd, spec, items, label ->
            if (items.isEmpty()) return@forEachCase
            val target = items[rnd.nextInt(items.size)]
            val moved = GridLayout.move(
                spec, items, target.id,
                Span(rnd.nextInt(spec.columns + 2) - 1, rnd.nextInt(spec.rows + 2) - 1, target.span.w, target.span.h),
            )
            val resized = GridLayout.resize(spec, items, target.id, rnd.nextInt(10), rnd.nextInt(10))
            try {
                assertInside(spec, moved.items)
                assertInside(spec, resized.items)
                assertNoOverlap(resized.items)
            } catch (e: AssertionError) {
                throw AssertionError("$label moved=$moved resized=$resized: ${e.message}", e)
            }
        }
    }

    @Test
    fun `move is deterministic and preserves ids and order`() {
        forEachCase { rnd, spec, items, label ->
            if (items.isEmpty()) return@forEachCase
            val target = items[rnd.nextInt(items.size)]
            val to = Span(rnd.nextInt(spec.columns), rnd.nextInt(spec.rows), target.span.w, target.span.h)
            val a = GridLayout.move(spec, items, target.id, to)
            val b = GridLayout.move(spec, items, target.id, to)
            assertEquals(label, a, b)
            assertEquals(label, items.map { it.id }, a.items.map { it.id })
        }
    }

    @Test
    fun `a rejected move returns the input unchanged`() {
        forEachCase { rnd, spec, items, label ->
            if (items.isEmpty()) return@forEachCase
            val target = items[rnd.nextInt(items.size)]
            val to = Span(rnd.nextInt(spec.columns), rnd.nextInt(spec.rows), target.span.w, target.span.h)
            val result = GridLayout.move(spec, items, target.id, to)
            if (!result.isClean) {
                assertEquals(label, items, result.items)
            }
        }
    }

    @Test
    fun `push-down only ever moves other items down`() {
        forEachCase { rnd, spec, items, label ->
            if (items.isEmpty()) return@forEachCase
            val target = items[rnd.nextInt(items.size)]
            val to = Span(rnd.nextInt(spec.columns), rnd.nextInt(spec.rows), target.span.w, target.span.h)
            val result = GridLayout.move(spec, items, target.id, to)
            if (!result.isClean) return@forEachCase
            for (before in items) {
                if (before.id == target.id) continue
                val after = result.items.byId(before.id)
                assertEquals("$label: ${before.id} changed column", before.span.x, after.span.x)
                assertEquals("$label: ${before.id} changed size", before.span.w to before.span.h, after.span.w to after.span.h)
                assertTrue("$label: ${before.id} moved up (${before.span} -> ${after.span})", after.span.y >= before.span.y)
            }
        }
    }

    @Test
    fun `normalize always yields a valid layout`() {
        val rnd = Random(seed)
        repeat(iterations) { i ->
            val spec = randomSpec(rnd)
            // deliberately invalid: random spans, random limits, duplicates of everything
            val items = List(rnd.nextInt(8)) { n ->
                val w = 1 + rnd.nextInt(5)
                val h = 1 + rnd.nextInt(5)
                GridItem(
                    "i$n",
                    Span(rnd.nextInt(spec.columns + 2) - 1, rnd.nextInt(spec.rows + 2) - 1, w, h),
                    SizeLimits(1 + rnd.nextInt(3), 1 + rnd.nextInt(3), 3 + rnd.nextInt(4), 3 + rnd.nextInt(4)),
                    mayCrossFold = n == 0 && rnd.nextBoolean(),
                )
            }
            val label = "seed=$seed iteration=$i spec=$spec items=$items"
            val result = GridLayout.normalize(spec, items)
            assertEquals("$label result=$result", emptyList<LayoutIssue>(), GridLayout.validate(spec, result.items))
            assertEquals("$label: order changed", items.map { it.id }.filter { id -> result.items.any { it.id == id } }, result.items.map { it.id })
            assertEquals("$label: normalize is not idempotent", result.items, GridLayout.normalize(spec, result.items).items)
        }
    }

    @Test
    fun `clampToWindow never produces an item outside the cover`() {
        forEachCase { _, spec, items, label ->
            val fold = spec.foldColumn ?: return@forEachCase
            val window = fold until spec.columns
            val cover = GridLayout.clampToWindow(items, window)
            try {
                assertInsideColumns(window, spec.rows, cover)
                assertNoOverlap(cover)
            } catch (e: AssertionError) {
                throw AssertionError("$label cover=$cover: ${e.message}", e)
            }
        }
    }
}

package de.mm20.launcher2.ui.launcher.search.common.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.ui.launcher.glass.GlassSurface
import de.mm20.launcher2.ui.launcher.glass.segmentEdges
import kotlin.math.ceil

fun <T : SavableSearchable> LazyListScope.GridResults(
    key: String,
    items: List<T>,
    itemContent: @Composable (T) -> Unit,
    before: @Composable (() -> Unit)? = null,
    after: @Composable (() -> Unit)? = null,
    columns: Int,
    reverse: Boolean = false,
) {
    // One glass card made of lazily laid out slices (#91): the header, each
    // row, the footer. A slice is open where it meets the next one.
    val rows = ceil(items.size / columns.toFloat()).toInt()
    val first = if (before != null) 1 else 0
    val segments = first + rows + if (after != null) 1 else 0
    val lastSpacing = Modifier.padding(
        top = if (reverse) 8.dp else 0.dp,
        bottom = if (!reverse) 8.dp else 0.dp,
    )

    if (before != null) {
        item(
            key = "$key-before",
            contentType = { "$key-before" },
        ) {
            GlassSurface(
                modifier = if (segments == 1) lastSpacing else Modifier,
                openEdges = segmentEdges(0, segments, reverse),
            ) {
                Box { before() }
            }
        }
    }

    items(
        rows,
        key = {
            "$key-$it"
        },
        contentType = { key }
    ) {
        val segment = first + it
        val isTopRow = if (reverse) it == rows - 1 else it == 0
        val isBottomRow = if (reverse) it == 0 else it == rows - 1
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (segment == segments - 1) lastSpacing else Modifier),
            openEdges = segmentEdges(segment, segments, reverse),
        ) {
            Row(
                modifier = Modifier.padding(
                    top = if (isTopRow) 8.dp else 0.dp,
                    bottom = if (isBottomRow) 8.dp else 0.dp,
                    start = if (columns == 1) 0.dp else 4.dp,
                    end = if (columns == 1) 0.dp else 4.dp,
                )
            ) {
                for (i in 0 until columns) {
                    val item = items.getOrNull(it * columns + i)
                    if (item != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                        ) {
                            itemContent(item)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (after != null) {
        item(
            key = "$key-after",
            contentType = { "$key-after" },
        ) {
            GlassSurface(
                modifier = lastSpacing,
                openEdges = segmentEdges(segments - 1, segments, reverse),
            ) {
                Box { after() }
            }
        }
    }
}

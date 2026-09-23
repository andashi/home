package de.mm20.launcher2.ui.launcher.search.common.list

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.ui.layout.BottomReversed
import de.mm20.launcher2.ui.launcher.glass.GlassEdge
import de.mm20.launcher2.ui.launcher.glass.GlassSurface

fun <T : SavableSearchable> LazyListScope.ListResults(
    key: String,
    items: List<T>,
    itemContent: @Composable ColumnScope.(T, Boolean, Int) -> Unit,
    before: @Composable (ColumnScope.() -> Unit)? = null,
    after: @Composable (ColumnScope.() -> Unit)? = null,
    reverse: Boolean = false,
    selectedIndex: Int = -1,
) {
    if (before != null) {
        item(
            key = "$key-before",
            contentType = { "$key-before" },
        ) {
            ListItemSurface(
                isFirst = true,
                isLast = after == null && items.isEmpty(),
                reverse = reverse,
                isBeforeExpanded = selectedIndex == 0,
            ) {
                before()
            }
        }
    }
    val rows = items.size
    items(
        items.size,
        key = {
            "$key-${items[it].key}"
        },
        contentType = { key },
    ) {
        val item = items[it]
        val showDetails = it == selectedIndex

        ListItemSurface(
            isFirst = it == 0 && before == null,
            isLast = it == rows - 1 && after == null,
            reverse = reverse,
            isExpanded = showDetails,
            isBeforeExpanded = selectedIndex - 1 == it,
            isAfterExpanded = selectedIndex >= 0 && selectedIndex + 1 == it,
        ) {
            itemContent(item, showDetails, it)
        }
    }
    if (after != null) {
        item(
            key = "$key-after",
            contentType = { "$key-after" },
        ) {
            ListItemSurface(
                isFirst = before == null && items.isEmpty(),
                isLast = true,
                reverse = reverse,
                isAfterExpanded = selectedIndex == items.lastIndex,
            ) {
                after()
            }
        }
    }
}

@Composable
fun LazyItemScope.ListItemSurface(
    isFirst: Boolean = false,
    isLast: Boolean = false,
    reverse: Boolean = false,
    isExpanded: Boolean = false,
    isBeforeExpanded: Boolean = false,
    isAfterExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Collapsed items are one glass card, open between them; an expanded
    // item stands out as a card of its own, and its neighbours close towards
    // it (#91).
    val startClosed = isFirst || isAfterExpanded || isExpanded
    val endClosed = isLast || isBeforeExpanded || isExpanded
    val topClosed = if (reverse) endClosed else startClosed
    val bottomClosed = if (reverse) startClosed else endClosed
    val openEdges = buildSet {
        if (!topClosed) add(GlassEdge.Top)
        if (!bottomClosed) add(GlassEdge.Bottom)
    }

    val transition = updateTransition(isExpanded)
    val padding by transition.animateDp {
        if (it) 8.dp else 0.dp
    }

    val spacing = if (reverse) {
        Modifier.padding(
            bottom = if (!isFirst) padding else 0.dp,
            top = if (!isLast) padding else 8.dp
        )
    } else {
        Modifier.padding(
            top = if (!isFirst) padding else 0.dp,
            bottom = if (!isLast) padding else 8.dp
        )
    }

    GlassSurface(
        modifier = spacing
            .animateItem()
            .fillMaxWidth(),
        openEdges = openEdges,
    ) {
        Column(
            verticalArrangement = if (reverse) Arrangement.BottomReversed else Arrangement.Top
        ) {
            content()
        }
    }
}

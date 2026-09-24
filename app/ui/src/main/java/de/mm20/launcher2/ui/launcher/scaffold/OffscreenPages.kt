package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * The scaffold's pages that are not open (search, the feed, ...): kept
 * composed and laid out, so their state survives and opening one is
 * instant, but placed a window's width to the right, out of the viewport.
 *
 * Nothing here can be seen, so nothing here is paid for before the frame
 * that can (#122):
 * - the pages are never drawn. Compose does not cull, so a page out of the
 *   viewport was still recorded whenever it was invalidated.
 * - a new window size reaches them two frames late. On unfold the hidden
 *   search page's app grid gains columns, and composing and measuring those
 *   in the same pass as the home screen delayed the first frame on the
 *   inner display. Two frames, because the traversal that first measures a
 *   new window often does not draw - the window waits for the display
 *   transition - and one frame later would be exactly the first drawn one.
 *
 * A page that opens leaves this box for the scaffold's visible slot and is
 * laid out and drawn there at the window's size, as before.
 */
/** How long after the window's last size change the pages take it, in ms. */
internal const val OffscreenPagesSettleMillis = 34L

@Composable
internal fun OffscreenPages(
    /** How far right the pages sit, in px: the window's width. */
    offsetX: () -> Int,
    content: @Composable () -> Unit,
) {
    // What the pages are laid out with, and the window's latest constraints;
    // the second is written by the layout and never read there, so it does
    // not invalidate it.
    var applied by remember { mutableStateOf<Constraints?>(null) }
    var latest by remember { mutableStateOf<Constraints?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.filterNotNull().collectLatest { constraints ->
            if (constraints == applied) return@collectLatest
            withFrameNanos { }
            withFrameNanos { }
            applied = constraints
        }
    }
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(x = offsetX(), y = 0) }
            .drawWithContent { },
    ) { measurables, constraints ->
        latest = constraints
        // As the Box this replaces: the pages without a minimum size.
        val pages = (applied ?: constraints).copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(pages) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

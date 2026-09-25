package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * How long after the window's last size change the pages take it, in ms:
 * past the display switch (0.2-0.35 s dark on the fold emulator) and an
 * unfold animation on hardware.
 */
internal const val OffscreenPagesSettleMillis = 1000L

/**
 * The scaffold's pages that are not open (search, the feed, ...): kept
 * composed and laid out, so their state survives and opening one is
 * instant, but placed a window's width to the right, out of the viewport.
 *
 * Nothing here can be seen, so nothing here is paid for while the display
 * switches (#122):
 * - the pages are never drawn. Compose does not cull, so a page out of the
 *   viewport was still recorded whenever it was invalidated.
 * - a new window size reaches them [OffscreenPagesSettleMillis] after the
 *   last change. On unfold the hidden search page's app grid gains columns,
 *   and composing and measuring those in the same pass as the home screen
 *   delayed the first frame on the inner display; doing it a couple of
 *   frames later still landed inside the switch, which keeps the display
 *   dark until every window has drawn, as more launcher frames to wait for.
 *
 * A page that opens leaves this box for the scaffold's visible slot and is
 * laid out and drawn there at the window's size, as before.
 */
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
            if (applied != null) delay(OffscreenPagesSettleMillis)
            applied = constraints
        }
    }
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxSize()
            // Absolute: right to left a relative offset moves the box left,
            // and a page still at a wider window's size reaches into view.
            .absoluteOffset { IntOffset(x = offsetX(), y = 0) }
            .drawWithContent { },
    ) { measurables, constraints ->
        latest = constraints
        // As the Box this replaces: the pages without a minimum size.
        val pages = (applied ?: constraints).copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.fastMap { it.measure(pages) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.fastForEach { it.place(0, 0) }
        }
    }
}

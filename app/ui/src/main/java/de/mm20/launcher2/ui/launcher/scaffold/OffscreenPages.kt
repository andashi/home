package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.IntOffset

/**
 * The scaffold's pages that are not open (search, the feed, ...): kept
 * composed and laid out, so their state survives and opening one is
 * instant, but placed a window's width to the right, out of the viewport.
 *
 * They are never drawn (#122). Compose does not cull, so a page out of the
 * viewport was still recorded whenever it was invalidated - on unfold all of
 * them, at the new window size, before the first frame there. A page that
 * opens moves out of this box into the scaffold's visible slot and is drawn
 * there, so nothing that can be seen is lost.
 */
@Composable
internal fun OffscreenPages(
    /** How far right the pages sit, in px: the window's width. */
    offsetX: () -> Int,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(x = offsetX(), y = 0) }
            .drawWithContent { }
    ) {
        content()
    }
}

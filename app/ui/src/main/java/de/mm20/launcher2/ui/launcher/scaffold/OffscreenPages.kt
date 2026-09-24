package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * The scaffold's pages that are not open (search, the feed, ...): kept
 * composed and laid out, so their state survives and opening one is
 * instant, but placed a window's width to the right, out of the viewport.
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
    ) {
        content()
    }
}

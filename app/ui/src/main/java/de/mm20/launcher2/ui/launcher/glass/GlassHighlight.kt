package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The layer behind a highlighted result - the best match (#91). On glass
 * ([LocalClearIcons]) it is a state layer, the content color at a low alpha,
 * which reads on glass in light and dark themes alike; an opaque
 * surfaceVariant there would punch a solid patch into the card. Elsewhere
 * (the Material sheets) it stays upstream's color.
 */
@Composable
@ReadOnlyComposable
fun resultHighlight(): Color =
    if (LocalClearIcons.current) MaterialTheme.colorScheme.onSurface.copy(alpha = HighlightAlpha)
    else MaterialTheme.colorScheme.surfaceVariant

internal const val HighlightAlpha = 0.12f

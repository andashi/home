package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The container of a menu on the search screen (#91), in place of Material's
 * DropdownMenuGroup: a glass card. The menu is a popup window of its own; the
 * backdrop is mapped through its position on screen (glassBackdrop).
 */
@Composable
fun GlassMenuGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier.width(IntrinsicSize.Max)) {
        CompositionLocalProvider(LocalOnGlass provides true) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import de.mm20.launcher2.ui.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.ui.common.FavoritesVM
import de.mm20.launcher2.ui.launcher.search.common.grid.GridItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The favorites of the search screen, without tags, the frequently-used
 * rows or an edit button: the grid cell shows pins, nothing else (D2).
 */
class FavoritesGridVM : FavoritesVM() {
    override val tagsExpanded: Flow<Boolean> = flowOf(false)
    override val compactTags: Flow<Boolean> = flowOf(false)
    override fun setTagsExpanded(expanded: Boolean) = Unit
}

/**
 * The dock as a widget (D2): the first `columns * rows` pins, laid out in
 * [columns] columns of icons without labels (ADR 0004). What does not fit is
 * clipped and stays reachable through search; the overflow count is edit
 * mode's job (PR 5). Icons go through the same [GridItem] as the search
 * results, so launching, badges and the icon cache are shared.
 */
@Composable
fun FavoritesGridWidget(
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
) {
    val viewModel: FavoritesGridVM = viewModel(key = "favorites-grid")
    val favorites by remember { viewModel.favorites }.collectAsState(emptyList())
    val shown = remember(favorites, columns, rows) { favorites.take(columns * rows) }
    val overflow = favorites.size - shown.size
    val editing = LocalGridEditing.current

    Box(modifier = modifier) {
        // Edit mode says how many pins the cell does not show (D2): make it
        // taller or wider, or leave them to search.
        if (editing && overflow > 0) {
            Text(
                text = stringResource(R.string.grid_favorites_overflow, overflow),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .semantics { contentDescription = "grid-favorites-overflow" },
            )
        }
    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until columns) {
                    val item = shown.getOrNull(row * columns + column)
                    if (item != null) {
                        key(item.key) {
                            GridItem(
                                modifier = Modifier.weight(1f),
                                item = item,
                                showLabels = false,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    }
}

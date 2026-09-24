package de.mm20.launcher2.ui.launcher.searchbar

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import de.mm20.launcher2.ui.launcher.glass.GlassSurface
import de.mm20.launcher2.ui.launcher.glass.GlassChip
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.searchactions.actions.SearchAction
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.SearchActionIcon
import de.mm20.launcher2.ui.modifier.consumeAllScrolling
import de.mm20.launcher2.ui.settings.SettingsActivity

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ColumnScope.SearchBarActions(
    modifier: Modifier = Modifier,
    actions: List<SearchAction>,
    highlightedAction: SearchAction?,
    reverse: Boolean = false,
) {
    val context = LocalContext.current
    AnimatedVisibility(actions.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                // The row as automation finds it: a test tag exposed as its
                // resource id, silent to screen readers (#116 review).
                .semantics { testTagsAsResourceId = true }
                .testTag("search-actions")
                .consumeAllScrolling()
                .height(48.dp)
                .padding(bottom = if (reverse) 0.dp else 8.dp, top = if (reverse) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp)
        ) {
            items(actions) {
                GlassChip(
                    modifier = Modifier.padding(4.dp),
                    // The best match: Enter starts it. A highlight, not a selection.
                    highlighted = it == highlightedAction,
                    onClick = {
                        it.start(context)
                    },
                    label = it.label,
                    leadingIcon = {
                        SearchActionIcon(
                            action = it,
                            size = 18.dp,
                        )
                    }
                )
            }
            item {
                // The edit button as a round glass chip, like the rest of the row (#91).
                GlassSurface(
                    modifier = Modifier.padding(start = 4.dp).size(40.dp),
                    shape = CircleShape,
                ) {
                    IconButton(
                        onClick = {
                            context.startActivity(
                                Intent(context, SettingsActivity::class.java).apply {
                                    putExtra(SettingsActivity.EXTRA_ROUTE, SettingsActivity.ROUTE_SEARCH_ACTIONS)
                                }
                            )
                        }
                    ) {
                        Icon(painterResource(R.drawable.edit_24px), contentDescription = null)
                    }
                }
            }
        }
    }
}
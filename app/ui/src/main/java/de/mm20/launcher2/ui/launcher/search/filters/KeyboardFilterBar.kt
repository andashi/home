package de.mm20.launcher2.ui.launcher.search.filters

import androidx.compose.ui.graphics.RectangleShape
import de.mm20.launcher2.ui.launcher.glass.GlassSurface
import de.mm20.launcher2.ui.launcher.glass.GlassChip
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.preferences.KeyboardFilterBarItem
import de.mm20.launcher2.search.SearchFilters
import de.mm20.launcher2.ui.modifier.consumeAllScrolling

@Composable
fun KeyboardFilterBar(
    filters: SearchFilters,
    onFiltersChange: (SearchFilters) -> Unit,
    items: List<KeyboardFilterBarItem>,
) {
    val context = LocalContext.current
    val allCategoriesEnabled = filters.allCategoriesEnabled
    // A glass bar over the keyboard (#91): square, as the keyboard's edge is.
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .height(50.dp),
        shape = RectangleShape,
        lensRadius = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .consumeAllScrolling()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in items.indices) {
                val item = items[i]
                val prevItem = items.getOrNull(i - 1)
                if (prevItem != null && prevItem.isCategory != item.isCategory) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(36.dp)
                            .padding(end = 8.dp)
                    )
                }
                val selected = filters.isSelected(item)
                GlassChip(
                    modifier = Modifier.padding(end = if (i == items.lastIndex) 0.dp else 8.dp),
                    selected = selected,
                    onClick = {
                        onFiltersChange(filters.toggle(item))
                    },
                    // Selected, the chip shows its check instead (#108).
                    leadingIcon = if (selected) null else {
                        {
                            Icon(
                                painter = painterResource(item.iconMedium),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    label = item.getLabel(context),
                )
            }
        }
    }
}
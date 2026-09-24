package de.mm20.launcher2.ui.launcher.search.filters

import de.mm20.launcher2.ui.launcher.glass.LocalOnGlass
import de.mm20.launcher2.ui.launcher.glass.GlassChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.search.SearchFilters
import de.mm20.launcher2.ui.R

@Composable
fun SearchFilters(
    filters: SearchFilters,
    onFiltersChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
    settings: Boolean = false
) {
    val allCategoriesEnabled = filters.allCategoriesEnabled
    Column(
        modifier = modifier
            .padding(horizontal = 4.dp),
    ) {
        val toggle = { onFiltersChange(filters.copy(hiddenItems = !filters.hiddenItems)) }
        val icon = @Composable {
            Icon(
                painter = painterResource(R.drawable.visibility_off_20px),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize)
            )
        }
        val label = stringResource(R.string.preference_hidden_items)
        // Shared with the settings screen's opaque sheet, where a glass chip
        // would be a hole down to the wallpaper and its selected state too
        // faint (review on #98): glass only on the search screen.
        if (LocalOnGlass.current) {
            GlassChip(selected = filters.hiddenItems, onClick = toggle, leadingIcon = icon, label = label)
        } else {
            FilterChip(
                selected = filters.hiddenItems,
                onClick = toggle,
                leadingIcon = icon,
                label = { Text(label) },
            )
        }
    }
}
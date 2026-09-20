package de.mm20.launcher2.ui.launcher.search.filters

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
        FilterChip(
            selected = filters.hiddenItems,
            onClick = {
                onFiltersChange(filters.copy(hiddenItems = !filters.hiddenItems))
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.visibility_off_20px),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            },
            label = { Text(stringResource(R.string.preference_hidden_items)) }
        )
    }
}
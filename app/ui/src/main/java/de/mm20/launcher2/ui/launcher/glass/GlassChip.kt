package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import de.mm20.launcher2.ui.R

/** A chip on the search screen as a glass pill (#91); selected is a stronger tint. */
@Composable
fun GlassChip(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** A stronger tint without being a selection, e.g. the best match (#108). */
    highlighted: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    GlassSurface(
        modifier = modifier.heightIn(min = 32.dp),
        pill = true,
        tintBoost = if (selected) SelectedTintBoost else 0f,
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                // On the node you tap, which merges the label: TalkBack and
                // uiautomator read the state from there (#108).
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                // A selected chip says so without its color: a check, in place
                // of its icon, as a Material filter chip does. The tint alone
                // was a shade too faint to see (#108).
                selected && label != null -> Icon(
                    painterResource(R.drawable.check_20px),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).testTag(GlassChipCheckTag),
                )
                leadingIcon != null -> leadingIcon()
            }
            if (label != null) Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingIcon?.invoke()
        }
    }
}

/** A selected chip over an unselected one: clearly stronger at every contrast (#91). */
internal const val SelectedTintBoost = 0.2f

/** The check a selected chip shows; a test tag, so tests can find it. */
internal const val GlassChipCheckTag = "glass-chip-check"

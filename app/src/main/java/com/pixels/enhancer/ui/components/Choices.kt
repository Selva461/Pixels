package com.pixels.enhancer.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pixels.enhancer.R

/**
 * A chip that picks an option or switches something on. A selected chip is inverted and shows a
 * tick, so its state never depends on colour alone; an unselected one has a 3:1 outline. With
 * [edited] the label gets a dot and screen readers hear "edited" after the name.
 */
@Suppress("LongParameterList")
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    edited: Boolean = false,
    enabled: Boolean = true,
) {
    val spoken = if (edited) stringResource(R.string.a11y_edited_name, label) else null
    val leading: (@Composable () -> Unit)? = when {
        selected -> ({ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) })
        icon != null -> ({ Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) })
        else -> null
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                if (edited) "$label •" else label,
                modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
            )
        },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leading,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.surface,
            selectedLeadingIconColor = MaterialTheme.colorScheme.surface,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected, borderColor = MaterialTheme.colorScheme.outline),
    )
}

/** An icon button that stays on or off. "On" is drawn inverted, so it never relies on colour alone. */
@Composable
fun StateIconToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.onSurface,
            checkedContentColor = MaterialTheme.colorScheme.surface,
        ),
    ) { Icon(icon, contentDescription = description) }
}

package io.droyagent.app.nativeui.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.droyagent.app.R

internal data class DroySettingsLink(
    val title: String,
    @param:DrawableRes val icon: Int,
    val tag: String,
    val supporting: String? = null,
    val onClick: () -> Unit,
)

@Composable
internal fun DroySettingsSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
}

/** SegmentedListItem owns press shape/effect springs; layout and hit targets remain stable. */
@Composable
internal fun DroySettingsLinkGroup(title: String? = null, items: List<DroySettingsLink>, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier.fillMaxWidth()) {
        title?.let { DroySettingsSectionTitle(it) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, item ->
                SegmentedListItem(onClick = item.onClick, enabled = enabled,
                    shapes = ListItemDefaults.segmentedShapes(index, items.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (item.supporting == null) 56.dp else 72.dp).testTag(item.tag),
                    leadingContent = { DroyIcon(item.icon, null) },
                    trailingContent = { DroyIcon(R.drawable.native_settings_chevron_right, null) },
                    supportingContent = item.supporting?.let { { Text(it) } },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(item.title) }
            }
        }
    }
}

@Composable
internal fun DroySettingsToggle(title: String, @DrawableRes icon: Int, checked: Boolean, enabled: Boolean,
    index: Int, count: Int, modifier: Modifier = Modifier, emphasized: Boolean = false, onCheckedChange: (Boolean) -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    SegmentedListItem(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        shapes = shapes.copy(selectedShape = shapes.shape),
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            selectedContainerColor = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { role = Role.Switch },
        leadingContent = { DroyIcon(icon, null) },
        trailingContent = { DroySwitch(checked, null, enabled = enabled) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(title) }
}

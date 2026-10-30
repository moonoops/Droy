package io.droyagent.app.nativeui.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun DroyIcon(@DrawableRes resource: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), contentDescription = description, modifier = modifier.size(24.dp))
}

@Composable
internal fun DroyMenuActionRow(@DrawableRes icon: Int, title: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) },
        leadingContent = { DroyIcon(icon, null) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}

@Composable
fun DroyIconAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
) {
    if (tonal) {
        FilledTonalIconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled,
            shape = RoundedCornerShape(18.dp)) { DroyIcon(icon, label) }
    } else {
        IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { DroyIcon(icon, label) }
    }
}

/** Off is a quiet surface; the checked thumb and track carry the emphasis. */
@Composable
fun DroySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, thumbContent: (@Composable () -> Unit)? = null) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier,
        enabled = enabled, thumbContent = thumbContent,
        colors = SwitchDefaults.colors(
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
        ))
}

/** The visible shape can change; the touch target remains 56 dp. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroyPrimaryIconAction(@DrawableRes icon: Int, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else 28.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>(),
        label = "primaryActionShape",
    )
    FilledIconButton(onClick = onClick, enabled = enabled, interactionSource = interactions,
        shape = RoundedCornerShape(corner), modifier = modifier.size(56.dp)) { DroyIcon(icon, label) }
}

/** Short, persistent feedback without replacing the conversation with an error card. */
@Composable
fun DroyErrorNotice(message: String, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier,
    @DrawableRes actionIcon: Int = io.droyagent.app.R.drawable.native_ui_refresh, actionLabel: String? = null) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            if (onRetry != null) DroyIconAction(actionIcon,
                actionLabel ?: androidx.compose.ui.res.stringResource(io.droyagent.app.R.string.native_retry), onRetry)
            DroyIconAction(io.droyagent.app.R.drawable.native_ui_close,
                androidx.compose.ui.res.stringResource(io.droyagent.app.R.string.native_dismiss), onDismiss)
        }
    }
}

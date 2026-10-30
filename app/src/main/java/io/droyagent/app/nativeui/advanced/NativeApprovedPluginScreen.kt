package io.droyagent.app.nativeui.advanced

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.DroySwitch as Switch

@Composable
fun NativeApprovedPluginScreen(
    id: String, state: NativeAdvancedState, model: NativeAdvancedViewModel, onBack: () -> Unit,
    onOpenTeam: (() -> Unit)? = null,
) {
    require(id in setOf("agent-team", "session-titles", "persistent-terminal"))
    val plugin = state.approvedPlugins.singleOrNull { it.id == id }
    var requested by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, state.ready, state.busy) {
        if (!state.ready) requested = false
        else if (!state.busy && !requested) { requested = true; model.loadApprovedPlugins() }
    }
    BackHandler(onBack = onBack)
    val title = stringResource(when (id) {
        "agent-team" -> R.string.native_advanced_team
        "session-titles" -> R.string.native_advanced_titles
        else -> R.string.native_advanced_persistent_terminal
    })
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_advanced_back), onBack)
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(8.dp))
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).testTag("native-approved-plugin:$id"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_advanced_enabled), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(plugin?.enabled == true, onCheckedChange = { model.enableApprovedPlugin(id, it) },
                        enabled = state.ready && plugin?.writable == true && !state.busy,
                        modifier = Modifier.testTag("native-approved-plugin-switch:$id"))
                }
            }
            Text(stringResource(when (id) {
                "agent-team" -> R.string.native_advanced_team_hint
                "session-titles" -> R.string.native_advanced_titles_hint
                else -> R.string.native_advanced_persistent_terminal_hint
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.approvedLoaded && plugin?.available != true) Text(stringResource(R.string.native_advanced_unavailable_plugin), color = MaterialTheme.colorScheme.error)
            if (plugin?.enabled == true && id == "agent-team" && onOpenTeam != null) {
                FilledTonalButton(onClick = onOpenTeam, modifier = Modifier.testTag("native-approved-team-open")) {
                    Text(stringResource(R.string.native_advanced_team_open))
                }
            }
            state.error?.let {
                Text(stringResource(if (it == "settings/conflict") R.string.native_advanced_conflict else R.string.native_advanced_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::loadApprovedPlugins, enabled = !state.busy) { Text(stringResource(R.string.native_advanced_refresh)) }
            }
            state.receipt?.let {
                if (it != "applied") Text(stringResource(when (it) {
                    "restart-required" -> R.string.native_advanced_restart
                    "overridden" -> R.string.native_advanced_overridden
                    "saved-failed" -> R.string.native_advanced_saved_failed
                    else -> R.string.native_advanced_failed
                }), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

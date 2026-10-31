package io.droyagent.app.nativeui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.DroySettingsLink
import io.droyagent.app.nativeui.ui.DroySettingsLinkGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePluginSettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize().testTag("native-settings-group"),
        containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.native_settings_plugins)) },
            navigationIcon = { DroyIconAction(R.drawable.native_settings_arrow_back,
                stringResource(R.string.native_settings_back), onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val groups = listOf(
                R.string.native_plugins_search to listOf(Triple("web-search", R.string.native_settings_web_search, R.drawable.native_settings_search)),
                R.string.native_plugins_agents to listOf(
                    Triple("subtask-models", R.string.native_settings_subtask_models, R.drawable.native_ui_model),
                    Triple("agent-loop", R.string.native_advanced_loop, R.drawable.native_settings_repeat),
                    Triple("agent-team", R.string.native_advanced_team, R.drawable.native_settings_groups)),
                R.string.native_plugins_tools to listOf(
                    Triple("terminal", R.string.native_settings_terminal, R.drawable.native_settings_terminal),
                    Triple("persistent-terminal", R.string.native_advanced_persistent_terminal, R.drawable.native_settings_terminal)),
                R.string.native_plugins_sessions to listOf(Triple("session-titles", R.string.native_advanced_titles, R.drawable.native_ui_edit_square)),
            )
            groups.forEach { (title, items) ->
                DroySettingsLinkGroup(stringResource(title), items.map { (id, label, icon) ->
                    DroySettingsLink(stringResource(label), icon, "native-settings-group:$id") { onOpen(id) }
                })
            }
        }
    }
}

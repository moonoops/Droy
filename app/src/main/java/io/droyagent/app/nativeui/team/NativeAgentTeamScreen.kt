package io.droyagent.app.nativeui.team

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.ui.DroyIconAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

/** Read-only team projection. Session navigation continues through the product resolver. */
@Composable
fun NativeAgentTeamScreen(sessionId: String?, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    var snapshot by remember(sessionId) { mutableStateOf<JSONObject?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sessionId, retry, lifecycle) {
        if (sessionId == null) return@LaunchedEffect
        val client = NativeApiClient()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) {
            try {
                snapshot = client.get("agent-team?sessionId=${NativeApiClient.encodeSegment(sessionId)}") as JSONObject
                failed = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true
            }
            delay(2000)
        } }
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp)) {
            DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), onBack)
            Text(stringResource(R.string.native_team_title), Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().testTag("native-agent-team"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (failed) item { Text(stringResource(R.string.native_team_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.native_team_retry)) } }
            val data = snapshot
            if (sessionId == null || data?.optBoolean("available") == false) item { Text(stringResource(R.string.native_team_empty)) }
            if (data?.optBoolean("available") == true) {
                if (data.optBoolean("failed")) item { Text(stringResource(R.string.native_team_failed), color = MaterialTheme.colorScheme.error) }
                item { Text(stringResource(R.string.native_team_members), style = MaterialTheme.typography.titleMedium) }
                items(data.rows("members"), key = { "member:${it.getString("id")}" }) { member ->
                    OutlinedCard(onClick = { onOpenSession(member.getString("id")) }, enabled = member.optBoolean("canOpen"),
                        modifier = Modifier.fillMaxWidth().testTag("native-team-member:${member.getString("id")}")) {
                        Column(Modifier.padding(16.dp)) {
                            Text(member.getString("name"), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when (member.optString("phase")) {
                                "failed" -> R.string.native_team_member_failed
                                "provisioning" -> R.string.native_team_preparing
                                else -> if (member.optString("role") == "lead") R.string.native_team_lead else R.string.native_team_member
                            }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text(stringResource(R.string.native_team_tasks), style = MaterialTheme.typography.titleMedium) }
                val tasks = data.rows("tasks")
                if (tasks.isEmpty()) item { Text(stringResource(R.string.native_team_no_tasks)) }
                items(tasks, key = { "task:${it.getString("id")}" }) { task ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(task.getString("subject"), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when (task.optString("status")) {
                                "completed" -> R.string.native_team_completed
                                "in_progress" -> R.string.native_team_in_progress
                                else -> R.string.native_team_pending
                            }), style = MaterialTheme.typography.labelMedium)
                            task.optString("description").takeIf { it.isNotBlank() }?.let { Text(it) }
                            task.optString("ownerName").takeIf { it.isNotBlank() && it != "null" }?.let { Text(stringResource(R.string.native_team_owner, it)) }
                            task.optJSONArray("writeScopes")?.let { scopes -> if (scopes.length() > 0)
                                Text(stringResource(R.string.native_team_scopes, (0 until scopes.length()).joinToString(", ") { scopes.getString(it) })) }
                            task.optJSONArray("writeScopeWarnings")?.let { warnings ->
                                for (index in 0 until warnings.length()) Text(warnings.getString(index), color = MaterialTheme.colorScheme.error) }
                            task.optJSONArray("blockedBy")?.let { blockers -> if (blockers.length() > 0)
                                Text(stringResource(R.string.native_team_blockers, (0 until blockers.length()).joinToString(", ") { blockers.getString(it) })) }
                        }
                    }
                }
            }
        }
    }
}

private fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.let { rows -> (0 until rows.length()).map(rows::getJSONObject) } ?: emptyList()

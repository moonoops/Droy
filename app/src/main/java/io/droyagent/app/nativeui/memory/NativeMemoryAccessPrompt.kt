package io.droyagent.app.nativeui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.droyagent.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Only displays live requests produced by the privacy boundary, never a list of grantable models. */
@Composable
fun NativeMemoryAccessPrompt(runtimeReady: Boolean) {
    val client = remember { NativeMemoryClient() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(runtimeReady, lifecycle) {
        if (!runtimeReady) { request = null; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val pending = client.mutate(NativeMemoryMutation("pendingAccess", "{}")) as JSONArray
                    val next = if (pending.length() == 0) null else pending.getJSONObject(0)
                    failed = false
                    request = next
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (request != null) failed = true }
                delay(1500)
            }
        }
    }
    val current = request ?: return
    fun decide(approved: Boolean) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try {
                client.mutate(NativeMemoryMutation("resolveAccess", JSONObject().put("id", current.getString("id")).put("approved", approved).toString()))
                if (request?.optString("id") == current.optString("id")) request = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { decide(false) },
        title = { Text(stringResource(R.string.native_memory_access_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.native_memory_access_question, current.getString("provider"), current.getString("model")))
                Text(current.getString("url"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val categories = current.getJSONArray("categories")
                Text((0 until categories.length()).map { index ->
                    stringResource(when (categories.getString(index)) {
                        "activity" -> R.string.native_memory_access_activity
                        "location" -> R.string.native_memory_access_location
                        "derived" -> R.string.native_memory_access_derived
                        else -> R.string.native_memory_access_memory
                    })
                }.joinToString(" · "))
                Text(stringResource(when (current.getString("purpose")) {
                    "compaction" -> R.string.native_memory_access_compaction
                    "extraction" -> R.string.native_memory_access_extraction
                    "session-title" -> R.string.native_memory_access_title_generation
                    else -> R.string.native_memory_access_conversation
                }), style = MaterialTheme.typography.bodyMedium)
                if (failed) Text(stringResource(R.string.native_memory_access_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { decide(true) }, enabled = !busy && !failed) { Text(stringResource(R.string.native_memory_access_allow)) } },
        dismissButton = { TextButton(onClick = { decide(false) }, enabled = !busy) { Text(stringResource(R.string.native_memory_cancel)) } })
}

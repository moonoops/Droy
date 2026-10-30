package io.droyagent.app.nativeui.tools

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.terminal.RemoteTerminalView

@Composable
internal fun NativeTerminalScreen(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val connection by model.terminalState.collectAsState()
    val view = remember(context) { RemoteTerminalView(context) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val colors = MaterialTheme.colorScheme
    var control by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        view.contentDescription = context.getString(R.string.native_tools_terminal_screen)
        view.setListener(object : RemoteTerminalView.Listener {
            override fun onInput(epoch: Long, utf8: ByteArray) { model.terminalInput(epoch, utf8) }
            override fun onResize(epoch: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) { model.terminalResize(epoch, cols, rows) }
            override fun onInteractionError(code: String) { model.terminalInteractionError(code) }
        })
        onDispose { view.release() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) resumed = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(view, state.terminalKey, state.terminalReconnect, state.runtime.ready, resumed) {
        val key = state.terminalKey ?: return@LaunchedEffect
        if (!state.runtime.ready || !resumed) return@LaunchedEffect
        model.terminalFrames(key) { frame -> when (frame) {
            is NativeTerminalRenderEvent.Snapshot -> {
                if (!RemoteTerminalView.canRenderDimensions(frame.cols, frame.rows)) throw NativeApiException("NATIVE_TOOLS_TERMINAL_SIZE_UNSUPPORTED")
                view.resetSnapshot(frame.epoch, frame.ansi, frame.cols, frame.rows); view.setWritable(frame.epoch, frame.writable)
            }
            is NativeTerminalRenderEvent.Output -> view.appendOutput(frame.epoch, frame.ansi)
            is NativeTerminalRenderEvent.Control -> {
                if (!RemoteTerminalView.canRenderDimensions(frame.cols, frame.rows)) throw NativeApiException("NATIVE_TOOLS_TERMINAL_SIZE_UNSUPPORTED")
                view.resizeRemote(frame.epoch, frame.cols, frame.rows); view.setWritable(frame.epoch, frame.writable)
            }
            is NativeTerminalRenderEvent.Detached -> view.setWritable(frame.epoch, false)
        } }
    }
    SideEffect { view.setTerminalColors(colors.onSurface.toArgb(), colors.surface.toArgb(), colors.primary.toArgb()); view.setModifiers(control, alt) }
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(connection.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(model::refresh, enabled = state.runtime.ready, modifier = Modifier.heightIn(min = 48.dp).testTag("native-tools-terminal-reconnect")) { Text(stringResource(R.string.native_tools_reconnect)) }
        }
        if (connection.error != null) ToolsNotice(toolsError(requireNotNull(connection.error)))
        Text(when {
            !state.runtime.ready || connection.error != null -> stringResource(R.string.native_tools_disconnected)
            !connection.connected -> stringResource(R.string.native_tools_connecting)
            connection.processState != "running" -> terminalStatus(connection.processState)
            !connection.writable -> stringResource(R.string.native_tools_input_readonly)
            connection.pendingBytes > 0 -> stringResource(R.string.native_tools_input_pending)
            else -> stringResource(R.string.native_tools_running)
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("native-tools-terminal-surface"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(control, { control = !control }, label = { Text(stringResource(R.string.native_tools_key_ctrl)) }, enabled = connection.writable, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(alt, { alt = !alt }, label = { Text(stringResource(R.string.native_tools_key_alt)) }, enabled = connection.writable, modifier = Modifier.heightIn(min = 48.dp))
            for ((resource, keyCode) in listOf(R.string.native_tools_key_escape to KeyEvent.KEYCODE_ESCAPE, R.string.native_tools_key_tab to KeyEvent.KEYCODE_TAB,
                R.string.native_tools_key_left to KeyEvent.KEYCODE_DPAD_LEFT, R.string.native_tools_key_down to KeyEvent.KEYCODE_DPAD_DOWN,
                R.string.native_tools_key_up to KeyEvent.KEYCODE_DPAD_UP, R.string.native_tools_key_right to KeyEvent.KEYCODE_DPAD_RIGHT)) {
                TextButton({ view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)); view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)) },
                    enabled = connection.writable, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(resource)) }
            }
        }
    }
}

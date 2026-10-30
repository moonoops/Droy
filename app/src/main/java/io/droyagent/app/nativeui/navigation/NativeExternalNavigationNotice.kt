package io.droyagent.app.nativeui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R

@Composable
fun NativeExternalNavigationNotice(state: NativeExternalNavigationState, error: String?, onRetry: () -> Unit,
    onDismiss: () -> Unit, onStartRuntime: () -> Unit, canStartRuntime: Boolean) {
    if (error == null && state.phase !in setOf(NativeExternalNavigationPhase.WAITING, NativeExternalNavigationPhase.FAILED)) return
    if (error == null && state.blocker in setOf(NativeExternalNavigationBlocker.FOREGROUND, NativeExternalNavigationBlocker.SETUP, NativeExternalNavigationBlocker.PERMISSION_FLOW)) return
    val message = when {
        error == "native_external_navigation_invalid" -> R.string.native_external_invalid
        error == "native_external_navigation_storage" || state.storageError -> R.string.native_external_storage
        state.failure == NativeExternalNavigationFailure.NOT_FOUND -> R.string.native_external_missing
        state.failure == NativeExternalNavigationFailure.REQUEST_ENDED -> R.string.native_external_ended
        state.failure == NativeExternalNavigationFailure.ACCESS_DENIED -> R.string.native_external_denied
        state.failure == NativeExternalNavigationFailure.UNSUPPORTED -> R.string.native_external_unsupported
        state.failure == NativeExternalNavigationFailure.INVALID_RESOLUTION -> R.string.native_external_invalid
        state.blocker == NativeExternalNavigationBlocker.RUNTIME -> R.string.native_engine_unavailable
        state.blocker == NativeExternalNavigationBlocker.TARGET_NOT_READY -> R.string.native_external_waiting
        else -> R.string.native_external_failed
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("native-external-navigation-notice")) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("native-external-dismiss")) { Text(stringResource(R.string.native_companion_cancel)) }
                if (state.blocker == NativeExternalNavigationBlocker.RUNTIME && canStartRuntime)
                    TextButton(onStartRuntime, modifier = Modifier.heightIn(min = 48.dp).testTag("native-external-start")) { Text(stringResource(R.string.native_start_engine)) }
                else TextButton(onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("native-external-retry")) { Text(stringResource(R.string.native_retry)) }
            }
        }
    }
}

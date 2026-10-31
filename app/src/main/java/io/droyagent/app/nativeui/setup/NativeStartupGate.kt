package io.droyagent.app.nativeui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import kotlinx.coroutines.delay

/** Local routing must resolve before either the first-run flow or the app is mounted. */
@Composable
internal fun NativeStartupGate(
    requiresSetup: Boolean?,
    error: String?,
    onRetry: () -> Unit,
    onboarding: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    when (requiresSetup) {
        true -> onboarding()
        false -> content()
        null -> Surface(Modifier.fillMaxSize().testTag("native-startup"), color = MaterialTheme.colorScheme.surface) {
            var showProgress by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(250); showProgress = true }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.droy_companion), stringResource(R.string.droy_app_name), Modifier.size(80.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                    if (error != null) SetupError(error, onRetry)
                    else if (showProgress) {
                        val loading = stringResource(R.string.native_loading)
                        CircularProgressIndicator(Modifier.size(24.dp).testTag("native-startup-progress")
                            .semantics { contentDescription = loading }, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

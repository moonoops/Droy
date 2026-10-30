package io.droyagent.app.nativeui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.NativeProductSettingsHost
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.DroySwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browser transport preference, independent of DSH search-provider configuration. */
@Composable
internal fun NativeBrowserNetworkSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val host = remember(context) { NativeProductSettingsHost(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    suspend fun update(next: Boolean? = null) {
        busy = true
        failed = false
        try {
            enabled = withContext(Dispatchers.IO) {
                if (next != null) host.setDisplay("browserEncryptedDns", next)
                host.snapshot("browser-network").getBoolean("browserEncryptedDns")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }
    LaunchedEffect(host) { update() }
    BackHandler(onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_settings_back), onBack,
                modifier = Modifier.testTag("native-settings-browser-network-back"))
            Text(stringResource(R.string.native_settings_browser_network), style = MaterialTheme.typography.titleLarge)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ListItem(headlineContent = { Text(stringResource(R.string.native_settings_encrypted_dns)) },
                supportingContent = { Text(stringResource(R.string.native_settings_encrypted_dns_summary)) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                trailingContent = {
                    DroySwitch(enabled == true, { next -> scope.launch { update(next) } }, enabled = enabled != null && !busy,
                        modifier = Modifier.testTag("native-settings-browser-encrypted-dns"))
                })
            }
            if (failed) {
                Text(stringResource(R.string.native_settings_error_general), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { update() } }, enabled = !busy) { Text(stringResource(R.string.native_settings_retry)) }
            }
        }
    }
}

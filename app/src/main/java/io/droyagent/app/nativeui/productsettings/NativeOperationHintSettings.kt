package io.droyagent.app.nativeui.productsettings

import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.droyagent.app.NativeProductSettingsHost
import io.droyagent.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A product display preference, deliberately separate from authorization controls. */
@Composable
fun NativeOperationHintSettings() {
    val context = LocalContext.current
    val controller = remember(context) { ProductSettingsController(NativeProductSettingsHost(context)) }
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun refresh() { scope.launch {
        try { checked = controller.snapshot(ProductSettingsSection.General).getBoolean("automationScreenDisplay"); failed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    } }
    LaunchedEffect(controller) { refresh() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.nps_operation_hints), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked == true, role = Role.Switch,
                enabled = checked != null && !busy, onValueChange = { value -> scope.launch {
                    busy = true
                    try { controller.display("automationScreenDisplay", value); checked = value; failed = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                } }), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nps_screen), Modifier.weight(1f).padding(end = 12.dp))
                Switch(checked == true, null, enabled = checked != null && !busy)
            }
            if (failed) TextButton(onClick = ::refresh) { Text(stringResource(R.string.nps_retry)) }
        }
    }
}

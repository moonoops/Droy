package io.droyagent.app.nativeui.productsettings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationTextRuleEditor(initial: String, save: suspend (String) -> Unit,
    reset: suspend () -> String, onDismiss: () -> Unit) {
    var baseline by rememberSaveable { mutableStateOf(initial) }
    var draft by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val leave = { if (!busy) { if (draft != baseline) confirmLeave = true else onDismiss() } }
    fun perform(action: suspend () -> Unit) {
        busy = true; failed = false
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = leave, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BackHandler { leave() }
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.nps_rules), style = MaterialTheme.typography.headlineSmall)
            ProductFilledField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 420.dp),
                enabled = !busy, isError = failed)
            if (failed) Text(stringResource(R.string.nps_error), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ confirmReset = true }, enabled = !busy) { Text(stringResource(R.string.nps_restore_default)) }
                Button({ perform { save(draft); baseline = draft; onDismiss() } }, enabled = !busy && draft.isNotBlank()) { Text(stringResource(R.string.nps_save)) }
            }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, text = { Text(stringResource(R.string.nps_unsaved)) },
        confirmButton = { TextButton({ confirmLeave = false; onDismiss() }) { Text(stringResource(R.string.nps_discard)) } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text(stringResource(R.string.nps_continue_editing)) } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, text = { Text(stringResource(R.string.nps_restore_default_confirm)) },
        confirmButton = { TextButton({ confirmReset = false; perform { baseline = reset(); draft = baseline } }) { Text(stringResource(R.string.nps_restore_default)) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.nps_cancel)) } })
}

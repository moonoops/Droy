package io.droyagent.app.nativeui.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.droyagent.app.NativeToolsFileHost

@Composable
internal fun NativeToolsFileActions(model: NativeToolsViewModel) {
    val context = LocalContext.current
    val action by model.fileActions.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.saveCopy(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(action?.id) {
        val pending = action ?: return@LaunchedEffect
        model.consumeFileAction(pending.id, pending.saving)
        try {
            if (pending.saving) save.launch(NativeToolsFileHost.saveIntent(context, pending.file, pending.mediaType))
            else open.launch(NativeToolsFileHost.openIntent(context, pending.file, pending.mediaType))
        } catch (_: ActivityNotFoundException) {
            model.cancelSaveCopy(); model.report("native_file_no_viewer")
        } catch (_: Exception) {
            model.cancelSaveCopy(); model.report("native_file_copy_failed")
        }
    }
}

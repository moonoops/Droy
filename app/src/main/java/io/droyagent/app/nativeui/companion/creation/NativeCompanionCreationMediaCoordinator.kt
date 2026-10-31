package io.droyagent.app.nativeui.companion.creation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Each persisted selection keeps its key and owner across Activity recreation. */
class NativeCompanionCreationMediaCoordinator(private val activity: ComponentActivity, private val model: NativeCompanionCreationViewModel) {
    private val pickers = mutableMapOf<String, ActivityResultLauncher<Intent>>()
    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { pickers.values.forEach { it.unregister() }; pickers.clear() }
        })
        activity.lifecycleScope.launch { model.pendingPicker()?.let { register(it.id) } }
    }
    private fun register(id: String): ActivityResultLauncher<Intent> = pickers.getOrPut(id) {
        activity.activityResultRegistry.register("droy-native-companion-creation-image-v1:$id", ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uri = data?.data
            if (result.resultCode != Activity.RESULT_OK || uri == null) model.imagePicked(id, null)
            else {
                if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                    try { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { /* Import still checks the current read grant; recovery checks it again. */ }
                }
                model.imagePicked(id, uri.toString())
            }
            activity.lifecycleScope.launch { yield(); pickers.remove(id)?.unregister() }
        }
    }
    fun chooseImage(kind: NativeCreationImageKind) {
        activity.lifecycleScope.launch {
            val operation = model.prepareImage(kind) ?: return@launch
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || activity.isFinishing) {
                model.cancelPreparedImage(operation.id); return@launch
            }
            try {
                register(operation.id).launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) { model.pickerFailed(operation.id) }
            catch (_: SecurityException) { model.pickerFailed(operation.id) }
        }
    }
    companion object {
        @JvmStatic fun isPickerOpen() = NativeCompanionCreationViewModel.isPickerOpen()
        @JvmStatic fun hasRuntimeWork() = NativeCompanionCreationViewModel.hasRuntimeWork()
    }
}

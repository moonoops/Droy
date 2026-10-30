package io.droyagent.app.nativeui.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionVoice
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.NativeCameraInputHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Register from onCreate before STARTED, using the same key after Activity recreation. */
class NativeMediaCoordinator(
    private val activity: ComponentActivity,
    private val companions: NativeCompanionViewModel,
    onWorkChanged: () -> Unit = {},
    private val workbench: NativeWorkbenchViewModel? = null,
) : DefaultLifecycleObserver {
    val mediaModel: NativeMediaViewModel = ViewModelProvider(activity)[NativeMediaViewModel::class.java]
    val state = mediaModel.state
    private val avatarCallbacks = mutableMapOf<String, (String) -> Unit>()
    private val backgroundCallbacks = mutableMapOf<String, (NativeAvatarResult) -> Unit>()
    private val picker = activity.activityResultRegistry.register(
        "droy-native-companion-media-v1", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) mediaModel.selected(null)
        else {
            val data = result.data
            val uris = linkedSetOf<android.net.Uri>()
            data?.clipData?.let { clips -> for (index in 0 until clips.itemCount) clips.getItemAt(index).uri?.let(uris::add) }
            data?.data?.let(uris::add)
            if (uris.isEmpty()) {
                mediaModel.selected(null); mediaModel.report(NativeMediaError.READ)
            } else {
                if (data != null && data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                    uris.forEach { uri ->
                        try { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        catch (_: SecurityException) { /* Immediate read may remain granted; a later retry validates it again. */ }
                    }
                }
                mediaModel.selected(uris.toList())
            }
        }
    }
    private val camera = activity.activityResultRegistry.register(
        "droy-native-camera-input-v1", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result -> mediaModel.selectedCamera(result.resultCode == Activity.RESULT_OK) }

    init {
        mediaModel.bind(companions, onWorkChanged, workbench)
        activity.lifecycle.addObserver(this)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    state.collect { snapshot ->
                        snapshot.avatarResults.values.forEach { result -> avatarCallbacks.remove(result.id)?.invoke(result.url) }
                        snapshot.backgroundResults.values.forEach { result -> backgroundCallbacks.remove(result.id)?.invoke(result) }
                    }
                }
                launch {
                    mediaModel.openRequests.collect { request ->
                        if (request != null) {
                            val failure = try {
                                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.media-input-files", request.file)
                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, request.mediaType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.clipData = ClipData.newRawUri(request.file.name, uri)
                                activity.startActivity(Intent.createChooser(intent, null))
                                null
                            } catch (_: ActivityNotFoundException) { NativeMediaError.NO_VIEWER }
                            catch (_: SecurityException) { NativeMediaError.OPEN }
                            catch (_: IllegalArgumentException) { NativeMediaError.OPEN }
                            mediaModel.opened(request.id, failure)
                        }
                    }
                }
            }
        }
    }

    fun chooseAttachments(companionId: String, imagesOnly: Boolean = false) {
        val current = companions.state.value
        val draft = current.draftsByCompanion[companionId] ?: current.draft.takeIf { current.selectedCompanionId == companionId }
        val remaining = 10 - (draft?.attachments?.size ?: 0) - (draft?.localAttachments?.size ?: 0)
        activity.lifecycleScope.launch {
            mediaModel.begin(companionId, NativeMediaKind.ATTACHMENTS, remaining, imagesOnly)?.let(::launch)
        }
    }

    fun chooseAvatar(targetId: String?, onSelectedUrl: (String) -> Unit) {
        activity.lifecycleScope.launch {
            mediaModel.begin(targetId, NativeMediaKind.AVATAR, 1, true)?.let { operation ->
                avatarCallbacks[operation.id] = onSelectedUrl
                launch(operation)
            }
        }
    }

    fun chooseBackground(targetId: String?, onSelected: (NativeAvatarResult) -> Unit) {
        if (targetId == null) { mediaModel.report(NativeMediaError.OWNER); return }
        activity.lifecycleScope.launch {
            mediaModel.begin(targetId, NativeMediaKind.BACKGROUND, 1, true)?.let { operation ->
                backgroundCallbacks[operation.id] = onSelected
                launch(operation)
            }
        }
    }

    fun chooseWorkbenchAttachments(imagesOnly: Boolean = false) {
        val model = workbench ?: run { mediaModel.report(NativeMediaError.OWNER); return }
        if (model.state.value.readOnly) { mediaModel.report(NativeMediaError.OWNER); return }
        val target = model.mediaTargetOrNull() ?: return
        val remaining = 10 - model.state.value.draftAttachments.size
        activity.lifecycleScope.launch {
            mediaModel.begin(target.draftId, NativeMediaKind.WORKBENCH_DRAFT, remaining, imagesOnly, target)?.let(::launch)
        }
    }

    fun chooseCompanionCamera(companionId: String) {
        val current = companions.state.value
        val draft = current.draftsByCompanion[companionId] ?: current.draft.takeIf { current.selectedCompanionId == companionId }
        val remaining = 10 - (draft?.attachments?.size ?: 0) - (draft?.localAttachments?.size ?: 0)
        activity.lifecycleScope.launch {
            mediaModel.begin(companionId, NativeMediaKind.ATTACHMENTS, remaining, true, camera = true)?.let(::launch)
        }
    }

    fun chooseWorkbenchCamera() {
        val model = workbench ?: run { mediaModel.report(NativeMediaError.OWNER); return }
        if (model.state.value.readOnly) { mediaModel.report(NativeMediaError.OWNER); return }
        val target = model.mediaTargetOrNull() ?: return
        val remaining = 10 - model.state.value.draftAttachments.size
        activity.lifecycleScope.launch {
            mediaModel.begin(target.draftId, NativeMediaKind.WORKBENCH_DRAFT, remaining, true, target, camera = true)?.let(::launch)
        }
    }

    fun retryPending() { activity.lifecycleScope.launch { mediaModel.retry()?.let(::launch) } }
    fun cancelPending() = mediaModel.cancelPending()
    fun dismissError() = mediaModel.dismissError()
    fun dismissCameraAlbumResult() = mediaModel.dismissCameraAlbumResult()
    fun consumeAvatar(ownerId: String?, resultId: String) = mediaModel.consumeAvatar(ownerId, resultId)
    fun consumeBackground(ownerId: String?, resultId: String) = mediaModel.consumeBackground(ownerId, resultId)
    fun openAttachment(companionId: String, attachment: NativeCompanionAttachment) = mediaModel.openAttachment(companionId, attachment)
    fun playVoice(companionId: String, voice: NativeCompanionVoice) = mediaModel.playVoice(companionId, voice)
    fun stopPlayback() = mediaModel.stopPlayback()
    fun onConversationChanged(companionId: String?) = mediaModel.onConversationChanged(companionId)

    private fun launch(operation: NativeMediaSelection) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || mediaModel.pendingSelection()?.id != operation.id) {
            if (operation.camera) mediaModel.cameraLaunchFailed(operation.id, null, operation.captureId) else mediaModel.pickerFailed(operation.id)
            return
        }
        if (operation.camera) { launchCamera(operation); return }
        try {
            var intent: Intent? = null
            if (operation.imagesOnly && Build.VERSION.SDK_INT >= 33) {
                intent = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                if (operation.maximum > 1) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(operation.maximum, MediaStore.getPickImagesMaxLimit()))
                if (intent.resolveActivity(activity.packageManager) == null) intent = null
            }
            val request = intent ?: Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (operation.imagesOnly) "image/*" else "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, operation.maximum > 1)
            request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            mediaModel.pickerLaunched(operation.id)
            picker.launch(request)
        } catch (_: ActivityNotFoundException) { mediaModel.pickerFailed(operation.id) }
        catch (_: SecurityException) { mediaModel.pickerFailed(operation.id) }
        catch (_: IllegalStateException) { mediaModel.pickerFailed(operation.id) }
    }

    private fun launchCamera(operation: NativeMediaSelection) {
        activity.lifecycleScope.launch {
            try {
                val captureId = operation.captureId ?: throw IllegalArgumentException()
                val file = withContext(Dispatchers.IO) { NativeCameraInputHost.prepareOutput(activity, captureId) }
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || mediaModel.pendingSelection()?.let {
                        it.id == operation.id && it.captureId == captureId && !it.discardResult
                    } != true) {
                    withContext(Dispatchers.IO) { NativeCameraInputHost.discardOutput(activity, captureId) }
                    mediaModel.cameraLaunchFailed(operation.id, null, captureId); return@launch
                }
                NativeCameraInputHost.checkStart(activity)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.media-input-files", file)
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("photo", uri)
                mediaModel.pickerLaunched(operation.id)
                camera.launch(intent)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) {
                    // A retained target remains interrupted; recreation must never launch the camera by itself.
                    mediaModel.cameraLaunchFailed(operation.id, null, operation.captureId); throw error
                }
                mediaModel.cameraLaunchFailed(operation.id, (error as? NativeCameraInputHost.Failure)?.code, operation.captureId)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) { mediaModel.stopPlayback() }
    override fun onDestroy(owner: LifecycleOwner) { avatarCallbacks.clear(); backgroundCallbacks.clear() }
}

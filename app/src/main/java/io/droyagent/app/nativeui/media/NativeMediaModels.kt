package io.droyagent.app.nativeui.media

import androidx.annotation.StringRes
import io.droyagent.app.R
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

enum class NativeMediaError(@param:StringRes val messageRes: Int) {
    BUSY(R.string.native_media_busy), PICKER(R.string.native_media_picker_failed),
    TOO_MANY(R.string.native_media_too_many), TOO_LARGE(R.string.native_media_too_large),
    READ(R.string.native_media_read_failed), UPLOAD(R.string.native_media_upload_failed),
    OWNER(R.string.native_media_owner_unavailable), OPEN(R.string.native_media_open_failed),
    NO_VIEWER(R.string.native_media_no_viewer), PLAYBACK(R.string.native_media_playback_failed),
    INTERRUPTED(R.string.native_media_interrupted), STORAGE(R.string.native_media_storage_failed),
    UNMATCHED_RESULT(R.string.native_media_unmatched_result),
    RUNTIME(R.string.native_engine_unavailable),
    CAMERA_PERMISSION(R.string.native_voice_input_camera_permission), CAMERA(R.string.native_voice_input_camera_failed),
    PAUSED(R.string.native_media_paused),
}

enum class NativeMediaKind {
    ATTACHMENTS, AVATAR, BACKGROUND, WORKBENCH_DRAFT;
    val profileImage: Boolean get() = this == AVATAR || this == BACKGROUND
}
enum class NativeMediaPhase { PICKING, UPLOADING, IMPORTING, INTERRUPTED }

data class NativeAvatarResult(val id: String, val ownerId: String?, val url: String, val attachmentId: String)
data class NativeMediaPlayback(val ownerId: String, val attachmentId: String, val preparing: Boolean)
data class NativeCameraAlbumResult(val operationId: String, val captureId: String, val saved: Boolean, val failed: Boolean) {
    internal fun json(): JSONObject = JSONObject().put("operationId", operationId).put("captureId", captureId).put("saved", saved).put("failed", failed)
    companion object {
        internal fun parse(value: JSONObject) = NativeCameraAlbumResult(io.droyagent.app.media.MediaInputPolicy.id(value.getString("operationId")),
            io.droyagent.app.media.MediaInputPolicy.id(value.getString("captureId")), value.getBoolean("saved"), value.getBoolean("failed")).also {
                require(!it.saved || !it.failed)
            }
    }
}
data class NativeMediaState(
    val phase: NativeMediaPhase? = null, val ownerId: String? = null,
    val error: NativeMediaError? = null, val canRetry: Boolean = false,
    val avatarResults: Map<String, NativeAvatarResult> = emptyMap(),
    val backgroundResults: Map<String, NativeAvatarResult> = emptyMap(),
    val playback: NativeMediaPlayback? = null,
    val opening: Boolean = false,
    val kind: NativeMediaKind? = null,
    val camera: Boolean = false, val cameraAlbumResult: NativeCameraAlbumResult? = null,
) {
    val busy: Boolean get() = phase in setOf(NativeMediaPhase.PICKING, NativeMediaPhase.UPLOADING, NativeMediaPhase.IMPORTING) || opening
    fun avatarFor(ownerId: String?): NativeAvatarResult? = avatarResults[NativeMediaPolicy.ownerKey(ownerId)]
    fun backgroundFor(ownerId: String?): NativeAvatarResult? = backgroundResults[NativeMediaPolicy.ownerKey(ownerId)]
}

internal data class NativeMediaReceipt(
    val index: Int, val id: String, val name: String, val mediaType: String, val bytes: Long, val url: String,
    val applied: Boolean = false,
)
internal data class NativeMediaSelection(
    val id: String, val ownerId: String?, val kind: NativeMediaKind,
    val maximum: Int, val imagesOnly: Boolean, val phase: NativeMediaPhase = NativeMediaPhase.PICKING,
    val uris: List<String> = emptyList(), val receipts: List<NativeMediaReceipt> = emptyList(),
    val discardResult: Boolean = false,
    val launched: Boolean = false,
    val workTarget: NativeWorkbenchMediaTarget? = null,
    val camera: Boolean = false, val captureId: String? = null, val cameraReady: Boolean = false,
) {
    val needsPicker: Boolean get() = if (camera) !cameraReady else uris.isEmpty()
    fun recoverForActivity(hasSavedActivityState: Boolean): NativeMediaSelection =
        if (phase in setOf(NativeMediaPhase.UPLOADING, NativeMediaPhase.IMPORTING) || phase == NativeMediaPhase.PICKING && (!hasSavedActivityState || !launched))
            copy(phase = NativeMediaPhase.INTERRUPTED) else this

    fun json(): JSONObject = JSONObject().put("id", id).put("ownerId", ownerId ?: JSONObject.NULL)
        .put("kind", kind.name).put("maximum", maximum).put("imagesOnly", imagesOnly).put("phase", phase.name)
        .put("uris", JSONArray(uris)).put("discardResult", discardResult).put("launched", launched)
        .put("camera", camera).put("captureId", captureId ?: JSONObject.NULL).put("cameraReady", cameraReady)
        .put("workTarget", workTarget?.let { JSONObject().put("draftId", it.draftId).put("sessionId", it.sessionId ?: JSONObject.NULL).put("workspaceId", it.workspaceId ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("receipts", JSONArray(receipts.map {
            JSONObject().put("index", it.index).put("id", it.id).put("name", it.name).put("mediaType", it.mediaType).put("bytes", it.bytes).put("url", it.url).put("applied", it.applied)
        }))
    companion object {
        fun restore(value: JSONObject): NativeMediaSelection {
            val kind = NativeMediaKind.valueOf(value.getString("kind"))
            val owner = if (value.isNull("ownerId")) null else value.getString("ownerId")
            require(kind == NativeMediaKind.AVATAR || owner != null)
            val maximum = value.getInt("maximum"); require(maximum in 1..10)
            val camera = value.optBoolean("camera")
            val captureId = if (value.isNull("captureId")) null else io.droyagent.app.media.MediaInputPolicy.id(value.getString("captureId"))
            val cameraReady = value.optBoolean("cameraReady")
            require(camera == (captureId != null) && (!cameraReady || camera))
            require(!camera || !kind.profileImage && value.getBoolean("imagesOnly"))
            val uris = value.getJSONArray("uris").let { items -> (0 until items.length()).map(items::getString) }
            require(uris.size <= maximum && uris.all(NativeMediaPolicy::contentUri))
            require(!camera || uris.isEmpty())
            val receipts = value.getJSONArray("receipts").let { items -> (0 until items.length()).map { index -> items.getJSONObject(index).let {
                    NativeMediaReceipt(it.getInt("index"), it.getString("id"), it.getString("name"), it.getString("mediaType"), it.getLong("bytes"), it.getString("url"), it.optBoolean("applied"))
                } } }
            require(receipts.map { it.index }.distinct().size == receipts.size && receipts.all {
                it.index in uris.indices && it.bytes in 1..NativeMediaPolicy.MAX_BYTES.toLong() && NativeMediaPolicy.ownedUrl(owner, it.id, it.url)
            })
            require(!kind.profileImage || maximum == 1 && value.getBoolean("imagesOnly"))
            val target = value.optJSONObject("workTarget")?.let {
                NativeWorkbenchMediaTarget(it.getString("draftId"), if (it.isNull("sessionId")) null else it.getString("sessionId"),
                    if (it.isNull("workspaceId")) null else it.getString("workspaceId"))
            }
            require((kind == NativeMediaKind.WORKBENCH_DRAFT) == (target != null))
            require(target == null || target.draftId == owner && receipts.isEmpty())
            val id = value.getString("id"); require(id.length in 1..160)
            return NativeMediaSelection(id, owner, kind, maximum, value.getBoolean("imagesOnly"),
                NativeMediaPhase.valueOf(value.getString("phase")), uris, receipts, value.optBoolean("discardResult"), value.optBoolean("launched"), target,
                camera, captureId, cameraReady)
        }
    }
}

internal class NativeMediaProblem(val problem: NativeMediaError, cause: Throwable? = null) : Exception(problem.name, cause)

internal object NativeMediaPolicy {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val CACHE_BYTES = 80L * 1024 * 1024
    fun ownerKey(owner: String?) = owner?.let { "companion:$it" } ?: "user-profile"
    fun contentUri(value: String): Boolean = value.length <= 8192 && io.droyagent.app.media.MediaInputPolicy.contentUri(value)
    fun ownedUrl(owner: String?, attachmentId: String, value: String): Boolean {
        fun encode(part: String) = URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return value == "/droy/companions/${owner?.let(::encode) ?: "user-profile"}/attachments/${encode(attachmentId)}"
    }
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > MAX_BYTES) throw NativeMediaProblem(NativeMediaError.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        if (total == 0) throw NativeMediaProblem(NativeMediaError.READ)
        return output.toByteArray()
    }
}

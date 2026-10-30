package io.droyagent.app.nativeui.media

import android.content.Context
import android.net.Uri
import io.droyagent.app.media.MediaInputPolicy
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.data.NativeApiException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NativeLocalAttachment(val importId: String, val id: String, val name: String, val mediaType: String, val bytes: Long) {
    val image: Boolean get() = mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    fun json(): JSONObject = JSONObject().put("importId", importId).put("id", id).put("name", name).put("mediaType", mediaType).put("bytes", bytes)
    companion object {
        fun parse(value: JSONObject): NativeLocalAttachment {
            val item = NativeLocalAttachment(MediaInputPolicy.id(value.getString("importId")), MediaInputPolicy.id(value.getString("id")),
                value.getString("name"), value.getString("mediaType"), value.getLong("bytes"))
            require(item.name == MediaInputPolicy.filename(item.name) && item.mediaType == MediaInputPolicy.mediaType(item.mediaType))
            require(item.bytes in 1..NativeMediaPolicy.MAX_BYTES.toLong())
            return item
        }
        fun parseList(values: JSONArray): List<NativeLocalAttachment> = (0 until values.length()).map { parse(values.getJSONObject(it)) }.also {
            require(it.size <= 10 && it.map(NativeLocalAttachment::id).distinct().size == it.size)
        }
    }
}

data class NativeWorkbenchMediaTarget(val draftId: String, val sessionId: String?, val workspaceId: String?) {
    init {
        listOfNotNull(draftId, sessionId, workspaceId).forEach { value ->
            require(value.isNotBlank() && value.length <= 1024 && value == value.trim() && value.none(Char::isISOControl))
        }
    }
}
data class NativeImportedShare(val id: String, val text: String, val attachments: List<NativeLocalAttachment>, val error: String? = null)
internal data class NativeImportedCamera(val attachments: List<NativeLocalAttachment>, val albumSaved: Boolean, val albumFailed: Boolean, val error: NativeMediaError?)

/** Uses the existing importer in a namespace that the legacy WebView cannot adopt. */
class NativeDraftMediaStore(context: Context) {
    private val app = context.applicationContext
    private val store = MediaInputStore.nativeDrafts(context)

    suspend fun importSelection(id: String, uris: List<Uri>, imagesOnly: Boolean, remaining: Int): List<NativeLocalAttachment> = withContext(Dispatchers.IO) {
        val manifest = store.importUrisForOperation(id, "native-picker", null, uris, "", imagesOnly, remaining)
        if (manifest.optString("source") != "native-picker" || manifest.optString("consumer") != "work" || manifest.has("targetSessionId"))
            throw NativeApiException("native_attachment_changed")
        try { attachments(manifest) }
        catch (error: Exception) { removeImport(id); throw error }
    }

    internal suspend fun importCamera(captureId: String, remaining: Int): NativeImportedCamera = withContext(Dispatchers.IO) {
        val output = io.droyagent.app.NativeCameraInputHost.completedOutput(app, captureId)
        val manifest = store.importNativeCapture(output, remaining, app.applicationInfo.loadLabel(app.packageManager).toString())
        val saved = manifest.optBoolean("albumSaved")
        val failed = manifest.optString("albumError").isNotBlank()
        try {
            val items = attachments(manifest)
            NativeImportedCamera(items, saved, failed, if (manifest.optString("error").isNotBlank() || items.isEmpty()) NativeMediaError.READ else null)
        } catch (error: NativeMediaProblem) { NativeImportedCamera(emptyList(), saved, failed, error.problem) }
        catch (_: IllegalArgumentException) { NativeImportedCamera(emptyList(), saved, failed, NativeMediaError.READ) }
    }

    suspend fun share(id: String): NativeImportedShare = withContext(Dispatchers.IO) {
        MediaInputPolicy.id(id)
        val manifest = store.list().let { rows -> (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("id") == id } }
            ?: throw NativeApiException("native_share_missing")
        NativeShareInbox.requireAdmittedShare(manifest)
        NativeImportedShare(id, MediaInputPolicy.text(manifest.optString("text")), attachments(manifest), manifest.optString("error").takeIf(String::isNotBlank))
    }

    fun open(item: NativeLocalAttachment): InputStream = store.openItem(item.importId, item.id, "work")
    suspend fun bytes(item: NativeLocalAttachment): ByteArray = withContext(Dispatchers.IO) {
        open(item).use(NativeMediaPolicy::readBounded).also { if (it.size.toLong() != item.bytes) throw NativeApiException("native_attachment_changed") }
    }
    suspend fun removeImport(id: String) = withContext(Dispatchers.IO) {
        NativeMediaImportRetention.retire(app, id) {
            val rows = store.list()
            if ((0 until rows.length()).any { rows.getJSONObject(it).getString("id") == id }) store.complete(id, "work")
        }
    }
    suspend fun removeShareImport(id: String) = withContext(Dispatchers.IO) {
        MediaInputPolicy.id(id)
        val rows = store.list()
        val manifest = (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("id") == id } ?: return@withContext
        NativeShareInbox.requireAdmittedShare(manifest)
        store.complete(id, "work")
    }

    private fun attachments(manifest: JSONObject): List<NativeLocalAttachment> {
        val values = manifest.getJSONArray("items")
        if (values.length() > 10) throw NativeMediaProblem(NativeMediaError.TOO_MANY)
        if ((0 until values.length()).any { values.getJSONObject(it).getLong("bytes") > NativeMediaPolicy.MAX_BYTES })
            throw NativeMediaProblem(NativeMediaError.TOO_LARGE)
        return NativeLocalAttachment.parseList(JSONArray((0 until values.length()).map { index ->
            JSONObject(values.getJSONObject(index).toString()).put("importId", manifest.getString("id"))
        }))
    }
}

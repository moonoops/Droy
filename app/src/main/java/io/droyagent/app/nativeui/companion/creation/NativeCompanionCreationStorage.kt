package io.droyagent.app.nativeui.companion.creation

import android.content.Context
import android.net.Uri
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream

internal class NativeCreationPreferences(context: Context) : NativeCreationJournalStore {
    private val preferences = context.getSharedPreferences("native_companion_creation", Context.MODE_PRIVATE)
    override suspend fun read(): NativeCreationJournal? = withContext(Dispatchers.IO) {
        preferences.getString("journal", null)?.let(NativeCreationJson::journal)
    }
    override suspend fun write(value: NativeCreationJournal?) = withContext(Dispatchers.IO) {
        val editor = preferences.edit()
        if (value == null) editor.remove("journal") else editor.putString("journal", NativeCreationJson.journal(value))
        if (!editor.commit()) throw NativeApiException("storage_error")
    }
}

/** Creation imports cannot be adopted by a work conversation or a saved profile. */
internal class NativeCreationFileStore(context: Context) : NativeCreationImageStore {
    private val store = MediaInputStore.nativeCompanionCreation(context)
    override suspend fun importImage(operationId: String, uri: String): NativeLocalAttachment = withContext(Dispatchers.IO) {
        val manifest = store.importUrisForOperation(operationId, "native-companion-creation", null, listOf(Uri.parse(uri)), "", true, 1)
        try {
            if (manifest.getString("id") != operationId || manifest.getString("source") != "native-companion-creation"
                || manifest.optString("consumer", "work") != "work" || manifest.has("targetSessionId") || manifest.getJSONArray("items").length() != 1)
                throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
            val item = manifest.getJSONArray("items").getJSONObject(0)
            NativeLocalAttachment.parse(JSONObject(item.toString()).put("importId", operationId)).also {
                if (!it.image) throw NativeApiException("COMPANION_IMAGE_REQUIRED")
            }
        } catch (error: Exception) { store.complete(operationId, "work"); throw error }
    }
    fun open(item: NativeLocalAttachment): InputStream = store.openItem(item.importId, item.id, "work")
    override suspend fun remove(importId: String) = withContext(Dispatchers.IO) {
        val rows = store.list()
        if ((0 until rows.length()).any { rows.getJSONObject(it).getString("id") == importId }) store.complete(importId, "work")
        Unit
    }
}

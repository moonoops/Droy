package io.droyagent.app.nativeui.media

import android.content.Context
import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Deleting an import must wait for the existing media journal's retirement commit, including failed commits. */
internal class NativeMediaRetirementGate {
    private val lock = Mutex()
    private var uncertain = false
    suspend fun write(commit: suspend () -> Boolean) = lock.withLock {
        uncertain = true
        if (!commit()) throw NativeApiException("storage_error")
        uncertain = false
    }
    suspend fun retire(importId: String, journal: () -> String?, remove: () -> Unit): Boolean = lock.withLock {
        if (uncertain) throw NativeApiException("storage_error")
        if (references(journal(), importId)) return@withLock false
        remove(); true
    }
    companion object {
        fun references(raw: String?, importId: String): Boolean {
            if (raw == null) return false
            try {
                val root = JSONObject(raw)
                require(root.has("selection"))
                if (root.isNull("selection")) return false
                val operation = NativeMediaSelection.restore(root.getJSONObject("selection"))
                return !operation.kind.profileImage && (operation.captureId ?: operation.id) == importId
            } catch (error: Exception) { throw NativeApiException("storage_error", error) }
        }
    }
}

internal object NativeMediaImportRetention {
    private const val KEY = "native-media-v1"
    private val gate = NativeMediaRetirementGate()
    suspend fun commit(context: Context, value: String) = gate.write {
        withContext(Dispatchers.IO) { context.getSharedPreferences("native_media", 0).edit().putString(KEY, value).commit() }
    }
    suspend fun retire(context: Context, importId: String, remove: () -> Unit) = gate.retire(importId,
        { context.getSharedPreferences("native_media", 0).getString(KEY, null) }, remove)
}

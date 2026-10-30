package io.droyagent.app.nativeui.media

import android.content.Context
import io.droyagent.app.media.MediaInputPolicy
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray

/** Durable FIFO contains only admitted, unbound shares. It is not a send queue. */
object NativeShareInbox {
    private val lock = Any()
    private fun preferences(context: Context) = context.getSharedPreferences("native_share_inbox", Context.MODE_PRIVATE)
    private fun identifier(id: String) {
        try { MediaInputPolicy.id(id) } catch (invalid: IllegalArgumentException) { throw NativeApiException("native_share_scope", invalid) }
    }
    private fun read(context: Context): List<String> {
        return parsePending(preferences(context).getString("ids", "[]") ?: throw NativeApiException("storage_error"))
    }
    internal fun parsePending(raw: String): List<String> = try {
        val values = JSONArray(raw)
        (0 until values.length()).map(values::getString).also { ids ->
            if (ids.size > MediaInputPolicy.MAX_PENDING_IMPORTS || ids.distinct().size != ids.size) throw NativeApiException("storage_error")
            ids.forEach { MediaInputPolicy.id(it) }
        }
    } catch (error: org.json.JSONException) { throw NativeApiException("storage_error", error) }
    catch (error: IllegalArgumentException) { throw NativeApiException("storage_error", error) }
    internal fun appended(pending: List<String>, id: String): List<String> {
        identifier(id)
        if (id in pending) return pending
        if (pending.size >= MediaInputPolicy.MAX_PENDING_IMPORTS) throw NativeApiException("native_share_inbox_full")
        return pending + id
    }
    internal fun requireAdmittedShare(manifest: org.json.JSONObject) {
        if (manifest.optString("source") != "share" || manifest.optString("consumer") != "work" || manifest.has("targetSessionId"))
            throw NativeApiException("native_share_scope")
    }
    private fun write(context: Context, ids: List<String>) {
        if (!preferences(context).edit().putString("ids", JSONArray(ids).toString()).commit()) throw NativeApiException("storage_error")
    }
    @JvmStatic fun enqueue(context: Context, id: String) = synchronized(lock) {
        identifier(id)
        val store = MediaInputStore.nativeDrafts(context)
        val rows = store.list()
        val manifest = (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("id") == id }
            ?: throw NativeApiException("native_share_missing")
        requireAdmittedShare(manifest)
        val pending = read(context)
        val next = appended(pending, id)
        if (next != pending) write(context, next)
    }
    @JvmStatic fun pending(context: Context): List<String> = synchronized(lock) { read(context) }
    /** Acknowledgement never deletes a private file; the active preview owns its local disposition proof. */
    @JvmStatic fun acknowledge(context: Context, id: String) = synchronized(lock) {
        identifier(id)
        val before = read(context)
        if (id in before) write(context, before.filterNot { it == id })
    }
}

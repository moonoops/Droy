package io.droyagent.app.nativeui.engine

import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiException
import java.util.UUID
import org.json.JSONObject

/** Nonsecret draft choices and immutable captures, separate from engine defaults and Android authorization. */
internal class NativeEngineDraftStore(private val preferences: SharedPreferences) {
    data class Capture(val selection: NativeEngineDraftSelection, val sessionId: String?, val prepared: Boolean)
    private var uncertain = false
    @Synchronized fun selection(draftId: String): NativeEngineDraftSelection? {
        if (uncertain) throw NativeApiException("NATIVE_ENGINE_DRAFT_STORAGE")
        return root().getJSONObject("drafts").optJSONObject(identifier(draftId))?.let(::decode)
    }
    @Synchronized fun stage(draftId: String, agent: String? = null, permission: String? = null, acceptRisk: Boolean = false): NativeEngineDraftSelection {
        val root = root(); val drafts = root.getJSONObject("drafts")
        val old = drafts.optJSONObject(identifier(draftId))?.let(::decode)
        val value = NativeEngineDraftSelection(draftId, UUID.randomUUID().toString(), agent ?: old?.agentPresetId,
            permission ?: old?.permissionPresetId, if (permission != null) acceptRisk else old?.permissionRiskAccepted ?: false)
        drafts.put(draftId, encode(value)); commit(root); return value
    }
    @Synchronized fun capture(draftId: String): JSONObject? {
        if (uncertain) throw NativeApiException("NATIVE_ENGINE_DRAFT_STORAGE")
        val root = root(); val value = root.getJSONObject("drafts").optJSONObject(identifier(draftId))?.let(::decode) ?: return null
        if (value.draftId != draftId) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
        val captures = root.getJSONObject("captures")
        val previous = captures.optJSONObject(value.nonce)
        if (previous == null) captures.put(value.nonce, JSONObject().put("selection", encode(value)).put("prepared", false))
        else if (decode(previous.getJSONObject("selection")) != value) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
        commit(root)
        return encode(value)
    }
    @Synchronized fun bind(value: NativeEngineDraftSelection, sessionId: String): Capture {
        identifier(sessionId)
        val root = root(); val record = root.getJSONObject("captures").optJSONObject(value.nonce) ?: throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
        if (decode(record.getJSONObject("selection")) != value) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
        val target = if (record.has("sessionId")) record.getString("sessionId") else null
        if (target != null && target != sessionId) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
        if (target == null) record.put("sessionId", sessionId)
        commit(root)
        return Capture(value, sessionId, record.getBoolean("prepared"))
    }
    @Synchronized fun prepared(value: NativeEngineDraftSelection, sessionId: String) {
        val root = root(); val record = root.getJSONObject("captures").getJSONObject(value.nonce)
        if (decode(record.getJSONObject("selection")) != value || record.optString("sessionId") != sessionId) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
        val drafts = root.getJSONObject("drafts")
        if (drafts.optJSONObject(value.draftId)?.let(::decode)?.nonce == value.nonce) drafts.remove(value.draftId)
        record.put("prepared", true); commit(root)
    }
    @Synchronized fun discard(draftId: String) {
        val root = root(); root.getJSONObject("drafts").remove(identifier(draftId)); commit(root)
    }
    private fun root(): JSONObject {
        try {
            val text = preferences.getString("draft-selections-v1", null)
            val root = if (text == null) JSONObject().put("version", 1).put("drafts", JSONObject()).put("captures", JSONObject()) else JSONObject(text)
            require(root.opt("version") == 1 && root.optJSONObject("drafts") != null && root.optJSONObject("captures") != null)
            return root
        } catch (_: Exception) { throw NativeApiException("NATIVE_ENGINE_DRAFT_STORAGE") }
    }
    private fun commit(root: JSONObject) {
        if (!preferences.edit().putString("draft-selections-v1", root.toString()).commit()) { uncertain = true; throw NativeApiException("NATIVE_ENGINE_DRAFT_STORAGE") }
        uncertain = false
    }
    companion object {
        private fun identifier(value: String): String {
            require(value.isNotBlank() && value.length <= 1024 && value == value.trim() && value.none { it.code < 32 || it.code == 127 })
            return value
        }
        fun encode(value: NativeEngineDraftSelection): JSONObject = JSONObject().put("version", 1).put("draftId", value.draftId).put("nonce", value.nonce)
            .put("permissionRiskAccepted", value.permissionRiskAccepted).apply {
                value.agentPresetId?.let { put("agentPresetId", it) }; value.permissionPresetId?.let { put("permissionPresetId", it) }
            }
        fun decode(value: JSONObject): NativeEngineDraftSelection {
            try {
                require(value.keys().asSequence().all { it in setOf("version", "draftId", "nonce", "agentPresetId", "permissionPresetId", "permissionRiskAccepted") })
                require(value.opt("version") == 1 && value.opt("permissionRiskAccepted") is Boolean)
                val agent = if (value.has("agentPresetId")) identifier(value.getString("agentPresetId")) else null
                val permission = if (value.has("permissionPresetId")) identifier(value.getString("permissionPresetId")) else null
                require(agent != null || permission != null)
                return NativeEngineDraftSelection(identifier(value.getString("draftId")), identifier(value.getString("nonce")), agent, permission, value.getBoolean("permissionRiskAccepted"))
            } catch (_: Exception) { throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED") }
        }
    }
}

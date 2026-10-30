package io.droyagent.app.nativeui.permissions

import android.content.SharedPreferences
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Product-only policy and request receipts; actual Android grants are always read from the system. */
class NativePermissionPolicyStore(private val preferences: SharedPreferences) {
    fun selected(): NativeAuthorizationPolicy? = NativeAuthorizationPolicy.fromId(preferences.getString(POLICY, null))

    fun select(policy: NativeAuthorizationPolicy) {
        synchronized(lock) { commit(preferences.edit().putString(POLICY, policy.id)) }
    }

    fun records(): Map<String, NativePermissionRequestRecord> = synchronized(lock) {
        val values = JSONObject(preferences.getString(RECORDS, "{}")!!)
        values.keys().asSequence().associateWith { id ->
            val value = values.getJSONObject(id)
            NativePermissionRequestRecord(value.getLong("firstRequestedAt"), value.getLong("updatedAt"),
                NativePermissionOutcome.fromId(value.getString("outcome")) ?: throw IllegalStateException("INVALID_PERMISSION_RECORD"))
        }
    }

    fun automaticRequestAllowed(id: String): Boolean = synchronized(lock) {
        identifier(id)
        selected() == NativeAuthorizationPolicy.ON_FIRST_USE && id !in NativePermissionPlans.independent
            && !records().containsKey(id) && pendingRequests().none {
                id in it.permissionIds || id.removePrefix("capability:") in it.capabilityKeys
            }
    }

    fun recordRequested(id: String, now: Long = System.currentTimeMillis()) = record(id, NativePermissionOutcome.REQUESTING, now)
    fun recordOutcome(id: String, outcome: NativePermissionOutcome, now: Long = System.currentTimeMillis()) = record(id, outcome, now)

    private fun record(id: String, outcome: NativePermissionOutcome, now: Long) {
        identifier(id); require(now > 0)
        synchronized(lock) {
            val values = JSONObject(preferences.getString(RECORDS, "{}")!!)
            val previous = values.optJSONObject(id)
            values.put(id, JSONObject().put("firstRequestedAt", previous?.getLong("firstRequestedAt") ?: now)
                .put("updatedAt", now).put("outcome", outcome.id))
            commit(preferences.edit().putString(RECORDS, values.toString()))
        }
    }

    fun pendingRequests(): List<NativePendingPermissionRequest> = synchronized(lock) {
        val values = JSONArray(preferences.getString(PENDING, "[]")!!)
        (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            NativePendingPermissionRequest(value.getString("id"), value.optional("taskId"), value.optional("callId"),
                value.getJSONArray("capabilityKeys").strings().toSet(), value.getJSONArray("permissionIds").strings().toSet(), value.getLong("createdAt"))
        }
    }

    /** Offering a pending item does not change product consent, system grants or task state. */
    fun offerPending(request: NativePendingPermissionRequest): Boolean = synchronized(lock) {
        identifier(request.id)
        request.taskId?.let(::identifier); request.callId?.let(::identifier)
        require(request.createdAt > 0 && request.capabilityKeys.size <= 64 && request.permissionIds.size <= 32)
        request.capabilityKeys.forEach(::identifier); request.permissionIds.forEach(::identifier)
        require(request.capabilityKeys.isNotEmpty() || request.permissionIds.isNotEmpty())
        val previous = pendingRequests()
        previous.firstOrNull { it.id == request.id }?.let { existing ->
            check(existing.copy(createdAt = request.createdAt) == request) { "PERMISSION_PENDING_ID_CONFLICT" }
            return@synchronized false
        }
        check(previous.size < 32) { "PERMISSION_PENDING_LIMIT" }
        commit(preferences.edit().putString(PENDING, encode(previous + request).toString()))
        true
    }

    fun removePending(id: String) {
        identifier(id)
        synchronized(lock) { commit(preferences.edit().putString(PENDING, encode(pendingRequests().filterNot { it.id == id }).toString())) }
    }

    fun isRetiredCall(task: String, call: String): Boolean = synchronized(lock) {
        JSONObject(preferences.getString(RETIRED, "{}")!!).has(callKey(task, call))
    }

    /** A retired identity can only finish through this process's live, single-use handoff. */
    fun retireCall(task: String, call: String) {
        val key = callKey(task, call)
        synchronized(lock) {
            val retired = JSONObject(preferences.getString(RETIRED, "{}")!!)
            if (retired.has(key)) return
            check(retired.length() < 4096) { "PERMISSION_RETIRED_LIMIT" }
            retired.put(key, true)
            commit(preferences.edit().putString(RETIRED, retired.toString()))
        }
    }

    private fun callKey(task: String, call: String): String {
        identifier(task); identifier(call)
        return MessageDigest.getInstance("SHA-256").digest((task + '\u0000' + call).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun encode(values: List<NativePendingPermissionRequest>) = JSONArray(values.map { request ->
        JSONObject().put("id", request.id).put("taskId", request.taskId ?: JSONObject.NULL).put("callId", request.callId ?: JSONObject.NULL)
            .put("capabilityKeys", JSONArray(request.capabilityKeys.toList())).put("permissionIds", JSONArray(request.permissionIds.toList()))
            .put("createdAt", request.createdAt)
    })

    private fun identifier(value: String) { require(value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)) }
    private fun commit(editor: SharedPreferences.Editor) { check(editor.commit()) { "PERMISSION_POLICY_NOT_SAVED" } }
    private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONArray.strings() = (0 until length()).map(::getString)

    companion object {
        private val lock = Any()
        const val POLICY = "nativePermissionAuthorizationPolicy"
        private const val RECORDS = "nativePermissionRequestRecords"
        private const val PENDING = "nativePermissionPendingRequests"
        private const val RETIRED = "nativePermissionRetiredCalls"
    }
}

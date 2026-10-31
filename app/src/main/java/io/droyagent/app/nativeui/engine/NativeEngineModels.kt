package io.droyagent.app.nativeui.engine

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import org.json.JSONArray
import org.json.JSONObject

enum class NativeEngineSettingsSection { PRESETS, PERMISSIONS }
data class NativeAgentPreset(val id: String, val name: String?, val description: String?, val broken: Boolean, val isDefault: Boolean)
data class NativeDshPermission(val id: String, val name: String, val description: String?) {
    val needsRiskConfirmation: Boolean get() = id in setOf("danger-full-access", "auto")
}
data class NativeEngineDefault(val revision: Long, val selected: String, val writable: Boolean)
data class NativeEngineCatalog(
    val presetsAvailable: Boolean, val presets: List<NativeAgentPreset>, val defaultPreset: String?, val presetSetting: NativeEngineDefault?,
    val permissionsAvailable: Boolean, val permissions: List<NativeDshPermission>, val defaultPermissions: List<NativeDshPermission>,
    val defaultPermission: String?, val permissionSetting: NativeEngineDefault?,
)
data class NativeEngineSession(val id: String, val fresh: Boolean, val needsMigration: Boolean, val agentPreset: String?,
    val presetSelectable: Boolean?, val permission: String?, val permissionSelectable: Boolean?, val context: NativeEngineContext? = null, val goal: NativeEngineGoal? = null)
data class NativeEngineGoal(val id: String, val revision: Long, val objective: String, val phase: String, val roundsStarted: Int, val maxGoalRounds: Int, val activation: String = "disarmed")
data class NativeEngineContext(val pressureTokens: Double?, val projectedTokens: Double?, val contextWindow: Double?,
    val systemTokens: Double?, val toolsTokens: Double?, val messageTokens: Double?)
data class NativeComposerSkill(val name: String, val description: String, val modelInvocable: Boolean, val path: String?, val whenToUse: String?)
data class NativeCompactOperation(val sessionId: String, val requestId: String, val state: String, val outcome: String? = null,
    val commandId: String? = null, val sourceEventSeq: Long? = null) {
    val active: Boolean get() = state in setOf("starting", "running", "cancelling")
}
data class NativePresetDocument(val id: String, val content: String)
data class NativeEngineDraftSelection(val draftId: String, val nonce: String, val agentPresetId: String? = null, val permissionPresetId: String? = null,
    val permissionRiskAccepted: Boolean = false)
data class NativeEngineTarget(val sessionId: String?, val draftId: String?)
data class NativeEngineState(
    val runtime: NativeRuntimeState = NativeRuntimeState(), val loading: Boolean = false, val busy: Boolean = false,
    val fresh: Boolean = false, val catalog: NativeEngineCatalog? = null,
    val sessionId: String? = null, val sessionLoading: Boolean = false, val session: NativeEngineSession? = null,
    val document: NativePresetDocument? = null, val draftId: String? = null, val draftSelection: NativeEngineDraftSelection? = null,
    val error: String? = null, val receipt: Boolean = false,
    val skills: List<NativeComposerSkill> = emptyList(), val skillsSessionId: String? = null, val skillsLoading: Boolean = false,
    val skillsError: String? = null, val compaction: NativeCompactOperation? = null, val compactCancelPending: Boolean = false, val compactError: String? = null,
)
sealed interface NativeEngineAction {
    data class Goal(val action: String, val objective: String? = null, val expectedTarget: NativeEngineTarget, val expectedGoal: NativeEngineGoal?) : NativeEngineAction
    data object Refresh : NativeEngineAction
    data object RefreshSession : NativeEngineAction
    data object DismissError : NativeEngineAction
    data object CloseDocument : NativeEngineAction
    data object LoadSkills : NativeEngineAction
    data object Compact : NativeEngineAction
    data class CompactAt(val expectedTarget: NativeEngineTarget) : NativeEngineAction
    data object CancelCompact : NativeEngineAction
    data object RefreshCompact : NativeEngineAction
    data object DismissCompactReceipt : NativeEngineAction
    data class ViewPreset(val id: String) : NativeEngineAction
    data class DefaultPreset(val id: String) : NativeEngineAction
    data class SessionPreset(val id: String, val expectedTarget: NativeEngineTarget? = null) : NativeEngineAction
    data class DefaultPermission(val id: String, val acceptRisk: Boolean = false) : NativeEngineAction
    data class SessionPermission(val id: String, val acceptRisk: Boolean = false, val expectedTarget: NativeEngineTarget? = null) : NativeEngineAction
}

internal object NativeEngineJson {
    private fun optional(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)
    private fun rows(a: JSONArray): List<JSONObject> = (0 until a.length()).map(a::getJSONObject)
    private fun setting(value: JSONObject?): NativeEngineDefault? = value?.let { NativeEngineDefault(it.getLong("revision"), it.getString("selected"), it.getBoolean("writable")) }
    private fun permission(value: JSONObject) = NativeDshPermission(value.getString("value"), value.getString("name"), optional(value, "description"))
    fun catalog(value: JSONObject): NativeEngineCatalog {
        val presets = value.getJSONObject("presets"); val permissions = value.getJSONObject("permissions")
        val hasPresets = presets.getBoolean("available"); val hasPermissions = permissions.getBoolean("available")
        return NativeEngineCatalog(hasPresets, if (hasPresets) rows(presets.getJSONArray("options")).map {
            NativeAgentPreset(it.getString("id"), optional(it, "name"), optional(it, "description"), it.getBoolean("broken"), it.getBoolean("isDefault"))
        } else emptyList(), if (hasPresets) presets.getString("defaultId") else null, setting(presets.optJSONObject("setting")),
            hasPermissions, if (hasPermissions) rows(permissions.getJSONArray("options")).map(::permission) else emptyList(),
            if (hasPermissions) rows(permissions.getJSONArray("defaultOptions")).map(::permission) else emptyList(),
            if (hasPermissions) permissions.getString("defaultId") else null, setting(permissions.optJSONObject("setting")))
    }
    fun session(value: JSONObject): NativeEngineSession = NativeEngineSession(value.getString("sessionId"), value.getBoolean("fresh"), value.optBoolean("needsMigration"),
        optional(value, "agentPreset"), if (value.has("presetSelectable")) value.getBoolean("presetSelectable") else null,
        optional(value, "permission"), if (value.has("permissionSelectable")) value.getBoolean("permissionSelectable") else null, context(value.optJSONObject("context")), value.optJSONObject("goal")?.let { NativeEngineGoal(it.getString("id"), it.getLong("revision"), it.getString("objective"), it.getString("phase"), it.getInt("roundsStarted"), it.getInt("maxGoalRounds"), it.optString("activation", "disarmed")) })
    fun context(value: JSONObject?): NativeEngineContext? = value?.let { row ->
        fun number(source: JSONObject?, key: String): Double? {
            if (source == null || !source.has(key)) return null
            val number = source.get(key)
            if (number !is Number || !number.toDouble().isFinite() || number.toDouble() < 0) throw NativeApiException("NATIVE_ENGINE_CONTEXT_INVALID")
            return number.toDouble()
        }
        val breakdown = row.optJSONObject("breakdown")
        NativeEngineContext(number(row, "pressureTokens"), number(row, "projectedTokens"), number(row, "contextWindow"),
            number(breakdown, "systemTokens"), number(breakdown, "toolsTokens"), number(breakdown, "messageTokens"))
    }
    fun skills(value: JSONObject): Pair<String, List<NativeComposerSkill>> = value.getString("sessionId") to rows(value.getJSONArray("skills")).map { row ->
        NativeComposerSkill(row.getString("name"), row.getString("description"), row.getBoolean("modelInvocable"), optional(row, "path"), optional(row, "whenToUse"))
    }
    fun compaction(value: JSONObject): NativeCompactOperation {
        val phase = value.getString("state")
        require(phase in setOf("running", "cancelling", "settled", "unknown"))
        val outcome = optional(value, "outcome")
        require(outcome == null || outcome in setOf("success", "empty", "cancelled", "failed", "unconfirmed", "unavailable"))
        return NativeCompactOperation(value.getString("sessionId"), value.getString("requestId"), phase, outcome, optional(value, "commandId"), if (value.has("sourceEventSeq")) value.getLong("sourceEventSeq") else null)
    }
    fun document(value: JSONObject): NativePresetDocument {
        val content = value.getString("content")
        if (content.length > 524288) throw NativeApiException("NATIVE_ENGINE_DOCUMENT_UNAVAILABLE")
        return NativePresetDocument(value.getString("id"), content)
    }
}

package io.droyagent.app.nativeui.advanced

import org.json.JSONArray
import org.json.JSONObject

data class AdvancedField(val path: List<String>, val kind: String, val value: String, val options: List<String> = emptyList(), val credentialRef: String? = null, val configured: Boolean = false, val optionKinds: List<String> = emptyList()) {
    val key: String get() = JSONArray(path).toString()
    val label: String get() = path.joinToString(" · ")
}
data class AdvancedSection(val id: String, val revision: Long, val fields: List<AdvancedField>) {
    val inlineCredential: AdvancedInlineCredential? get() {
        if (id !in setOf("web-search-deepseek", "web-deepseek-api")) return null
        val field = fields.find { it.path == listOf("apiKey") && it.kind == "secret" } ?: return null
        val ref = fields.find { it.path == listOf("apiKeyEnv") }?.credentialRef ?: return null
        return AdvancedInlineCredential(ref, field.configured)
    }
}
data class AdvancedInlineCredential(val ref: String, val configured: Boolean)
data class AdvancedInlineReceipt(val status: String, val credential: String, val inline: String, val ref: String)
data class AdvancedDraft(val section: AdvancedSection, val edits: Map<String, String> = emptyMap(), val resets: Set<String> = emptySet())
data class AdvancedPlugin(val id: String, val name: String, val enabled: Boolean, val phase: String?, val writable: Boolean, val webUi: Boolean, val conditional: Boolean = false)
data class AdvancedBundle(val name: String, val version: String?, val enabled: Boolean, val installed: Boolean, val removable: Boolean, val writable: Boolean, val incompatible: List<AdvancedIncompatibility> = emptyList())
data class AdvancedIncompatibility(val packageVersion: String, val runtimeVersion: String, val requirements: List<String>)
data class AdvancedVersionExemption(val packageVersion: String, val runtimeVersion: String)
data class AdvancedRegistries(val configured: String?, val fallbacks: List<String>, val resolved: String?) {
    val choices: List<String> get() = (listOfNotNull(configured, resolved) + fallbacks).distinct()
}
data class AdvancedPreset(val id: String, val name: String, val rows: List<AdvancedPlugin>)
data class AdvancedAccount(val status: String = "signed-out", val attemptId: String? = null, val phase: String? = null, val authorizeUrl: String? = null, val running: Boolean = false, val name: String? = null, val contact: String? = null, val balances: List<String> = emptyList(), val detailsFailed: Boolean = false)
data class ApprovedPlugin(val id: String, val enabled: Boolean, val available: Boolean, val writable: Boolean, val version: String?)

data class NativeAdvancedState(
    val loading: Boolean = false, val busy: Boolean = false, val ready: Boolean = false, val writable: Boolean = false,
    val sections: List<AdvancedSection> = emptyList(), val draft: AdvancedDraft? = null,
    val approvedPlugins: List<ApprovedPlugin> = emptyList(), val approvedLoaded: Boolean = false,
    val pluginsAvailable: Boolean = false, val accountAvailable: Boolean = false,
    val plugins: List<AdvancedPlugin> = emptyList(), val bundles: List<AdvancedBundle> = emptyList(), val presets: List<AdvancedPreset> = emptyList(),
    val account: AdvancedAccount? = null, val error: String? = null, val receipt: String? = null,
    val inspectedSpec: String? = null, val inspection: String? = null, val installAllowed: Boolean = false,
    val installId: String? = null, val installState: String? = null, val operationDetails: List<String> = emptyList(),
    val inlineReceipt: AdvancedInlineReceipt? = null,
    val registries: AdvancedRegistries? = null, val selectedRegistry: String = "", val inspectedRegistry: String? = null,
    val installSpec: String? = null, val installRegistry: String = "", val pendingBuilds: List<String> = emptyList(),
    val runtimeVersion: String? = null, val versionExemptions: List<AdvancedVersionExemption> = emptyList(),
    val exemptionWarnings: Int = 0, val incompatible: List<AdvancedIncompatibility> = emptyList(),
)

internal object AdvancedJson {
    fun approved(value: JSONObject): List<ApprovedPlugin> = objects(value.getJSONArray("plugins")).map { row -> ApprovedPlugin(row.getString("id"), row.getBoolean("enabled"), row.getBoolean("available"), row.getBoolean("writable"), optional(row, "version")) }
    fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
    fun optional(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
    fun section(o: JSONObject) = AdvancedSection(o.getString("ns"), o.getLong("revision"), objects(o.getJSONArray("fields")).map { f ->
        val kind = f.getString("kind")
        AdvancedField((0 until f.getJSONArray("path").length()).map { f.getJSONArray("path").getString(it) }, kind,
            if (kind == "secret" || !f.has("value") || f.isNull("value")) "" else if (kind == "string") f.getString("value") else f.get("value").toString(),
            f.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.get(it).toString() } } ?: emptyList(), optional(f, "credentialRef"), f.optBoolean("configured"), f.optJSONArray("options")?.let { a -> (0 until a.length()).map { when (a.get(it)) { is Boolean -> "boolean"; is Number -> "number"; else -> "string" } } } ?: emptyList())
    })
    fun incompatible(a: JSONArray?): List<AdvancedIncompatibility> = objects(a).map { row ->
        val requirements = row.getJSONObject("requirements")
        AdvancedIncompatibility("${row.getString("name")}@${row.getString("version")}", row.getString("runtimeVersion"), requirements.keys().asSequence().map { "$it: ${requirements.getString(it)}" }.toList())
    }
    fun registryRequest(body: JSONObject, choice: String): JSONObject = body.apply {
        when (choice) { "" -> Unit; "pnpm" -> put("registry", JSONObject.NULL); else -> put("registry", choice) }
    }
    fun approvalRequest(spec: String, registry: String, sourceId: String, id: String, selected: Set<String>, pending: List<String>): JSONObject {
        require(selected.isNotEmpty() && selected.all { it in pending })
        return registryRequest(JSONObject().put("spec", spec).put("requestId", id).put("approvalFrom", sourceId).put("approvedBuilds", JSONArray(selected.sorted())), registry)
    }
    fun exemptionRequest(packageVersion: String, runtimeVersion: String, enabled: Boolean, acceptRisk: Boolean): JSONObject {
        require(!enabled || acceptRisk)
        return JSONObject().put("packageVersion", packageVersion).put("runtimeVersion", runtimeVersion).put("enabled", enabled).put("acceptRisk", acceptRisk)
    }
    fun plugin(o: JSONObject) = AdvancedPlugin(optional(o, "entryId") ?: "", o.getString("moduleName"), o.optBoolean("enabled"), optional(o, "fiberPhase"), o.has("patchId") && !o.has("readOnlyReason"), o.optString("presentation") == "web-ui", o.opt("enabled") == "conditional")
    fun account(o: JSONObject, previous: AdvancedAccount? = null, running: Boolean = false): AdvancedAccount {
        val attempt = o.optJSONObject("attempt")
        val retained = previous?.takeIf { it.status == o.getString("status") && it.status == "credential-stored" } ?: AdvancedAccount()
        return retained.copy(status = o.getString("status"), attemptId = attempt?.let { optional(it, "id") }, phase = attempt?.let { optional(it, "phase") }, authorizeUrl = attempt?.let { optional(it, "authorizeUrl") }, running = running)
    }
    fun mutation(draft: AdvancedDraft): JSONObject {
        val known = draft.section.fields.filter { it.kind !in setOf("secret", "unsupported") }.map { it.key }.toSet()
        require((draft.edits.keys + draft.resets).all { it in known })
        val ops = JSONArray()
        for (field in draft.section.fields) {
            if (field.key in draft.resets) ops.put(JSONObject().put("op", "unset").put("path", JSONArray(field.path)))
            else if (draft.edits.containsKey(field.key)) {
                val value = draft.edits.getValue(field.key)
                val decoded: Any = when (field.kind) {
                    "string" -> value
                    "boolean" -> when (value) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException() }
                    "number" -> value.toDouble().also { require(it.isFinite()) }
                    "array" -> JSONArray(value)
                    "enum" -> {
                        require(value in field.options)
                        // Preserve the schema option type: a string named "true" is still text.
                        when (field.optionKinds.getOrNull(field.options.indexOf(value))) { "boolean" -> value.toBoolean(); "number" -> value.toDouble(); else -> value }
                    }
                    else -> throw IllegalArgumentException()
                }
                ops.put(JSONObject().put("op", "set").put("path", JSONArray(field.path)).put("value", decoded))
            }
        }
        require(ops.length() > 0)
        return JSONObject().put("ns", draft.section.id).put("expectedRevision", draft.section.revision).put("ops", ops)
    }
}

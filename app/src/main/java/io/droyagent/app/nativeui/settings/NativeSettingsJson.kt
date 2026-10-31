package io.droyagent.app.nativeui.settings

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeModel
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class SettingsNamespace(val id: String, val revision: Long, val value: JSONObject, val user: JSONObject?)

internal object NativeSettingsJson {
    val protocols = setOf("openai-completions", "openai-responses", "anthropic-messages")

    fun namespaces(description: JSONObject): Map<String, SettingsNamespace> = description.getJSONArray("namespaces").objects().associate { value ->
        val name = value.getString("ns")
        name to SettingsNamespace(name, value.getLong("revision"), value.getJSONObject("value"), value.optJSONObject("user"))
    }

    fun objectAt(root: JSONObject, path: List<String>): JSONObject? {
        var value = root
        for (key in path) value = value.optJSONObject(key) ?: return null
        return value
    }

    fun providers(directory: JSONObject, sections: Map<String, SettingsNamespace>, credentials: JSONObject, catalog: List<NativeModel>, failedProviders: Set<String> = emptySet()): List<NativeSettingsProvider> {
        val active = directory.getJSONArray("active").objects().map { it.getString("id") }.toSet()
        return directory.getJSONArray("configurable").objects().map { row ->
            val id = row.getString("provider")
            val ns = row.getString("settingsNs")
            val path = row.getJSONArray("settingsPath").strings()
            val profile = sections[ns]?.let { objectAt(it.value, path) }
            val userProfile = sections[ns]?.user?.let { objectAt(it, path) }
            val ref = profile?.stringOrNull("apiKeyEnv")
            val refs = ref?.let(::listOf) ?: row.optJSONObject("nativeAuth")?.optJSONArray("env")?.strings().orEmpty()
            val metadata = refs.mapNotNull(credentials::optJSONObject)
            NativeSettingsProvider(id, row.getString("displayName"), ns, path,
                declared = row.optBoolean("declared"), profileExists = if (path.isEmpty()) userProfile?.length()?.let { it > 0 } == true else profile != null,
                active = id in active, unavailable = row.optBoolean("unavailable") || id in failedProviders,
                defaultBaseUrl = row.stringOrNull("defaultBaseURL"),
                credentialConfigured = if (metadata.isEmpty()) null else metadata.any { it.getBoolean("configured") },
                credentialWritable = if (ref == null) true else metadata.singleOrNull()?.optBoolean("writable") ?: false,
                explicitCredentialRef = ref, credentialRefs = refs, modelCount = catalog.filter { it.provider == id }.distinctBy { it.id }.size.takeIf { it > 0 }, protocol = profile?.stringOrNull("api"),
                oauthOnly = ref == null && row.optJSONObject("nativeAuth")?.let { it.optBoolean("oauth") && it.optJSONArray("env")?.length() == 0 } == true,
                removed = ns == "llm-deepseek" && profile?.optJSONArray("models")?.length() == 0)
        }
    }

    fun credentialRefs(directory: JSONObject, sections: Map<String, SettingsNamespace>): List<String> = buildSet {
        sections.filterKeys { it in setOf("web-search-deepseek", "web-deepseek-api") }.values.forEach { section ->
            section.value.stringOrNull("apiKeyEnv")?.let(::add)
        }
        directory.getJSONArray("configurable").objects().forEach { row ->
            val path = row.getJSONArray("settingsPath").strings()
            sections[row.getString("settingsNs")]?.let { objectAt(it.value, path)?.stringOrNull("apiKeyEnv") }?.let(::add)
            row.optJSONObject("nativeAuth")?.optJSONArray("env")?.strings()?.forEach(::add)
        }
    }.toList()

    fun catalog(value: JSONObject): List<NativeModel> = value.getJSONArray("groups").objects().flatMap { group ->
        group.getJSONArray("models").objects().map { model -> NativeModel(group.getString("id"), model.getString("id"),
            model.getString("name"), group.getString("name")) }
    }

    fun defaultModel(value: JSONObject, catalog: List<NativeModel>): NativeModel? {
        val current = value.optJSONObject("default") ?: return null
        val provider = current.getString("provider"); val id = current.getString("model")
        if (provider.isBlank() || id.isBlank()) return null
        return catalog.firstOrNull { it.provider == provider && it.id == id }
    }

    fun draft(provider: NativeSettingsProvider, namespace: SettingsNamespace, builtinModels: List<NativeSettingsModelEntry> = emptyList()): NativeProviderDraft {
        val profile = objectAt(namespace.value, provider.settingsPath)
        val rawModels = profile?.optJSONArray("models")
        return NativeProviderDraft(provider.id, provider.settingsNs, provider.settingsPath, namespace.revision,
            profile?.stringOrNull("displayName") ?: provider.name,
            profile?.stringOrNull("baseURL").orEmpty(),
            profile?.stringOrNull("api"),
            provider.explicitCredentialRef ?: newCredentialRef(provider.id), provider.explicitCredentialRef,
            provider.credentialConfigured, provider.credentialWritable,
            rawModels?.objects()?.map(::model).orEmpty(), useDefaultModels = rawModels == null || provider.removed,
            isNew = profile == null, custom = provider.declared, fixedName = provider.settingsNs == "llm-deepseek", builtinModels = builtinModels,
            modelOverridesJson = profile?.optJSONObject("modelOverrides")?.toString() ?: "{}")
    }

    fun customDraft(namespace: SettingsNamespace): NativeProviderDraft {
        val id = "provider-" + UUID.randomUUID().toString()
        return NativeProviderDraft(id, namespace.id, listOf("providers", id), namespace.revision,
            "", "", "openai-completions", newCredentialRef(id), null, false, true,
            emptyList(), useDefaultModels = false, isNew = true, custom = true, fixedName = false)
    }

    private fun newCredentialRef(id: String): String = "DROY_" + id.uppercase(java.util.Locale.ROOT)
        .replace(Regex("[^A-Z0-9_]"), "_") + "_API_KEY"

    fun model(value: JSONObject): NativeSettingsModelEntry = NativeSettingsModelEntry(value.getString("id"),
        value.stringOrNull("name") ?: value.getString("id"), value.longOrNull("contextWindow"), value.longOrNull("maxTokens"),
        value.optJSONArray("inputModalities")?.strings() ?:
            // DSH's pi-ai schema materializes absent input as []; its resolver treats both as inheritance.
            value.optJSONArray("input")?.strings()?.takeIf { it.isNotEmpty() }, value.toString(),
        value.opt("reasoningEfforts")?.takeUnless { it === JSONObject.NULL }?.toString())

    fun discovered(value: JSONArray): List<NativeSettingsModelEntry> = value.objects().map(::model).distinctBy { it.id }

    fun modelValue(value: NativeSettingsModelEntry, ns: String): JSONObject {
        val original = value.savedJson?.let(::JSONObject) ?: JSONObject()
        val result = JSONObject(original.toString())
        val baseline = value.savedJson?.let { model(JSONObject(it)) }
        fun update(key: String, next: Any?, changed: Boolean) {
            if (!changed) return
            if (next == null) result.remove(key) else result.put(key, next)
        }
        update("id", value.id, baseline == null || value.id != baseline.id)
        update("name", value.name.takeIf { it != value.id }, baseline == null || value.name != baseline.name)
        update("contextWindow", value.contextWindow, baseline == null || value.contextWindow != baseline.contextWindow)
        update("maxTokens", value.maxTokens, baseline == null || value.maxTokens != baseline.maxTokens)
        val modalityKey = if (ns == "llm-pi-ai") "input" else "inputModalities"
        if (value.inputModalities != null || value.inputModalities != baseline?.inputModalities) {
            result.remove(if (modalityKey == "input") "inputModalities" else "input")
            update(modalityKey, value.inputModalities?.let(::JSONArray), true)
        }
        if (value.reasoningJson != baseline?.reasoningJson) {
            update("reasoningEfforts", value.reasoningJson?.let { if (it == "false") false else JSONObject(it) }, true)
        }
        value.extraJson?.let { extra ->
            val fields = JSONObject(extra)
            for (key in listOf("compat", "description", "imagePixelBudget", "imageMaxBytes", "systemPromptUpdate", "toolUpdate")) {
                if (fields.has(key)) update(key, fields.opt(key).takeUnless { it === JSONObject.NULL }, true)
            }
        }
        return result
    }

    /** Only fields changed by this editor cross the write boundary. */
    fun mutation(draft: NativeProviderDraft, original: SettingsNamespace, writeCredential: Boolean): JSONObject? {
        validate(draft)
        val previous = objectAt(original.value, draft.settingsPath)
        if (draft.isNew && previous != null) throw NativeApiException("settings/conflict")
        val operations = JSONArray()
        val values = linkedMapOf<String, Any>()
        if (!draft.fixedName && (draft.isNew || draft.name != draft.originalName)) values["displayName"] = draft.name.trim()
        if (draft.baseUrl.isNotBlank()) values["baseURL"] = draft.baseUrl.trim()
        if (!draft.fixedName && draft.protocol != null) values["api"] = draft.protocol
        if (writeCredential) values["apiKeyEnv"] = draft.credentialRef
        if (draft.settingsNs == "llm-pi-ai" && draft.modelOverridesJson != draft.originalOverridesJson)
            values["modelOverrides"] = JSONObject(draft.modelOverridesJson)
        if (!draft.useDefaultModels) values["models"] = JSONArray(draft.models.map { modelValue(it, draft.settingsNs) })
        fun set(field: String, value: Any) {
            operations.put(JSONObject().put("op", "set").put("path", JSONArray(draft.settingsPath + field)).put("value", value))
        }
        if (draft.isNew) {
            val profile = JSONObject()
            values.forEach { (key, value) -> profile.put(key, value) }
            operations.put(JSONObject().put("op", "set").put("path", JSONArray(draft.settingsPath)).put("value", profile))
        } else {
            values.forEach { (field, value) ->
                if (!jsonEquals(previous?.opt(field), value)) set(field, value)
            }
            for (field in listOf("baseURL", "api", "models")) {
                val cleared = when (field) { "baseURL" -> draft.baseUrl.isBlank(); "api" -> draft.protocol == null && !draft.fixedName; else -> draft.useDefaultModels }
                if (cleared && previous?.has(field) == true) operations.put(JSONObject().put("op", "unset").put("path", JSONArray(draft.settingsPath + field)))
            }
        }
        if (operations.length() == 0) return null
        return JSONObject().put("ns", draft.settingsNs).put("expectedRevision", draft.revision).put("ops", operations)
    }

    fun validate(draft: NativeProviderDraft) {
        if (!draft.fixedName && (draft.name.isBlank() || draft.name.length > 256 || draft.name.any(Char::isISOControl)))
            throw NativeApiException("settings_invalid_name")
        if (draft.custom && draft.baseUrl.isBlank()) throw NativeApiException("settings_invalid_endpoint")
        if (draft.baseUrl.isNotBlank()) {
            val url = try { URI(draft.baseUrl.trim()) } catch (_: java.net.URISyntaxException) { throw NativeApiException("settings_invalid_endpoint") }
            if (url.scheme !in setOf("http", "https") || url.host.isNullOrBlank() || url.userInfo != null || url.fragment != null)
                throw NativeApiException("settings_invalid_endpoint")
        }
        if (draft.protocol != null && draft.protocol !in protocols && (draft.isNew || draft.protocol != draft.originalProtocol))
            throw NativeApiException("settings_invalid_protocol")
        if (draft.custom && draft.protocol == null) throw NativeApiException("settings_invalid_protocol")
        if (!draft.useDefaultModels && (draft.models.isEmpty() || draft.models.any { it.id.isBlank() || it.id.length > 200 || it.id.any { char -> char.isISOControl() || char.isWhitespace() } }))
            throw NativeApiException("settings_invalid_models")
        if (draft.models.map { it.id }.toSet().size != draft.models.size) throw NativeApiException("settings_invalid_models")
    }

    fun validateKey(value: String) {
        if (value.isBlank()) return
        val key = value.trim()
        if (key.length > 16384 || key.any { it.code !in 33..126 } || Regex("^[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(key)
            || key.length >= 2 && key.first() in setOf('\'', '"') && key.last() == key.first())
            throw NativeApiException("settings_invalid_key")
    }

    fun verification(value: JSONObject): NativeModelVerification? {
        val receipt = value.optJSONObject("modelVerification") ?: return null
        val passed = receipt.optBoolean("chatPassed", receipt.optBoolean("passed"))
        return NativeModelVerification(passed, receipt.optBoolean("toolsPassed"), receipt.stringOrNull("provider"), receipt.stringOrNull("model"),
            receipt.opt("checkedAt")?.takeUnless { it === JSONObject.NULL }?.toString())
    }

    private fun jsonEquals(first: Any?, second: Any?): Boolean = when {
        first is JSONObject && second is JSONObject -> first.keys().asSequence().toSet() == second.keys().asSequence().toSet()
            && first.keys().asSequence().all { jsonEquals(first.opt(it), second.opt(it)) }
        first is JSONArray && second is JSONArray -> first.length() == second.length() && (0 until first.length()).all { jsonEquals(first.opt(it), second.opt(it)) }
        else -> first == second
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
internal fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key).takeIf(String::isNotBlank) else null
internal fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

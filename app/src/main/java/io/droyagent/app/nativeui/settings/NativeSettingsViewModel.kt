package io.droyagent.app.nativeui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NativeSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val api = NativeSettingsClient()
    private val initial = NativeRuntimeHost.inspect(application)
    private val _state = MutableStateFlow(NativeSettingsState(runtime = NativeRuntimeState(initial.ready, initial.active, initial.canStart, initial.status, initial.locale), locale = initial.locale))
    val state: StateFlow<NativeSettingsState> = _state.asStateFlow()
    private var sections = emptyMap<String, SettingsNamespace>()
    private var endpoint: NativeRuntimeHost.Endpoint? = null
    private var observing: Job? = null
    private var refreshing: Job? = null
    private var mutation: Job? = null
    private var discovery: Job? = null
    private var testing: Job? = null
    private var pendingCredentialRef: String? = null
    private var discoveryGeneration = 0L

    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            while (isActive) {
                val runtime = NativeRuntimeHost.inspect(getApplication())
                _state.update { it.copy(runtime = NativeRuntimeState(runtime.ready, runtime.active, runtime.canStart, runtime.status, runtime.locale)) }
                if (runtime.ready && endpoint?.isCurrent() != true) {
                    endpoint = NativeRuntimeHost.connection()
                    refresh()
                } else if (!runtime.ready) {
                    endpoint = null
                    refreshing?.cancel(); refreshing = null
                    _state.update { it.copy(loading = false, loaded = false) }
                }
                delay(750)
            }
        }
    }

    fun stopObserving() {
        observing?.cancel(); observing = null
        refreshing?.cancel(); refreshing = null; endpoint = null
    }

    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); dismissError() }
        catch (_: IllegalStateException) { fail("runtime_start_failed") }
        catch (_: SecurityException) { fail("runtime_start_failed") }
    }

    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try { load() }
            catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(loading = false) } }
        }
    }

    private suspend fun load() {
        val description = api.describe()
        val nextSections = NativeSettingsJson.namespaces(description)
        val directory = api.providers()
        val catalogValue = api.catalog()
        val catalog = NativeSettingsJson.catalog(catalogValue)
        val metadata = JSONObject()
        for (batch in NativeSettingsJson.credentialRefs(directory, nextSections).chunked(64)) {
            val current = api.metadata(batch)
            current.keys().forEach { key -> metadata.put(key, current.getJSONObject(key)) }
        }
        val locale = nextSections["locale"]?.value?.stringOrNull("preference")?.takeIf { it == "zh" || it == "en" } ?: _state.value.runtime.locale
        if (locale != NativeRuntimeHost.inspect(getApplication()).locale) NativeRuntimeHost.adoptLocale(getApplication(), locale)
        sections = nextSections
        val providers = NativeSettingsJson.providers(directory, nextSections, metadata, catalog,
            catalogValue.getJSONArray("failures").objects().map { it.getString("id") }.toSet())
        val configured = NativeSettingsState(providers = providers).configuredProviders.map { it.id }.toSet()
        val selectable = catalog.filter { it.provider in configured }
        _state.update { it.copy(loaded = true, writable = description.getBoolean("writable"), locale = locale,
            providers = providers, catalog = selectable,
            defaultModel = NativeSettingsJson.defaultModel(catalogValue, selectable),
            featureSettings = nextSections.filterKeys { it in setOf("web", "web-search-deepseek", "web-deepseek-api", "subagent", "subagent-model-selection-settings") }
                .mapValues { entry -> JSONObject().put("value", entry.value.value).put("revision", entry.value.revision)
                    .put("credentialConfigured", entry.value.value.stringOrNull("apiKeyEnv")?.let { metadata.optJSONObject(it)?.optBoolean("configured") } == true).toString() }) }
        refreshVerification()
    }

    private suspend fun refreshVerification() {
        try {
            val verification = NativeSettingsJson.verification(api.verification())
            _state.update { it.copy(verification = verification, verificationUnavailable = false) }
        }
        catch (_: NativeApiException) { _state.update { it.copy(verification = null, verificationUnavailable = true) } }
    }

    fun openProvider(id: String) {
        if (_state.value.saving) return
        val provider = _state.value.providers.firstOrNull { it.id == id } ?: return fail("NATIVE_SETTINGS_NOT_FOUND")
        val section = sections[provider.settingsNs] ?: return fail("NATIVE_SETTINGS_NOT_READY")
        discoveryGeneration++; discovery?.cancel(); pendingCredentialRef = null
        _state.update { it.copy(editor = NativeSettingsJson.draft(provider, section, _state.value.catalog.filter { it.provider == id }.map { NativeSettingsModelEntry(it.id, it.name) }), editorGeneration = it.editorGeneration + 1, credentialPending = false,
            savedEditorId = null, discoveredModels = emptyList(), discoveryComplete = false, discovering = false, error = null) }
    }

    fun addProvider() {
        if (_state.value.saving) return
        val section = sections["llm-pi-ai"] ?: return fail("NATIVE_SETTINGS_NAMESPACE_UNAVAILABLE")
        discoveryGeneration++; discovery?.cancel(); pendingCredentialRef = null
        _state.update { it.copy(editor = NativeSettingsJson.customDraft(section), editorGeneration = it.editorGeneration + 1, credentialPending = false,
            savedEditorId = null, discoveredModels = emptyList(), discoveryComplete = false, discovering = false, error = null) }
    }

    fun closeEditor() {
        if (_state.value.saving) return
        val committedSettings = _state.value.credentialPending
        discoveryGeneration++; discovery?.cancel(); pendingCredentialRef = null
        _state.update { it.copy(editor = null, credentialPending = false, discoveredModels = emptyList(), discoveryComplete = false, discovering = false, error = null) }
        if (committedSettings && _state.value.runtime.ready) refresh()
    }

    fun reloadEditor() {
        val id = _state.value.editor?.id ?: return refresh()
        if (_state.value.saving || refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try { load(); openProvider(id) }
            catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(loading = false) } }
        }
    }

    fun editProvider(value: NativeProviderDraft) {
        if (_state.value.saving || _state.value.credentialPending || _state.value.editor?.id != value.id) return
        _state.update { it.copy(editor = value, error = null, savedEditorId = null) }
    }

    fun editProviderInputs(id: String, name: String, baseUrl: String) = editProviderField(id) { copy(name = name, baseUrl = baseUrl) }

    fun editProviderProtocol(id: String, protocol: String?) = editProviderField(id) { copy(protocol = protocol) }
    fun editProviderDefaultModels(id: String, useDefault: Boolean) = editProviderField(id) { copy(useDefaultModels = useDefault) }

    private fun editProviderField(id: String, edit: NativeProviderDraft.() -> NativeProviderDraft) {
        _state.update { current ->
            val draft = current.editor
            if (draft == null || draft.id != id || current.saving || current.credentialPending) current
            else current.copy(editor = draft.edit(), error = null, savedEditorId = null)
        }
    }

    fun addManualModel(id: String) {
        val draft = _state.value.editor ?: return
        val value = id.trim()
        if (value.isBlank() || value.length > 200 || value.any { it.isISOControl() || it.isWhitespace() }) return fail("settings_invalid_models")
        if (draft.models.any { it.id == value }) return fail("settings_duplicate_model")
        editProvider(draft.copy(models = draft.models + NativeSettingsModelEntry(value), useDefaultModels = false))
    }

    fun providerModels(draft: NativeProviderDraft): List<NativeSettingsModelEntry> {
        if (!draft.useDefaultModels) return draft.models
        val overrides = JSONObject(draft.modelOverridesJson)
        return draft.builtinModels.map { base ->
            overrides.optJSONObject(base.id)?.let { override -> NativeSettingsJson.model(JSONObject(override.toString()).put("id", base.id))
                .let { if (override.has("name")) it else it.copy(name = base.name) } } ?: base
        }
    }

    fun saveModel(entry: NativeSettingsModelEntry, previousId: String?): Boolean {
        val draft = _state.value.editor ?: return false
        if (entry.id.isBlank() || entry.id.length > 200 || entry.id.any { it.isWhitespace() || it.isISOControl() }) { fail("settings_invalid_models"); return false }
        if (providerModels(draft).any { it.id == entry.id && it.id != previousId }) { fail("settings_duplicate_model"); return false }
        if (draft.useDefaultModels && previousId != null && draft.settingsNs == "llm-pi-ai") {
            val overrides = JSONObject(draft.modelOverridesJson)
            overrides.remove(previousId)
            overrides.put(entry.id, NativeSettingsJson.modelValue(entry, draft.settingsNs).apply {
                remove("id")
                if (entry.name == draft.builtinModels.firstOrNull { it.id == entry.id }?.name &&
                    !JSONObject(draft.modelOverridesJson).optJSONObject(entry.id).let { it?.has("name") == true }) remove("name")
            })
            editProvider(draft.copy(modelOverridesJson = overrides.toString()))
        } else {
            val entries = providerModels(draft)
            val changed = if (previousId == null) entries + entry else entries.map { if (it.id == previousId) entry else it }
            editProvider(draft.copy(models = changed, useDefaultModels = false))
        }
        return true
    }

    fun saveFeature(namespace: String, value: JSONObject, apiKey: String = "") =
        saveFeatures(linkedMapOf(namespace to value), namespace.takeIf { apiKey.isNotBlank() }, apiKey)

    /** Save service parameters before routing; a failed write never activates an incomplete endpoint. */
    fun saveFeatures(values: Map<String, JSONObject>, credentialNamespace: String? = null, apiKey: String = "") {
        if (values.keys.any { it !in sections }) return fail("NATIVE_SETTINGS_NOT_READY")
        if (_state.value.saving || !_state.value.writable) return
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                NativeSettingsJson.validateKey(apiKey)
                for ((namespace, value) in values) {
                    val original = sections.getValue(namespace)
                    val proposed = JSONObject(value.toString())
                    val savesCredential = namespace == credentialNamespace && apiKey.isNotBlank()
                    if (savesCredential) proposed.put("apiKeyEnv", original.value.stringOrNull("apiKeyEnv") ?: "DEEPSEEK_API_KEY")
                    val ops = JSONArray()
                    proposed.keys().forEach { key ->
                        val next = proposed.opt(key)
                        if (next === JSONObject.NULL) {
                            if (original.value.has(key)) ops.put(JSONObject().put("op", "unset").put("path", JSONArray().put(key)))
                        } else if (original.value.opt(key)?.toString() != next.toString()) {
                            ops.put(JSONObject().put("op", "set").put("path", JSONArray().put(key)).put("value", next))
                        }
                    }
                    if (ops.length() > 0) api.mutate(JSONObject().put("ns", namespace).put("expectedRevision", original.revision).put("ops", ops))
                    if (savesCredential) requireCommitted(api.setCredential(proposed.getString("apiKeyEnv"), apiKey))
                }
                load()
                _state.update { it.copy(featureSaveReceipt = it.featureSaveReceipt + 1) }
            } catch (error: Exception) { handle(error); loadFeatureAfterFailure() }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    private suspend fun loadFeatureAfterFailure() {
        // A settings write may precede a failed credential write. Refresh revisions
        // without treating that partial result as a successfully saved credential.
        val code = _state.value.error
        try { load(); _state.update { it.copy(error = code) } }
        catch (error: Exception) { handle(error) }
    }

    fun removeModel(id: String) {
        val draft = _state.value.editor ?: return
        editProvider(draft.copy(models = providerModels(draft).filterNot { it.id == id }, useDefaultModels = false))
    }

    fun addDiscovered(ids: Set<String>) {
        val draft = _state.value.editor ?: return
        val added = _state.value.discoveredModels.filter { it.id in ids }
        editProvider(draft.copy(models = (providerModels(draft) + added).distinctBy { it.id }, useDefaultModels = false))
        _state.update { it.copy(discoveryComplete = false, discoveredModels = emptyList()) }
    }

    fun dismissDiscovery() { _state.update { it.copy(discoveryComplete = false) } }

    fun discover(apiKey: String) {
        val draft = _state.value.editor ?: return
        if (_state.value.discovering || _state.value.saving || _state.value.credentialPending) return
        val generation = ++discoveryGeneration
        discovery = viewModelScope.launch {
            _state.update { it.copy(discovering = true, error = null) }
            try {
                NativeSettingsJson.validateKey(apiKey)
                NativeSettingsJson.validate(draft.copy(useDefaultModels = true))
                val rows = if (draft.settingsNs == "llm-deepseek") {
                    val catalog = api.catalog()
                    catalog.getJSONArray("groups").objects().firstOrNull { it.getString("id") == draft.id }
                        ?.getJSONArray("models") ?: JSONArray()
                } else {
                val request = JSONObject().apply {
                    if (!draft.isNew || !draft.custom) put("provider", draft.id)
                    if (draft.baseUrl.isNotBlank()) put("baseURL", draft.baseUrl.trim())
                    draft.protocol?.let { put("api", it) }
                    if (apiKey.isNotBlank()) put("apiKey", apiKey)
                }
                api.discover(JSONObject().put("settingsNs", draft.settingsNs).put("request", request))
                }
                if (generation == discoveryGeneration && _state.value.editor?.id == draft.id)
                    _state.update { it.copy(discoveredModels = NativeSettingsJson.discovered(rows), discoveryComplete = true) }
            } catch (error: Exception) { handle(error) }
            finally { if (generation == discoveryGeneration) _state.update { it.copy(discovering = false) } }
        }
    }

    fun saveProvider(apiKey: String) {
        val draft = _state.value.editor ?: return
        if (mutation?.isActive == true || _state.value.saving || _state.value.testing || !_state.value.writable) return
        if (_state.value.credentialPending) { retryCredential(apiKey); return }
        val original = sections[draft.settingsNs] ?: return fail("NATIVE_SETTINGS_NOT_READY")
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            var settingsApplied = false
            try {
                NativeSettingsJson.validateKey(apiKey)
                NativeSettingsJson.mutation(draft, original, apiKey.isNotBlank())?.let { api.mutate(it) }
                settingsApplied = true
                if (apiKey.isNotBlank()) {
                    pendingCredentialRef = draft.credentialRef
                    _state.update { it.copy(credentialPending = true) }
                    requireCommitted(api.setCredential(draft.credentialRef, apiKey))
                }
                pendingCredentialRef = null
                _state.update { it.copy(credentialPending = false, savedEditorId = draft.id, editor = null) }
                load()
            } catch (error: Exception) {
                if (settingsApplied && pendingCredentialRef != null) _state.update { it.copy(credentialPending = true) }
                handle(error)
            } finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun retryCredential(apiKey: String) {
        val ref = pendingCredentialRef ?: return
        val id = _state.value.editor?.id ?: return
        if (mutation?.isActive == true || _state.value.saving) return
        if (apiKey.isBlank()) return fail("settings_key_required")
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                NativeSettingsJson.validateKey(apiKey)
                requireCommitted(api.setCredential(ref, apiKey))
                pendingCredentialRef = null
                _state.update { it.copy(credentialPending = false, savedEditorId = id, editor = null) }
                load()
            } catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun clearCredential() {
        val draft = _state.value.editor ?: return
        val ref = draft.explicitCredentialRef ?: return
        if (mutation?.isActive == true || _state.value.saving || _state.value.testing || !_state.value.writable || !draft.credentialWritable || _state.value.credentialPending) return
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                requireCommitted(api.clearCredential(ref))
                load()
                _state.update { it.copy(editor = it.editor?.copy(credentialConfigured = false)) }
            } catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun setLanguage(language: String) {
        if (language !in setOf("zh", "en") || mutation?.isActive == true || _state.value.saving || _state.value.testing || !_state.value.writable) return
        val section = sections["locale"] ?: return fail("NATIVE_SETTINGS_NAMESPACE_UNAVAILABLE")
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                api.mutate(JSONObject().put("ns", "locale").put("expectedRevision", section.revision).put("ops", JSONArray()
                    .put(JSONObject().put("op", "set").put("path", JSONArray().put("preference")).put("value", language))))
                NativeRuntimeHost.adoptLocale(getApplication(), language)
                load()
            } catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun setDefault(value: NativeModel) {
        if (mutation?.isActive == true || _state.value.saving || _state.value.testing || !_state.value.writable) return
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, verification = null) }
            try { requireCommitted(api.setDefault(value.provider, value.id)); load() }
            catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun removeProvider(id: String) {
        if (mutation?.isActive == true || _state.value.saving || _state.value.testing || !_state.value.writable) return
        val provider = _state.value.configuredProviders.firstOrNull { it.id == id } ?: return
        val section = sections[provider.settingsNs] ?: return fail("NATIVE_SETTINGS_NOT_READY")
        mutation = viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try { requireCommitted(api.removeProvider(id, section.revision)); load() }
            catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun testDefaultModel() {
        if (testing?.isActive == true || _state.value.testing || _state.value.saving || _state.value.defaultModel == null) return
        testing = viewModelScope.launch {
            _state.update { it.copy(testing = true, error = null, verification = null) }
            try {
                val verification = NativeSettingsJson.verification(api.testDefault())
                _state.update { it.copy(verification = verification, verificationUnavailable = false) }
            }
            catch (error: Exception) { handle(error) }
            finally { _state.update { it.copy(testing = false) } }
        }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }
    private fun requireCommitted(value: JSONObject) { if (!value.getBoolean("committed")) throw NativeApiException("malformed_response") }
    private fun handle(error: Exception) { if (error is CancellationException) throw error; fail(if (error is NativeApiException) error.code else "request_failed") }
    private fun fail(code: String) { _state.update { it.copy(error = code) } }
}

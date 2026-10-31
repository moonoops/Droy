package io.droyagent.app.nativeui.advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

class NativeAdvancedViewModel(application: Application) : AndroidViewModel(application) {
    private val api = NativeAdvancedClient()
    private val mutable = MutableStateFlow(NativeAdvancedState())
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private var request: Job? = null
    private var poll: Job? = null
    private var pollIdentity: String? = null
    private var endpoint: NativeRuntimeHost.Endpoint? = null
    fun startObserving() {
        if (observer?.isActive == true) return
        observer = viewModelScope.launch {
            while (isActive) {
                val ready = NativeRuntimeHost.inspect(getApplication()).ready
                mutable.update { it.copy(ready = ready) }
                if (ready && endpoint?.isCurrent() != true) { endpoint = NativeRuntimeHost.connection(); refresh() }
                if (!ready) endpoint = null
                delay(1000)
            }
        }
    }
    fun stopObserving() { observer?.cancel(); observer = null; endpoint = null; poll?.cancel(); poll = null; pollIdentity = null }
    fun refresh() = action {
        val root = api.call(); val settings = root.getJSONObject("settings")
        val sections = AdvancedJson.objects(settings.getJSONArray("namespaces")).map(AdvancedJson::section)
        mutable.update { it.copy(sections = sections, writable = settings.getBoolean("writable"), pluginsAvailable = root.getBoolean("pluginsAvailable"), accountAvailable = root.getBoolean("accountAvailable")) }
        if (mutable.value.installId != null) pollInstall()
    }
    fun openSection(id: String) { mutable.value.sections.find { it.id == id }?.let { section -> mutable.update { it.copy(draft = AdvancedDraft(section), error = null, receipt = null, inlineReceipt = null) } } }
    fun closeSection() { if (!mutable.value.busy) mutable.update { it.copy(draft = null, error = null, inlineReceipt = null) } }
    fun edit(key: String, value: String) { if (!mutable.value.busy) mutable.update { s -> s.copy(draft = s.draft?.let { it.copy(edits = it.edits + (key to value), resets = it.resets - key) }) } }
    fun reset(key: String) { if (!mutable.value.busy) mutable.update { s -> s.copy(draft = s.draft?.let { it.copy(edits = it.edits - key, resets = it.resets + key) }) } }
    fun reloadDraft() = action {
        val old = mutable.value.draft ?: return@action
        val result = api.call().getJSONObject("settings")
        val sections = AdvancedJson.objects(result.getJSONArray("namespaces")).map(AdvancedJson::section)
        val latest = sections.find { it.id == old.section.id } ?: throw NativeApiException("NATIVE_ADVANCED_UNAVAILABLE")
        mutable.update { it.copy(sections = sections, draft = old.copy(section = latest), receipt = "review-draft") }
    }
    fun save() = action {
        val draft = mutable.value.draft ?: return@action
        val result = AdvancedJson.section(api.call("/mutate", AdvancedJson.mutation(draft)))
        mutable.update { it.copy(sections = it.sections.map { s -> if (s.id == result.id) result else s }, draft = AdvancedDraft(result), receipt = "saved") }
    }
    fun credential(ref: String, value: String?, remove: Boolean = false) = action {
        val result = api.call("/credentials", JSONObject().put("ref", ref).put("operation", if (remove) "unset" else if (value == null) "describe" else "set").apply { if (value != null) put("value", value) })
        mutable.update { it.copy(receipt = if ((value != null || remove) && (result.optJSONArray("overriddenByInline")?.length() ?: 0) > 0) { if (remove) "credential-cleared-shadowed" else "credential-shadowed" } else if (value != null || remove) "saved" else if (result.optJSONObject(ref)?.optBoolean("configured") == true) "credential-set" else "credential-empty") }
    }
    fun changeInlineCredential(ns: String, revision: Long, newValue: String? = null) = action {
        val result = api.call("/credentials/inline", JSONObject().put("ns", ns).put("expectedRevision", revision)
            .put("operation", if (newValue == null) "clear" else "migrate").apply { if (newValue != null) put("value", newValue) })
        val saved = result.optJSONObject("section")?.let(AdvancedJson::section)
        mutable.update { state -> state.copy(
            sections = if (saved == null) state.sections else state.sections.map { if (it.id == saved.id) saved else it },
            draft = if (saved == null || state.draft?.section?.id != saved.id) state.draft else state.draft.copy(section = saved),
            inlineReceipt = AdvancedInlineReceipt(result.getString("status"), result.getString("credential"), result.getString("inline"), result.getString("ref")),
        ) }
    }
    fun loadApprovedPlugins(): Boolean = startAction(loading = true) {
        val result = api.call("/approved-plugins")
        mutable.update { it.copy(approvedPlugins = AdvancedJson.approved(result), approvedLoaded = true) }
    }
    fun enableApprovedPlugin(id: String, enabled: Boolean) = action {
        val current = mutable.value.approvedPlugins.singleOrNull { it.id == id } ?: throw NativeApiException("NATIVE_ADVANCED_UNAVAILABLE")
        val result = api.call("/approved-plugins/enable", JSONObject().put("id", id).put("enabled", enabled).put("expectedEnabled", current.enabled))
        receipt(result.getJSONObject("change"))
        mutable.update { it.copy(approvedPlugins = AdvancedJson.approved(result), approvedLoaded = true) }
    }
    fun loadPlugins() = action { readPlugins() }
    private suspend fun readPlugins() {
        val value = api.call("/plugins")
        mutable.update { it.copy(plugins = AdvancedJson.objects(value.getJSONArray("plugins")).map(AdvancedJson::plugin),
            bundles = AdvancedJson.objects(value.getJSONArray("bundles")).map { b -> AdvancedBundle(b.getString("name"), AdvancedJson.optional(b, "version"), b.getBoolean("enabled"), b.getBoolean("installed"), b.getBoolean("removable"), !b.has("readOnlyReason"), AdvancedJson.incompatible(b.optJSONObject("error")?.optJSONArray("incompatible"))) },
            presets = AdvancedJson.objects(value.getJSONArray("presets")).map { p -> AdvancedPreset(p.getString("id"), p.optString("name", p.getString("id")), AdvancedJson.objects(p.getJSONArray("rows")).map(AdvancedJson::plugin)) },
            registries = value.optJSONObject("registries")?.let { r -> AdvancedRegistries(AdvancedJson.optional(r, "registry"), r.getJSONArray("fallbackRegistries").let { a -> (0 until a.length()).map(a::getString) }, AdvancedJson.optional(r, "resolved")) },
            runtimeVersion = AdvancedJson.optional(value, "runtimeVersion"),
            versionExemptions = value.optJSONObject("versionExemptions")?.getJSONObject("exemptions")?.let { e -> e.keys().asSequence().flatMap { name -> e.getJSONArray(name).let { a -> (0 until a.length()).map { AdvancedVersionExemption(name, a.getString(it)) } }.asSequence() }.toList() } ?: emptyList(),
            exemptionWarnings = value.optJSONObject("versionExemptions")?.optJSONArray("warnings")?.length() ?: 0) }
    }
    fun selectRegistry(choice: String) { if (!mutable.value.busy) mutable.update { it.copy(selectedRegistry = choice, inspectedRegistry = null, installAllowed = false, inspection = null) } }
    fun enable(kind: String, id: String, enabled: Boolean) = action { receipt(api.call("/plugins/enable", JSONObject().put("kind", kind).put("id", id).put("enabled", enabled))); readPlugins() }
    fun remove(name: String) = action { receipt(api.call("/plugins/remove", JSONObject().put("name", name))); readPlugins() }
    fun inspect(spec: String) = action {
        val registry = mutable.value.selectedRegistry
        val result = api.call("/plugins/inspect", AdvancedJson.registryRequest(JSONObject().put("spec", spec.trim()), registry))
        mutable.update { it.copy(inspectedSpec = spec.trim(), inspectedRegistry = registry, inspection = listOfNotNull(AdvancedJson.optional(result, "name"), AdvancedJson.optional(result, "version"), AdvancedJson.optional(result, "description"), AdvancedJson.optional(result, "problem")).joinToString("\n"), installAllowed = result.optString("status") == "accepted") }
    }
    fun install(spec: String) = action {
        if (!mutable.value.installAllowed || mutable.value.inspectedSpec != spec.trim() || mutable.value.inspectedRegistry != mutable.value.selectedRegistry) throw NativeApiException("NATIVE_ADVANCED_INVALID")
        val id = UUID.randomUUID().toString()
        val registry = mutable.value.selectedRegistry
        // Keep the identity even if the response is lost; never blindly send a second installation.
        mutable.update { it.copy(installId = id, installState = "running", installSpec = spec.trim(), installRegistry = registry, pendingBuilds = emptyList()) }
        api.call("/plugins/install", AdvancedJson.registryRequest(JSONObject().put("spec", spec.trim()).put("requestId", id), registry)); pollInstall()
    }
    fun approveBuilds(selected: Set<String>) = action {
        val state = mutable.value
        val source = state.installId ?: throw NativeApiException("NATIVE_ADVANCED_INVALID")
        val spec = state.installSpec ?: throw NativeApiException("NATIVE_ADVANCED_INVALID")
        if (state.installState != "settled") throw NativeApiException("NATIVE_ADVANCED_INVALID")
        val id = UUID.randomUUID().toString()
        val body = AdvancedJson.approvalRequest(spec, state.installRegistry, source, id, selected, state.pendingBuilds)
        mutable.update { it.copy(installId = id, installState = "running", pendingBuilds = emptyList()) }
        api.call("/plugins/install", body); pollInstall()
    }
    fun setVersionExemption(packageVersion: String, runtimeVersion: String, enabled: Boolean, acceptRisk: Boolean) = action {
        if (enabled && mutable.value.runtimeVersion != runtimeVersion) throw NativeApiException("NATIVE_ADVANCED_RUNTIME_CHANGED")
        receipt(api.call("/plugins/version-exemption", AdvancedJson.exemptionRequest(packageVersion, runtimeVersion, enabled, acceptRisk)))
        readPlugins()
    }
    fun refreshInstall() { pollInstall() }
    private fun pollInstall() {
        val id = mutable.value.installId ?: return
        if (poll?.isActive == true && pollIdentity == id) return
        poll?.cancel()
        pollIdentity = id
        poll = viewModelScope.launch {
            try {
                while (isActive) {
                    val result = api.call("/plugins/install-state", JSONObject().put("requestId", id))
                    if (mutable.value.installId != id) break
                    val status = result.getString("state")
                    mutable.update { it.copy(installState = status) }
                    if (status != "running") { result.optJSONObject("result")?.let { receipt(it, installResult = true) }; break }
                    delay(1000)
                }
            } catch (e: Exception) { failure(e) }
        }
    }
    fun cancelInstall() = action { val id = mutable.value.installId ?: return@action; val result = api.call("/plugins/cancel", JSONObject().put("requestId", id)); mutable.update { it.copy(receipt = result.getString("status")) }; pollInstall() }
    fun loadAccount() = action { readAccount() }
    private suspend fun readAccount() {
        val result = api.call("/account")
        mutable.update { it.copy(accountAvailable = result.getBoolean("available"), account = result.optJSONObject("view")?.let { v -> AdvancedJson.account(v, it.account, result.optBoolean("runningTasks")) }) }
    }
    fun accountDetails() = action {
        val result = api.call("/account/details", client())
        val profile = result.optJSONObject("profile")
        val balance = result.optJSONObject("balance")
        val data = profile?.optJSONObject("value")
        val wallets = AdvancedJson.objects(balance?.optJSONArray("value")) + AdvancedJson.objects(balance?.optJSONArray("bonusWallets"))
        mutable.update { s -> s.copy(account = s.account?.copy(name = data?.let { AdvancedJson.optional(it, "name") }, contact = data?.let { AdvancedJson.optional(it, "contact") }, balances = wallets.map { "${it.getString("balance")} ${it.getString("currency")}" }, detailsFailed = profile?.optString("status") == "failed" || balance?.optString("status") == "failed")) }
    }
    fun signIn() = action { val result = api.call("/account/sign-in", client()); mutable.update { it.copy(account = AdvancedJson.account(result)) } }
    fun cancelSignIn() = action { val id = mutable.value.account?.attemptId ?: return@action; val result = api.call("/account/cancel", JSONObject().put("attemptId", id)); mutable.update { it.copy(account = AdvancedJson.account(result)) } }
    fun signOut() = action { val result = api.call("/account/sign-out", client()); mutable.update { it.copy(account = AdvancedJson.account(result), receipt = "signed-out") } }
    private fun client() = JSONObject().put("timezoneOffsetSeconds", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
    private fun receipt(value: JSONObject, installResult: Boolean = false) { mutable.update { it.copy(receipt = if (value.optString("application") == "failed" && value.optBoolean("changed")) "saved-failed" else value.optString("application", "unknown"), operationDetails = listOfNotNull(AdvancedJson.optional(value, "restartReason"), value.optJSONObject("error")?.let { e -> AdvancedJson.optional(e, "code") }),
        pendingBuilds = if (installResult) value.optJSONArray("pendingBuilds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() else it.pendingBuilds,
        incompatible = ((if (installResult) emptyList() else it.incompatible) + AdvancedJson.incompatible(value.optJSONObject("error")?.optJSONArray("incompatible")) + AdvancedJson.incompatible(value.optJSONObject("packageResult")?.optJSONArray("incompatible"))).distinctBy { candidate -> candidate.packageVersion to candidate.runtimeVersion }) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) { startAction(block = block) }
    private fun startAction(loading: Boolean = false, block: suspend () -> Unit): Boolean {
        if (request != null) return false
        val task = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { block() } catch (e: Exception) { failure(e) } finally {
                // Observers may immediately submit the next read when busy becomes false.
                request = null
                mutable.update { it.copy(busy = false, loading = false) }
            }
        }
        request = task
        mutable.update { it.copy(busy = true, loading = loading, error = null, receipt = null, inlineReceipt = null) }
        task.start()
        return true
    }
    private fun failure(e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(error = if (e is NativeApiException) e.code else if (e is IllegalArgumentException || e is org.json.JSONException) "NATIVE_ADVANCED_INVALID" else "request_failed") } }
}

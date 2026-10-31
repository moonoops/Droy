package io.droyagent.app.nativeui.settings

import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.data.NativeRuntimeState

data class NativeSettingsProvider(
    val id: String,
    val name: String,
    val settingsNs: String,
    val settingsPath: List<String>,
    val declared: Boolean,
    val profileExists: Boolean,
    val active: Boolean,
    val unavailable: Boolean,
    val defaultBaseUrl: String?,
    val credentialConfigured: Boolean? = null,
    val credentialWritable: Boolean = true,
    val explicitCredentialRef: String? = null,
    val credentialRefs: List<String> = emptyList(),
    val modelCount: Int? = null,
    val protocol: String? = null,
    val oauthOnly: Boolean = false,
    val removed: Boolean = false,
)

data class NativeSettingsModelEntry(
    val id: String,
    val name: String = id,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val inputModalities: List<String>? = null,
    internal val savedJson: String? = null,
    val reasoningJson: String? = null,
    val extraJson: String? = null,
)

/** Editable non-secret fields. API keys never enter this state or saved navigation. */
data class NativeProviderDraft(
    val id: String,
    val settingsNs: String,
    val settingsPath: List<String>,
    val revision: Long,
    val name: String,
    val baseUrl: String,
    val protocol: String?,
    val credentialRef: String,
    val explicitCredentialRef: String?,
    val credentialConfigured: Boolean?,
    val credentialWritable: Boolean,
    val models: List<NativeSettingsModelEntry>,
    val useDefaultModels: Boolean,
    val isNew: Boolean,
    val custom: Boolean,
    val fixedName: Boolean,
    internal val originalName: String = name,
    internal val originalProtocol: String? = protocol,
    val builtinModels: List<NativeSettingsModelEntry> = emptyList(),
    val modelOverridesJson: String = "{}",
    internal val originalOverridesJson: String = modelOverridesJson,
)

data class NativeModelVerification(
    val passed: Boolean,
    val toolsPassed: Boolean,
    val provider: String?,
    val model: String?,
    val checkedAt: String? = null,
)

data class NativeSettingsState(
    val runtime: NativeRuntimeState = NativeRuntimeState(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val writable: Boolean = false,
    val error: String? = null,
    val locale: String = "en",
    val providers: List<NativeSettingsProvider> = emptyList(),
    val catalog: List<NativeModel> = emptyList(),
    val defaultModel: NativeModel? = null,
    val editor: NativeProviderDraft? = null,
    val editorGeneration: Long = 0,
    val credentialPending: Boolean = false,
    val savedEditorId: String? = null,
    val discovering: Boolean = false,
    val discoveredModels: List<NativeSettingsModelEntry> = emptyList(),
    val discoveryComplete: Boolean = false,
    val testing: Boolean = false,
    val verification: NativeModelVerification? = null,
    val verificationUnavailable: Boolean = false,
    val featureSettings: Map<String, String> = emptyMap(),
    val featureSaveReceipt: Long = 0,
) {
    val configuredProviders: List<NativeSettingsProvider>
        get() = providers.filter { !it.removed && (it.profileExists || (it.settingsPath.isEmpty() && it.credentialConfigured == true) || (it.oauthOnly && it.active)) }
    val presets: List<NativeSettingsProvider>
        get() = providers.filterNot { it.declared }
}

package io.droyagent.app.nativeui.settings

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSettingsJsonTest {
    @Test fun staleDefaultNeverCreatesAModelOutsideTheCatalog() {
        val value = JSONObject("""{"default":{"provider":"removed","model":"old"}}""")
        assertNull(NativeSettingsJson.defaultModel(value, listOf(NativeModel("other", "current", "Current"))))
    }

    @Test fun preservedNativeCredentialDoesNotReAddARemovedPiAiProfile() {
        val row = provider().copy(profileExists = false, active = false, credentialConfigured = true)
        assertTrue(NativeSettingsState(providers = listOf(row)).configuredProviders.isEmpty())
    }

    @Test fun builtinDefaultsAreNotAnAddedProviderAndRemovedCatalogCanBeAddedAgain() {
        val directory = JSONObject("""{"active":[{"id":"deepseek-official","name":"DeepSeek"}],"configurable":[{"provider":"deepseek-official","displayName":"DeepSeek","settingsNs":"llm-deepseek","settingsPath":[]}]}""")
        val original = SettingsNamespace("llm-deepseek", 1, JSONObject("""{"models":[{"id":"default"}],"apiKeyEnv":"DEEPSEEK_API_KEY"}"""), JSONObject())
        val providers = NativeSettingsJson.providers(directory, mapOf(original.id to original), JSONObject(), emptyList())
        assertTrue(NativeSettingsState(providers = providers).configuredProviders.isEmpty())
        val removed = original.copy(value = JSONObject("""{"models":[],"apiKeyEnv":"DEEPSEEK_API_KEY"}"""), user = JSONObject("""{"models":[]}"""))
        val row = NativeSettingsJson.providers(directory, mapOf(removed.id to removed), JSONObject(), emptyList()).single()
        assertTrue(row.removed)
        assertTrue(NativeSettingsState(providers = listOf(row)).configuredProviders.isEmpty())
        val draft = NativeSettingsJson.draft(row, removed)
        assertTrue(draft.useDefaultModels)
        val mutation = NativeSettingsJson.mutation(draft, removed, false)!!
        val operation = mutation.getJSONArray("ops").getJSONObject(0)
        assertEquals("unset", operation.getString("op"))
        assertEquals(listOf("models"), operation.getJSONArray("path").strings())
    }

    private val profile = """{"providers":{"relay":{"displayName":"Relay","api":"openai-responses","baseURL":"https://old.invalid/v1","apiKeyEnv":"SHARED_API_KEY","models":[{"id":"model-a","contextWindow":5000,"compat":{"supportsStore":false}}],"headers":{"x-private":"redacted"},"futureField":7}}}"""
    private fun section(value: String = profile, ns: String = "llm-pi-ai", revision: Long = 7) = SettingsNamespace(ns, revision, JSONObject(value), null)
    private fun provider(ns: String = "llm-pi-ai") = NativeSettingsProvider("relay", "Relay", ns,
        if (ns == "llm-deepseek") emptyList() else listOf("providers", "relay"), false, true, true, false,
        null, explicitCredentialRef = "SHARED_API_KEY", credentialConfigured = true)
    private fun draft(section: SettingsNamespace = section()) = NativeSettingsJson.draft(provider(section.id), section)

    @Test fun editingNamePreservesInvisibleFieldsAndExistingCredential() {
        val original = section()
        val request = NativeSettingsJson.mutation(draft(original).copy(name = "Renamed"), original, writeCredential = false)!!
        val operations = request.getJSONArray("ops")
        assertEquals(1, operations.length())
        assertEquals(listOf("providers", "relay", "displayName"), operations.getJSONObject(0).getJSONArray("path").strings())
        assertEquals("Renamed", operations.getJSONObject(0).getString("value"))
        assertFalse(request.toString().contains("redacted"))
        assertEquals("SHARED_API_KEY", original.value.getJSONObject("providers").getJSONObject("relay").getString("apiKeyEnv"))
        assertEquals(7L, request.getLong("expectedRevision"))
    }

    @Test fun unchangedEditorDoesNotRewriteAResolvedOrRedactedNamespace() {
        val original = section()
        assertNull(NativeSettingsJson.mutation(draft(original), original, writeCredential = false))
        val withoutOverrides = section("""{"providers":{"relay":{}}}""")
        val defaulted = provider().copy(defaultBaseUrl = "https://catalog.invalid/v1", explicitCredentialRef = null)
        val editor = NativeSettingsJson.draft(defaulted, withoutOverrides)
        assertEquals("", editor.baseUrl)
        assertNull(NativeSettingsJson.mutation(editor, withoutOverrides, writeCredential = false))
    }

    @Test fun customCreationAndKeyAdmissionHaveSeparatePayloads() {
        val original = section("""{"providers":{}}""")
        val editor = NativeSettingsJson.customDraft(original).copy(name = "Gateway", baseUrl = "https://gateway.invalid/v1",
            protocol = "anthropic-messages", models = listOf(NativeSettingsModelEntry("model-a")))
        val withoutKey = NativeSettingsJson.mutation(editor, original, writeCredential = false)!!.getJSONArray("ops").getJSONObject(0).getJSONObject("value")
        assertFalse(withoutKey.has("apiKeyEnv"))
        val withKey = NativeSettingsJson.mutation(editor, original, writeCredential = true)!!.getJSONArray("ops").getJSONObject(0).getJSONObject("value")
        assertEquals(editor.credentialRef, withKey.getString("apiKeyEnv"))
        assertFalse(withKey.has("apiKey"))
        assertEquals("anthropic-messages", withKey.getString("api"))
        assertNotEquals(editor.id, NativeSettingsJson.customDraft(original).id)
    }

    @Test fun officialDeepSeekKeepsItsOwnNamespaceAndProtocol() {
        val original = section("""{"apiKeyEnv":"SHARED_API_KEY","models":[{"id":"native-model"}],"thinking":"enabled","maxTokens":4096}""", "llm-deepseek")
        val editor = draft(original).copy(baseUrl = "https://native.invalid/anthropic")
        val request = NativeSettingsJson.mutation(editor, original, writeCredential = true)!!
        assertEquals("llm-deepseek", request.getString("ns"))
        val operations = request.getJSONArray("ops")
        assertEquals(1, operations.length())
        assertEquals(listOf("baseURL"), operations.getJSONObject(0).getJSONArray("path").strings())
        assertFalse(request.toString().contains("displayName"))
        assertFalse(request.toString().contains("\"api\""))
        assertEquals("enabled", original.value.getString("thinking"))
    }

    @Test fun modelEditsPreserveExistingCapabilitiesAndDoNotInventNewOnes() {
        val original = section()
        val editor = draft(original).copy(models = draft(original).models + NativeSettingsModelEntry("manual-model"))
        val request = NativeSettingsJson.mutation(editor, original, false)!!
        val models = request.getJSONArray("ops").getJSONObject(0).getJSONArray("value")
        assertEquals(5000, models.getJSONObject(0).getInt("contextWindow"))
        assertFalse(models.getJSONObject(0).getJSONObject("compat").getBoolean("supportsStore"))
        assertEquals("manual-model", models.getJSONObject(1).getString("id"))
        assertFalse(models.getJSONObject(1).has("contextWindow"))
        assertFalse(models.getJSONObject(1).has("input"))
        val discovered = NativeSettingsJson.discovered(JSONArray("""[{"id":"vision","inputModalities":["text","image"]},{"id":"vision"}]"""))
        assertEquals(1, discovered.size)
        assertEquals(listOf("text", "image"), NativeSettingsJson.modelValue(discovered.single(), "llm-pi-ai").getJSONArray("input").strings())
        assertTrue(NativeSettingsJson.modelValue(discovered.single(), "llm-deepseek").has("inputModalities"))
    }

    @Test fun anEditorKeepsItsOriginalRevisionWhenAnotherReadIsNewer() {
        val old = section()
        val editor = draft(old).copy(name = "Changed")
        val refreshed = section(revision = 8)
        assertEquals(7L, NativeSettingsJson.mutation(editor, refreshed, false)!!.getLong("expectedRevision"))
        val collision = NativeSettingsJson.customDraft(section("""{"providers":{}}""")).copy(name = "Gateway", baseUrl = "https://gateway.invalid", models = listOf(NativeSettingsModelEntry("m")))
        val occupied = section(JSONObject().put("providers", JSONObject().put(collision.id, JSONObject())).toString())
        assertCode("settings/conflict") { NativeSettingsJson.mutation(collision, occupied, false) }
    }

    @Test fun directoryPathsAndCredentialMetadataStaySeparateFromSecretValues() {
        val directory = JSONObject("""{"active":[{"id":"relay","name":"Relay"}],"configurable":[{"provider":"relay","displayName":"Relay","settingsNs":"llm-pi-ai","settingsPath":["providers","relay"]}]}""")
        val metadata = JSONObject("""{"SHARED_API_KEY":{"configured":true,"writable":true,"source":"fixture","value":"fictional-secret-not-for-ui"}}""")
        val rows = NativeSettingsJson.providers(directory, mapOf("llm-pi-ai" to section()), metadata, emptyList(), setOf("relay"))
        assertEquals(true, rows.single().credentialConfigured)
        assertEquals(listOf("SHARED_API_KEY"), rows.single().credentialRefs)
        assertTrue(rows.single().unavailable)
        assertFalse(rows.toString().contains("fictional-secret-not-for-ui"))
        assertEquals(listOf("SHARED_API_KEY"), NativeSettingsJson.credentialRefs(directory, mapOf("llm-pi-ai" to section())))
    }

    @Test fun verificationPreservesItsReceiptIdentityAndPartialToolResult() {
        val receipt = NativeSettingsJson.verification(JSONObject("""{"selection":{"provider":"new","model":"new-model"},"modelVerification":{"passed":true,"toolsPassed":false,"provider":"old","model":"old-model","checkedAt":123}}"""))!!
        assertTrue(receipt.passed)
        assertFalse(receipt.toolsPassed)
        assertEquals("old", receipt.provider)
        assertEquals("old-model", receipt.model)
    }

    @Test fun invalidKeyEndpointAndModelInputsAreRejectedBeforeCommit() {
        for (key in listOf("NAME=value", "\"wrapped\"", "fictional\nvalue", "含中文"))
            assertCode("settings_invalid_key") { NativeSettingsJson.validateKey(key) }
        NativeSettingsJson.validateKey("  fictional-token  ")
        NativeSettingsJson.validateKey("")
        val editor = draft().copy(custom = true)
        assertCode("settings_invalid_endpoint") { NativeSettingsJson.validate(editor.copy(baseUrl = "https://user:password@fixture.invalid")) }
        assertCode("settings_invalid_models") { NativeSettingsJson.validate(editor.copy(models = listOf(NativeSettingsModelEntry("with space")))) }
        assertCode("settings_invalid_models") { NativeSettingsJson.validate(editor.copy(models = listOf(NativeSettingsModelEntry("duplicate"), NativeSettingsModelEntry("duplicate")))) }
    }

    @Test fun advancedModelEditingMergesKnownFieldsWithoutDroppingUnknownCapabilities() {
        val source = JSONObject("""{"id":"m","name":"Original","contextWindow":2000,"maxTokens":300,"input":["text"],"reasoningEfforts":{"off":null,"high":"provider-high"},"compat":{"supportsStore":false},"futureCapability":{"value":7}}""")
        val edited = NativeSettingsJson.model(source).copy(name = "Renamed", contextWindow = 8000, maxTokens = null,
            inputModalities = listOf("text", "image"), reasoningJson = "false")
        val saved = NativeSettingsJson.modelValue(edited, "llm-pi-ai")
        assertEquals("Renamed", saved.getString("name"))
        assertEquals(8000, saved.getInt("contextWindow"))
        assertFalse(saved.has("maxTokens"))
        assertFalse(saved.getBoolean("reasoningEfforts"))
        assertEquals(7, saved.getJSONObject("futureCapability").getInt("value"))
        assertFalse(saved.getJSONObject("compat").getBoolean("supportsStore"))
        assertEquals(listOf("text", "image"), NativeSettingsJson.model(saved).inputModalities)
    }

    @Test fun discoveryRetainsReasoningWireMappingsAndMissingValuesRemainInherited() {
        val discovered = NativeSettingsJson.discovered(JSONArray("""[{"id":"m","reasoningEfforts":{"off":null,"high":"deep"},"futureCapability":true}]""")).single()
        val roundtrip = NativeSettingsJson.modelValue(discovered, "llm-pi-ai")
        assertTrue(roundtrip.getJSONObject("reasoningEfforts").isNull("off"))
        assertEquals("deep", roundtrip.getJSONObject("reasoningEfforts").getString("high"))
        assertTrue(roundtrip.getBoolean("futureCapability"))
        assertFalse(roundtrip.has("contextWindow"))
        assertFalse(roundtrip.has("maxTokens"))
        assertNull(discovered.inputModalities)
        assertFalse(roundtrip.has("input"))
        assertFalse(roundtrip.has("inputModalities"))
    }

    @Test fun schemaMaterializedEmptyPiInputRemainsInheritedWhenEditingAndSaving() {
        val model = NativeSettingsJson.model(JSONObject("""{"id":"m","input":[],"futureCapability":true}"""))
        assertNull(model.inputModalities)
        val saved = NativeSettingsJson.modelValue(model.copy(name = "Renamed"), "llm-pi-ai")
        assertNull(NativeSettingsJson.model(saved).inputModalities)
        assertTrue(saved.getBoolean("futureCapability"))
        assertEquals(listOf("text", "image"), NativeSettingsJson.model(
            JSONObject("""{"id":"m","input":["text","image"]}""")).inputModalities)
        val explicit = NativeSettingsJson.modelValue(model.copy(inputModalities = listOf("text")), "llm-pi-ai")
        assertEquals(listOf("text"), NativeSettingsJson.model(explicit).inputModalities)
        val inherited = NativeSettingsJson.modelValue(NativeSettingsJson.model(explicit).copy(inputModalities = null), "llm-pi-ai")
        assertFalse(inherited.has("input"))
    }

    @Test fun builtinOverrideDoesNotReplaceTheProvidersModelList() {
        val original = section("""{"providers":{"relay":{"modelOverrides":{"keep":{"name":"Keep me"}}}}}""")
        val editor = draft(original).copy(modelOverridesJson = """{"keep":{"name":"Keep me"},"m":{"contextWindow":9000}}""")
        val ops = NativeSettingsJson.mutation(editor, original, false)!!.getJSONArray("ops")
        assertEquals(1, ops.length())
        assertEquals(listOf("providers", "relay", "modelOverrides"), ops.getJSONObject(0).getJSONArray("path").strings())
        assertEquals("Keep me", ops.getJSONObject(0).getJSONObject("value").getJSONObject("keep").getString("name"))
        assertTrue(editor.useDefaultModels)
    }

    @Test fun oauthOnlyProviderDoesNotPretendToUseAnApiKey() {
        val directory = JSONObject("""{"active":[],"configurable":[{"provider":"login-only","displayName":"Login","settingsNs":"llm-pi-ai","settingsPath":["providers","login-only"],"nativeAuth":{"env":[],"oauth":true}}]}""")
        val provider = NativeSettingsJson.providers(directory, mapOf("llm-pi-ai" to section("""{"providers":{}}""")), JSONObject(), emptyList()).single()
        assertTrue(provider.oauthOnly)
        assertNull(provider.credentialConfigured)
        assertTrue(provider.credentialRefs.isEmpty())
    }

    @Test fun explicitAdvancedInheritClearsOnlyTheSelectedOverride() {
        val original = NativeSettingsJson.model(JSONObject("""{"id":"m","compat":{"supportsStore":false},"imageMaxBytes":9000,"unknown":{"keep":true}}"""))
        val cleared = NativeSettingsJson.modelValue(original.copy(extraJson = """{"compat":null}"""), "llm-pi-ai")
        assertFalse(cleared.has("compat"))
        assertEquals(9000, cleared.getInt("imageMaxBytes"))
        assertTrue(cleared.getJSONObject("unknown").getBoolean("keep"))
    }

    @Test fun missingCatalogDoesNotClaimZeroModelsAndKnownCatalogCountsDistinctRoutes() {
        val directory = JSONObject("""{"active":[{"id":"relay","name":"Relay"}],"configurable":[{"provider":"relay","displayName":"Relay","settingsNs":"llm-pi-ai","settingsPath":["providers","relay"]}]}""")
        val sections = mapOf("llm-pi-ai" to section())
        val unresolved = NativeSettingsJson.providers(directory, sections, JSONObject(), emptyList()).single()
        assertNull(unresolved.modelCount)
        val catalog = listOf(NativeModel("relay", "a", "A"), NativeModel("relay", "a", "A"),
            NativeModel("relay", "b", "B"), NativeModel("other", "c", "C"))
        assertEquals(2, NativeSettingsJson.providers(directory, sections, JSONObject(), catalog).single().modelCount)
    }

    private fun assertCode(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: NativeApiException) { assertEquals(code, error.code) }
    }
}

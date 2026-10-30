package io.droyagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsClient
import io.droyagent.app.nativeui.data.NativeApiClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit phone review setup. Adds only a fictional loopback provider; never uses an existing key. */
@RunWith(AndroidJUnit4::class)
class NativePhoneReviewProviderDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Removes this review's synthetic route only. Root separately restores the recorded default while stopped. */
    @Test fun removeOnlyTheRecordedLocalReviewProviderAndCredential() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("nativePhoneLocalProviderCleanup") == "true") { "Explicit cleanup argument required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.droyagent.app.dev") { "Development package required" }
        val provider = "provider-aa1fdba1-ef3b-48a1-b688-025bc0dd02a5"
        val ref = "DROY_PROVIDER_AA1FDBA1_EF3B_48A1_B688_025BC0DD02A5_API_KEY"
        val markerFile = File(context.getExternalFilesDir(null), "native-review-provider.json")
        check(markerFile.isFile) { "Review recovery marker required" }
        val marker = JSONObject(markerFile.readText())
        check(marker.optString("provider") == provider && marker.optString("originalProvider") == "deepseek-official" &&
            marker.optString("originalModel") == "deepseek-flash") { "Review recovery marker mismatch" }
        ActivityScenario.launch(NativeAppActivity::class.java).use {
            compose.waitUntil(60_000) { RuntimeService.ready && NativeRuntimeHost.inspect(context).ready }
            suspend fun requireIdle() {
                check(RuntimeService.ready && NativeRuntimeHost.inspect(context).ready) { "Runtime must remain ready" }
                val sessions = (NativeApiClient().post("list") as JSONObject).getJSONArray("items")
                check((0 until sessions.length()).none { sessions.getJSONObject(it).getBoolean("running") }) { "Wait until all sessions are idle" }
                val progress = ToolProgress.current(context)
                check(!progress.getBoolean("active") && progress.isNull("pending")) { "Wait until device tasks are idle" }
                check(AudioCapture.get(context).uiStatus().isNull("active")) { "Wait until recording has stopped" }
            }
            requireIdle()
            val api = NativeSettingsClient()
            val before = api.describe()
            check(before.getBoolean("writable")) { "Settings must be writable" }
            fun section(description: JSONObject): JSONObject {
                val rows = description.getJSONArray("namespaces")
                return (0 until rows.length()).map(rows::getJSONObject).single { it.getString("ns") == "llm-pi-ai" }
            }
            val settings = section(before)
            val providers = settings.getJSONObject("value").getJSONObject("providers")
            val fixture = providers.getJSONObject(provider)
            check(fixture.optString("displayName") == "DroyLocalReview" && fixture.optString("baseURL") == "http://127.0.0.1:18764/v1" &&
                fixture.optString("apiKeyEnv") == ref && fixture.optString("api") == "openai-completions") { "Synthetic provider identity mismatch" }
            val models = fixture.getJSONArray("models")
            check(models.length() == 1 && models.getJSONObject(0).optString("id") == "droy-review-local") { "Synthetic model identity mismatch" }
            fun countReference(value: Any?): Int = when (value) {
                is JSONObject -> value.keys().asSequence().sumOf { countReference(value.opt(it)) }
                is JSONArray -> (0 until value.length()).sumOf { countReference(value.opt(it)) }
                else -> if (value == ref) 1 else 0
            }
            val namespaces = before.getJSONArray("namespaces")
            check((0 until namespaces.length()).sumOf { countReference(namespaces.getJSONObject(it).getJSONObject("value")) } == 1) {
                "Synthetic credential must not be shared"
            }
            val previousDefault = api.catalog().getJSONObject("default")
            check((previousDefault.optString("provider") == provider && previousDefault.optString("model") == "droy-review-local") ||
                (previousDefault.optString("provider") == "deepseek-official" && previousDefault.optString("model") == "deepseek-flash")) {
                "Default changed outside this review"
            }
            val receiptFile = File(context.getExternalFilesDir(null), "native-review-provider-cleanup.json")
            val receipt = JSONObject().put("provider", provider).put("credentialRef", ref)
                .put("originalProvider", marker.getString("originalProvider")).put("originalModel", marker.getString("originalModel"))
                .put("previousProvider", previousDefault.getString("provider")).put("previousModel", previousDefault.getString("model"))
                .put("defaultRestored", false)
            fun record(stage: String) {
                receipt.put("stage", stage)
                val temporary = File(receiptFile.parentFile, "${receiptFile.name}.tmp")
                temporary.writeText(receipt.toString())
                check(temporary.renameTo(receiptFile)) { "Cleanup receipt could not be saved" }
            }
            requireIdle()
            record("prepared")
            check(api.clearCredential(ref).getBoolean("committed")) { "Credential deletion was not committed" }
            check(!api.metadata(listOf(ref)).getJSONObject(ref).getBoolean("configured")) { "Synthetic credential remains configured" }
            record("credential-removed")
            api.mutate(JSONObject().put("ns", "llm-pi-ai").put("expectedRevision", settings.getLong("revision"))
                .put("ops", JSONArray().put(JSONObject().put("op", "unset").put("path", JSONArray(listOf("providers", provider))))))
            val after = section(api.describe()).getJSONObject("value")
            check(!after.getJSONObject("providers").has(provider)) { "Synthetic provider remains configured" }
            fun canonical(value: Any?): Any? = when (value) {
                is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.opt(it)) }
                is JSONArray -> (0 until value.length()).map { canonical(value.opt(it)) }
                else -> value
            }
            val expected = JSONObject(settings.getJSONObject("value").toString()).apply { getJSONObject("providers").remove(provider) }
            check(canonical(expected) == canonical(after)) { "Unrelated provider settings changed" }
            record("removed")
        }
    }

    @Test fun configureAndVerifyOnlyTheLocalReviewProvider() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("nativePhoneLocalProvider"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.droyagent.app.dev", context.packageName)
        ActivityScenario.launch(NativeAppActivity::class.java).use { scenario ->
            lateinit var model: NativeSettingsViewModel
            scenario.onActivity { model = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
            compose.waitUntil(30_000) { model.state.value.loaded && model.state.value.writable }
            assertFalse(model.state.value.providers.any { it.name == "DroyLocalReview" })
            val previous = model.state.value.defaultModel
            compose.onNodeWithTag("native-settings-add-provider").performClick()
            compose.onNodeWithTag("native-settings-custom-provider").performClick()
            compose.waitUntil(10_000) { model.state.value.editor != null }
            val provider = requireNotNull(model.state.value.editor).id
            compose.onNodeWithTag("native-settings-provider-name").performTextInput("DroyLocalReview")
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-provider-base-url"))
            compose.onNodeWithTag("native-settings-provider-base-url").performTextInput("http://127.0.0.1:18764/v1")
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-provider-key"))
            compose.onNodeWithTag("native-settings-provider-key").performTextInput("fictional-review-key")
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-fetch-models"))
            compose.onNodeWithTag("native-settings-fetch-models").performClick()
            compose.waitUntil(20_000) { model.state.value.discoveryComplete || model.state.value.error != null }
            assertNull(model.state.value.error)
            assertEquals(listOf("droy-review-local"), model.state.value.discoveredModels.map { it.id })
            compose.onNodeWithTag("native-settings-discovered:droy-review-local").performClick()
            compose.onNodeWithTag("native-settings-add-discovered").performClick()
            compose.onNodeWithTag("native-settings-save-provider").performClick()
            compose.waitUntil(20_000) { !model.state.value.saving && model.state.value.savedEditorId == provider }
            assertNull(model.state.value.error)
            File(context.getExternalFilesDir(null), "native-review-provider.json").writeText(JSONObject()
                .put("provider", provider).put("originalProvider", previous?.provider ?: JSONObject.NULL)
                .put("originalModel", previous?.id ?: JSONObject.NULL).toString())
            compose.onNodeWithTag("native-settings-default-model").performClick()
            compose.onNodeWithTag("native-settings-default:$provider:droy-review-local").performClick()
            compose.waitUntil(15_000) { !model.state.value.saving && model.state.value.defaultModel?.provider == provider }
            assertEquals("droy-review-local", model.state.value.defaultModel?.id)
            compose.onNodeWithTag("native-settings-test-model").performClick()
            compose.onNodeWithTag("native-settings-confirm-test").performClick()
            compose.waitUntil(30_000) { !model.state.value.testing && (model.state.value.verification != null || model.state.value.error != null) }
            assertNull(model.state.value.error)
            assertTrue(requireNotNull(model.state.value.verification).passed)
            assertTrue(requireNotNull(model.state.value.verification).toolsPassed)
        }
    }
}

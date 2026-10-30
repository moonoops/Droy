package io.droyagent.app

import android.Manifest
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.engine.*
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Real local DSH through the shared isolated fixture; the only prompt uses its controlled local provider. */
@RunWith(AndroidJUnit4::class)
class NativeEngineRuntimeDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-engine-failed-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var engine: NativeEngineViewModel

    @Test fun coldCatalogDefaultsAreDurableAndIndependentOfAndroidAuthorization() {
        fixture.startRuntime()
        val before = ordinarySessions()
        val android = androidFacts()
        val root = engineRequest()
        assertTrue(root.getJSONObject("presets").getBoolean("available"))
        assertTrue(root.getJSONObject("permissions").getBoolean("available"))
        val roster = rows(root.getJSONObject("presets").getJSONArray("options"))
        assertTrue(roster.any { it.getString("id") == "standard" })
        assertTrue(roster.any { it.getString("id") == "cordis" && !it.getBoolean("broken") })
        assertEquals(before, ordinarySessions())
        val selected = engineRequest("/presets/default", JSONObject().put("id", "cordis")
            .put("expectedRevision", root.getJSONObject("presets").getJSONObject("setting").getLong("revision")))
        assertEquals("cordis", selected.getJSONObject("presets").getString("defaultId"))
        assertEquals("cordis", engineRequest().getJSONObject("presets").getString("defaultId"))
        val permission = selected.getJSONObject("permissions")
        assertTrue(rows(permission.getJSONArray("defaultOptions")).any { it.getString("value") == "danger-full-access" })
        engineRequest("/permissions/default", JSONObject().put("id", "danger-full-access").put("acceptRisk", true)
            .put("expectedRevision", permission.getJSONObject("setting").getLong("revision")))
        assertEquals("danger-full-access", engineRequest().getJSONObject("permissions").getString("defaultId"))
        assertEquals("Catalog and new-session defaults must not create an ordinary session", before, ordinarySessions())
        assertEquals("DSH policy is not Android consent", android, androidFacts())
    }

    @Test fun unsentDraftSelectionDoesNotCreateASessionAndTheFirstPromptUsesTheCapturedChoices() {
        startModels()
        val before = ordinarySessions(); val android = androidFacts()
        val draftId = work.state.value.draftId
        chooseCreatorAndPermission()
        assertEquals("cordis", engine.state.value.draftSelection!!.agentPresetId)
        assertEquals(draftId, engine.state.value.draftSelection!!.draftId)
        assertEquals(before, ordinarySessions())
        val marker = "NATIVE_ENGINE_CAPTURED_FIRST_SEND"
        fixture.scenario.onActivity { work.setDraft(marker) }
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        awaitReply(marker)
        val id = requireNotNull(work.state.value.selectedSessionId)
        val current = engineRequest("/session", JSONObject().put("sessionId", id))
        assertEquals("cordis", current.getString("agentPreset"))
        assertEquals(chosenPermission, current.getString("permission"))
        assertEquals(1, calls(marker).size)
        assertPresetBeforePrompt(id, marker)
        assertEquals(android, androidFacts())
        fixture.capture("native-engine-first-captured-prompt")
    }

    @Test fun recreationAndExplicitRetryReuseThePendingTokenAfterAnUncertainPreparationReceipt() {
        startModels(); chooseCreatorAndPermission()
        val draftId = work.state.value.draftId
        val first = AtomicBoolean(true)
        fixture.scenario.onActivity {
            work.bindSessionPreparation(engine::captureSelection) { sessionId, token ->
                engine.prepareSession(sessionId, token)
                if (first.getAndSet(false)) throw NativeApiException("fixture_lost_prepare_receipt")
            }
        }
        val marker = "NATIVE_ENGINE_CAPTURED_RETRY"
        fixture.scenario.onActivity { work.setDraft(marker) }
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { !work.state.value.sending && work.state.value.error != null && work.state.value.selectedSessionId != null }
        val sessionId = requireNotNull(work.state.value.selectedSessionId)
        val pending = requireNotNull(pending(sessionId))
        assertFalse(pending.getBoolean("sessionPrepared"))
        assertTrue(pending.getBoolean("sessionPreparationAttempted"))
        val frozen = JSONObject(pending.getJSONObject("sessionPreparation").toString())
        assertEquals("cordis", frozen.getString("agentPresetId"))
        assertEquals(0, calls(marker).size)
        fixture.scenario.onActivity { engine.stagePreset(draftId, "standard") }
        fixture.recreate(); models()
        compose.waitUntil(20_000) { engine.state.value.runtime.ready && work.state.value.runtime.ready }
        val restored = requireNotNull(pending(sessionId))
        assertSelectionEquals(frozen, restored.getJSONObject("sessionPreparation"))
        assertEquals("standard", staged(draftId).getString("agentPresetId"))
        fixture.scenario.onActivity { work.retryPending() }
        awaitReply(marker)
        val current = engineRequest("/session", JSONObject().put("sessionId", sessionId))
        assertEquals("cordis", current.getString("agentPreset")); assertEquals(chosenPermission, current.getString("permission"))
        assertEquals(1, calls(marker).size)
        assertPresetBeforePrompt(sessionId, marker)
        fixture.capture("native-engine-recreated-token-retry")
    }

    @Test fun frozenOriginalDraftCaptureDoesNotReadTheCurrentlyDisplayedDraftOrCreateASession() {
        startModels()
        val original = work.state.value.draftId; val other = "fixture-other-${UUID.randomUUID()}"
        val before = ordinarySessions()
        var captured: JSONObject? = null
        fixture.scenario.onActivity {
            engine.stagePreset(original, "cordis")
            engine.bindDraft(other); engine.stagePreset(other, "standard")
            captured = engine.captureSelection(original)
            assertEquals(other, engine.state.value.draftId)
            assertEquals("standard", engine.state.value.draftSelection?.agentPresetId)
        }
        assertEquals(original, requireNotNull(captured).getString("draftId"))
        assertEquals("cordis", requireNotNull(captured).getString("agentPresetId"))
        fixture.scenario.onActivity { assertNull(engine.captureSelection("fixture-never-staged")) }
        assertEquals(before, ordinarySessions())
    }

    @Test fun aRealBlankSessionExposesItsScopedSkillsAndUnknownContextWithoutCallingCompact() {
        startModels()
        val id = "native-engine-cold-${UUID.randomUUID()}"
        val android = androidFacts()
        val defaults = engineRequest().getJSONObject("presets").getString("defaultId")
        val created = sessionRequest("create", JSONObject().put("sessionId", id).put("agentPreset", "cordis"))
        assertEquals(id, created.getString("sessionId"))
        val current = engineRequest("/session", JSONObject().put("sessionId", id))
        assertTrue(current.getBoolean("fresh")); assertTrue(current.getBoolean("presetSelectable"))
        fixture.scenario.onActivity { work.selectSession(id) }
        compose.waitUntil(20_000) { engine.state.value.sessionId == id && engine.state.value.session?.fresh == true && !engine.state.value.sessionLoading }
        openTaskType()
        taskTypeDefault().assertIsDisplayed().assertIsEnabled()
        closeSheet()
        val selected = engineRequest("/presets/select", JSONObject().put("sessionId", id).put("id", "standard"))
        assertEquals(id, selected.getString("sessionId")); assertEquals("standard", selected.getString("agentPreset"))
        assertTrue(selected.getBoolean("presetSelectable"))
        val restored = engineRequest("/presets/select", JSONObject().put("sessionId", id).put("id", "cordis"))
        assertEquals("cordis", restored.getString("agentPreset")); assertTrue(restored.getBoolean("presetSelectable"))
        assertEquals(defaults, engineRequest().getJSONObject("presets").getString("defaultId"))
        val context = current.optJSONObject("context")
        assertTrue(context == null || !context.has("pressureTokens"))
        assertTrue(context == null || !context.has("projectedTokens"))
        val catalog = engineRequest("/skills", JSONObject().put("sessionId", id))
        assertEquals(id, catalog.getString("sessionId"))
        val skills = rows(catalog.getJSONArray("skills"))
        assertTrue("Creator must expose its composed skill catalog", skills.isNotEmpty())
        val file = skills.firstOrNull { it.has("path") }
        if (file != null) {
            val text = native("/droy/native/tools/files/text", JSONObject().put("sessionId", id).put("path", file.getString("path"))
                .put("range", JSONObject().put("offset", 1).put("limit", 20)))
            assertTrue(text.getString("text").isNotEmpty()); assertTrue(text.getInt("lines") > 0)
        }
        assertEquals("No prompt or compaction is dispatched by discovery", 0, fixtureCalls().count { it.optString("sessionId") == id })
        assertEquals(android, androidFacts())
    }

    @Test fun actualAppPermissionDialogsFollowTheSavedLanguageWithoutChangingAnyPermissionChoice() {
        startModels()
        lateinit var settings: NativeSettingsViewModel
        fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        compose.waitUntil(20_000) { settings.state.value.loaded && settings.state.value.writable && !settings.state.value.loading }
        val sessions = ordinarySessions(); val android = androidFacts()
        val selected = engine.state.value.draftSelection
        val defaultPermission = engineRequest().getJSONObject("permissions").getString("defaultId")
        for (language in listOf("zh", "en")) {
            fixture.scenario.onActivity { settings.setLanguage(language) }
            compose.waitUntil(20_000) { !settings.state.value.saving && settings.state.value.locale == language
                && work.state.value.runtime.locale == language && engine.state.value.runtime.locale == language && engine.state.value.fresh }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-permission").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithText(localized(language, R.string.native_engine_full_access)).assertIsDisplayed().performClick()
            compose.onNodeWithText(localized(language, R.string.native_engine_permission_confirm)).assertIsDisplayed()
            compose.onNodeWithText(localized(language, R.string.native_engine_full_risk)).assertIsDisplayed()
            compose.onNodeWithText(localized(language, R.string.native_engine_enable_full)).assertIsNotEnabled()
            fixture.capture("native-engine-real-risk-$language")
            compose.onNodeWithText(localized(language, R.string.native_engine_cancel)).performClick()
            compose.onNodeWithText(localized(language, R.string.native_engine_permission_confirm)).assertDoesNotExist()
            assertEquals(selected, engine.state.value.draftSelection)
        }
        assertEquals(defaultPermission, engineRequest().getJSONObject("permissions").getString("defaultId"))
        assertEquals(sessions, ordinarySessions()); assertEquals(android, androidFacts())
        assertTrue(fixtureCalls().isEmpty())
    }

    private var chosenPermission = ""
    private fun startModels() {
        fixture.startRuntime(); models()
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaces.isNotEmpty() && engine.state.value.fresh && !engine.state.value.loading }
        compose.waitUntil(15_000) { work.state.value.draftId.isNotBlank() && engine.state.value.draftId == work.state.value.draftId }
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        compose.onNodeWithTag("native-model").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("native-permission").assertIsDisplayed().assertIsEnabled()
    }
    private fun models() { fixture.scenario.onActivity { activity ->
        work = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java]
        engine = ViewModelProvider(activity)[NativeEngineViewModel::class.java]
    } }
    private fun chooseCreatorAndPermission() {
        val originalDefault = engineRequest().getJSONObject("presets").getString("defaultId")
        // The current task-type sheet exposes the configured default. Choose Cordis through
        // that real entry, then change the global default back before testing draft capture.
        setDefaultPreset("cordis")
        openTaskType()
        taskTypeDefault().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { engine.state.value.draftSelection?.agentPresetId == "cordis" }
        setDefaultPreset(originalDefault)
        assertEquals("Restoring the global default must preserve the staged task type", "cordis", engine.state.value.draftSelection?.agentPresetId)
        val catalog = requireNotNull(engine.state.value.catalog)
        val selected = catalog.permissions.first { it.id == if (catalog.defaultPermission == "workspace-write") "danger-full-access" else "workspace-write" }
        chosenPermission = selected.id
        compose.onNodeWithTag("native-permission").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText(permissionLabel(selected)).assertIsDisplayed().performClick()
        if (selected.needsRiskConfirmation) {
            val confirm = fixture.context.getString(if (selected.id == "danger-full-access") R.string.native_engine_enable_full else R.string.native_engine_confirm)
            compose.onNodeWithText(confirm).assertIsNotEnabled()
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText(confirm).assertIsEnabled().performClick()
        }
        compose.waitUntil(10_000) { engine.state.value.draftSelection?.permissionPresetId == selected.id }
    }
    private fun setDefaultPreset(id: String) {
        val presets = engineRequest().getJSONObject("presets")
        if (presets.getString("defaultId") != id) engineRequest("/presets/default", JSONObject().put("id", id)
            .put("expectedRevision", presets.getJSONObject("setting").getLong("revision")))
        fixture.scenario.onActivity { engine.onAction(NativeEngineAction.Refresh) }
        compose.waitUntil(20_000) { engine.state.value.fresh && !engine.state.value.loading && engine.state.value.catalog?.defaultPreset == id }
    }
    private fun openTaskType() {
        compose.onNodeWithTag("native-workbench-menu").assertIsDisplayed().performClick()
        compose.onNodeWithText(fixture.context.getString(R.string.native_task_type)).assertIsDisplayed().performClick()
    }
    private fun taskTypeDefault() = compose.onNode(hasText(fixture.context.getString(R.string.native_task_default)) and
        hasClickAction() and hasAnyAncestor(isDialog()) and
        hasAnySibling(hasText(fixture.context.getString(R.string.native_task_type))))
    private fun closeSheet() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun permissionLabel(row: NativeDshPermission): String = if (row.name != row.id) row.name else fixture.context.getString(
        if (row.id == "workspace-write") R.string.native_engine_workspace_write else R.string.native_engine_full_access)
    private fun localized(language: String, resource: Int): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(resource)
    }
    private fun awaitReply(marker: String) {
        compose.waitUntil(45_000) { !work.state.value.running && !work.state.value.sending && work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming } }
        assertEquals(1, work.state.value.messages.count { it.role == "user" && it.text == marker })
        assertNull(work.state.value.error)
    }
    private fun pending(sessionId: String): JSONObject? = fixture.context.getSharedPreferences("native_workbench", 0).getString("pending:$sessionId", null)?.let(::JSONObject)
    private fun staged(draftId: String): JSONObject = JSONObject(requireNotNull(fixture.context.getSharedPreferences("native-engine-drafts", 0)
        .getString("draft-selections-v1", null))).getJSONObject("drafts").getJSONObject(draftId)
    private fun androidFacts(): Pair<Map<String, Any?>, Map<String, Int>> {
        val preferences = ProductPermissions.preferences(fixture.context)
        val keys = SetupManager.capabilityKeys(fixture.context).toSet() + ProductPermissions.STOPPED + "nativePermissionAuthorizationPolicy" + "nativePermissionExplicitPause"
        return keys.associateWith { preferences.all[it] } to listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS).associateWith(fixture.context::checkSelfPermission)
    }
    private fun ordinarySessions(): Set<String> {
        val root = sessionRequest("list", JSONObject())
        val managed = root.getJSONArray("managedSessionIds").let { a -> (0 until a.length()).map(a::getString).toSet() }
        return rows(root.getJSONArray("items")).map { it.getString("sessionId") }.filterNot { it in managed }.toSet()
    }
    private fun assertPresetBeforePrompt(sessionId: String, marker: String) {
        val snapshot = engineRequest("/session", JSONObject().put("sessionId", sessionId))
        val page = sessionRequest("page", JSONObject().put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", snapshot.getLong("asOfSeq")).put("maxMessages", 100))
        val events = rows(page.getJSONArray("records")).mapNotNull { if (it.optString("type") == "event") it.optJSONObject("event") else null }
        val preset = events.first { it.optString("type") == "agent-preset/selected" && it.optJSONObject("data")?.optString("agentPreset") == "cordis" }
        val prompt = events.first { event -> event.optString("type") == "user/message" && rows(event.getJSONObject("data").optJSONArray("content") ?: JSONArray()).any { it.optString("text") == marker } }
        assertTrue("The preset must be selected before prompt admission", preset.getLong("seq") < prompt.getLong("seq"))
    }
    private fun assertSelectionEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
        expected.keys().forEach { key -> assertEquals("Captured $key must survive recreation", expected.get(key), actual.get(key)) }
    }
    private fun fixtureCalls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.map(::JSONObject) ?: emptyList()
    private fun calls(marker: String) = fixtureCalls().filter { !it.optBoolean("title") && it.optString("prompt") == marker }
    private fun engineRequest(path: String = "", body: JSONObject? = null) = native("/droy/native/engine$path", body)
    private fun sessionRequest(method: String, body: JSONObject) = native("/droy/native/session/$method", body)
    private fun native(path: String, body: JSONObject? = null): JSONObject = runBlocking {
        val envelope = fixture.runtimeTransport.json(path, if (body == null) "GET" else "POST", body)
        assertTrue("The local native API must confirm its result", envelope.getBoolean("ok"))
        envelope.getJSONObject("result")
    }
    private fun rows(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
}

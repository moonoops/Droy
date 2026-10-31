package io.droyagent.app

import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.*
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.standardvoice.*
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native routes and authenticated DSH. No microphone grant, capture, TTS or external model. */
@RunWith(AndroidJUnit4::class)
class NativeStandardVoiceDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var voice: NativeStandardVoiceViewModel
    private lateinit var settings: NativeSettingsViewModel

    @Test fun inlineVoiceUsesBothLanguagesAndKeepsTheDraftWhenProductAccessIsDenied() {
        start()
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putBoolean("allowAudioMicrophone", false)
            .putBoolean("allowAudioTranscription", false).commit())
        val draft = "STANDARD_VOICE_ORDINARY_DRAFT"
        fixture.scenario.onActivity { work.setDraft(draft) }
        val before = sessions()
        // The retired entry preference must not hide the always-available inline conversation.
        voiceRequest("save_config", JSONObject().put("patch", JSONObject().put("entries", JSONObject().put("standard", false))))
        compose.onNodeWithTag("native-workbench-menu").performClick()
        compose.onNodeWithText(text("en", R.string.nsv_title)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-standard-voice-panel").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-root-navigation").assertIsDisplayed()
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        compose.onNodeWithTag("native-standard-voice-start").assertIsEnabled().performClick()
        compose.onNodeWithTag("native-voice-access-confirmation").assertIsDisplayed()
        compose.onNodeWithTag("native-voice-access-cancel").performClick()
        assertFalse(NativeStandardVoiceHost.productAccessGranted(fixture.context))
        assertNull(voice.state.value.journal)
        // Even bypassing the UI confirmation cannot start native capture without product authorization.
        fixture.scenario.onActivity { voice.onAction(NativeStandardVoiceAction.Start) }
        compose.waitUntil(10_000) { voice.state.value.error == "PERMISSION_DENIED" }
        compose.onNodeWithTag("native-standard-voice-error").assertTextEquals(text("en", R.string.nsv_denied))
        assertNull(voice.state.value.journal)
        assertNull(voiceRequest("status").optString("captureId").takeUnless { it == "null" || it.isEmpty() })
        assertTrue(AudioCapture.get(fixture.context).uiStatus().isNull("active"))
        fixture.capture("native-standard-voice-inline-denied-en")
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(15_000) { !settings.state.value.saving && work.state.value.runtime.locale == "zh" }
        compose.onNodeWithTag("native-standard-voice-error").assertTextEquals(text("zh", R.string.nsv_denied))
        fixture.recreate(); models()
        compose.onNodeWithTag("native-standard-voice-panel").assertIsDisplayed()
        compose.onNodeWithTag("native-root-navigation").assertIsDisplayed()
        compose.onNodeWithTag("native-standard-voice-start").performClick()
        compose.onNodeWithTag("native-voice-access-cancel").performClick()
        assertFalse(NativeStandardVoiceHost.productAccessGranted(fixture.context))
        fixture.scenario.onActivity { voice.onAction(NativeStandardVoiceAction.Start) }
        compose.waitUntil(10_000) { voice.state.value.error == "PERMISSION_DENIED" }
        compose.onNodeWithTag("native-standard-voice-error").assertTextEquals(text("zh", R.string.nsv_denied))
        fixture.capture("native-standard-voice-inline-denied-recreated-zh")
        compose.onNodeWithTag("native-standard-voice-back").performClick()
        compose.onNodeWithTag("native-standard-voice-panel").assertDoesNotExist()
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        assertEquals(draft, work.state.value.draft)
        assertEquals(before, sessions())
        assertTrue(fixtureCalls().isEmpty())
        fixture.capture("native-standard-voice-returned-original-draft")
    }

    @Test fun freshVoicePreservesOldDraftAndShowsTheNewConversationWithRealDshReceipts() = runBlocking {
        start()
        fixture.scenario.onActivity { work.setDraft("UNSENT_ORDINARY_TEXT") }
        val ordinary = work.state.value.draftId
        val before = sessions()
        val target = withContext(Dispatchers.Main) { work.captureStandardVoiceTarget(true) }
        assertNotEquals(ordinary, target.draftId)
        assertEquals(before + ordinary, sessions())
        assertEquals(target.draftId, work.state.value.draftId)
        assertNull(work.state.value.selectedSessionId)
        assertEquals("", work.state.value.draft)
        assertEquals("UNSENT_ORDINARY_TEXT", fixture.context.getSharedPreferences("native_workbench", 0).getString("draft:$ordinary", null))
        assertTrue("Preserving the old draft must not call the model", fixtureCalls().isEmpty())
        assertFalse(work.canInsertStandardVoice(target))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val opened = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<NativeStandardVoiceReplyEvent.Text>()
        val nonce = UUID.randomUUID().toString()
        val observed = mutableListOf<JSONObject>()
        try {
            fixture.scenario.onActivity { work.bindStandardVoiceObservation { id, request ->
                val projector = NativeStandardVoiceReplies(id).also { it.register(request) }
                scope.launch {
                    try { NativeApiClient().follow(id).collect { frame ->
                        val event = frame.optJSONObject("event")
                        val stream = frame.optJSONObject("frame")
                        observed += JSONObject().put("type", frame.opt("type")).put("cursor", frame.opt("cursor"))
                            .put("eventType", event?.opt("type")).put("seq", event?.opt("seq")).put("surfaceOp", event?.opt("surfaceOp"))
                            .put("turn", event?.optJSONObject("data")?.opt("turn")).put("step", event?.optJSONObject("data")?.opt("step"))
                            .put("source", event?.optJSONObject("data")?.optJSONObject("source")?.let {
                                JSONObject().put("kind", it.opt("kind")).put("rpcId", it.opt("rpcId")).put("form", it.opt("form")) })
                            .put("streamType", stream?.opt("type")).put("revision", stream?.opt("revision"))
                            .put("attemptId", stream?.opt("attemptId")).put("index", stream?.opt("index"))
                            .put("streamTurn", stream?.opt("turn")).put("streamStep", stream?.opt("step"))
                            .put("startedAfterSeq", stream?.opt("startedAfterSeq")).put("outcome", stream?.opt("outcome"))
                        val events = projector.accept(frame)
                        if (frame.optString("type") == "snapshot") opened.complete(Unit)
                        for (event in events) if (event is NativeStandardVoiceReplyEvent.Text) reply.complete(event)
                    } } catch (failure: Throwable) { if (!opened.isCompleted) opened.completeExceptionally(failure); reply.completeExceptionally(failure) }
                }
                withTimeout(10_000) { opened.await() }
            } }
            val receipt = withContext(Dispatchers.Main) { work.submitStandardVoice(target, nonce, "NATIVE_STANDARD_VOICE_EXPLICIT_FINAL") }
            assertEquals(target.draftId, receipt.sessionId); assertEquals(nonce, receipt.rpcId)
            val actual = withTimeout(30_000) { reply.await() }
            assertEquals(receipt.sessionId, actual.sessionId)
            assertEquals("Native DSH reply received.", actual.text)
            assertTrue(actual.final && actual.live)
            assertEquals(target.draftId, work.state.value.selectedSessionId)
            assertEquals("", work.state.value.draft)
            compose.waitUntil(10_000) { work.state.value.messages.any { it.text == actual.text } }
            compose.onNodeWithTag("native-composer").assertIsDisplayed()
            val preferences = fixture.context.getSharedPreferences("native_workbench", 0)
            assertTrue(preferences.contains("voice-receipt:$nonce"))
            assertTrue(withContext(Dispatchers.Main) { work.standardVoiceSubmissionStatus(target, nonce) } is NativeStandardVoiceSubmissionStatus.Accepted)
            assertTrue(preferences.edit().remove("voice-receipt:$nonce").commit())
            assertTrue("Actual history must prove a lost local receipt", withContext(Dispatchers.Main) {
                work.standardVoiceSubmissionStatus(target, nonce) } is NativeStandardVoiceSubmissionStatus.Accepted)
            assertTrue("Absence from bounded history is unknown", withContext(Dispatchers.Main) {
                work.standardVoiceSubmissionStatus(target, UUID.randomUUID().toString()) } is NativeStandardVoiceSubmissionStatus.Unknown)
            val check = withContext(Dispatchers.Main) { work.validateStandardVoiceTarget(target) }
            assertTrue(check.writableAndOwned && check.draftUnchanged)
            fixture.scenario.onActivity { work.selectSession(ordinary) }
            compose.waitUntil(10_000) { work.state.value.selectedSessionId == ordinary && work.state.value.draft == "UNSENT_ORDINARY_TEXT" }
            val manual = withContext(Dispatchers.Main) { work.captureStandardVoiceTarget(false) }
            fixture.scenario.onActivity { work.setDraft("EDITED_ORDINARY_TEXT") }
            assertFalse(withContext(Dispatchers.Main) { work.validateStandardVoiceTarget(manual) }.draftUnchanged)
            assertTrue(withContext(Dispatchers.Main) { work.validateStandardVoiceTarget(target) }.draftUnchanged)
            assertEquals(before + ordinary + target.draftId, sessions())
            assertEquals(1, fixtureCalls().count { !it.optBoolean("title") && it.optString("sessionId") == target.draftId })
        } finally {
            scope.cancel()
            File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { mkdirs() }
                .resolve("native-standard-voice-reply-wire.json").writeText(JSONArray(observed).toString(2))
        }
    }

    private fun start() {
        fixture.startRuntime(); models()
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaces.isNotEmpty()
            && settings.state.value.loaded && voice.entryEnabled.value == true }
    }
    private fun models() = fixture.scenario.onActivity {
        work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
        voice = ViewModelProvider(it)[NativeStandardVoiceViewModel::class.java]
        settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
    }
    private fun sessions(): Set<String> {
        val result = request("/droy/native/session/list", JSONObject())
        val rows = result.getJSONArray("items")
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("sessionId") }.toSet()
    }
    private fun voiceRequest(operation: String, body: JSONObject = JSONObject()): JSONObject = request("/droy/voice", body.put("operation", operation))
    private fun request(path: String, body: JSONObject? = null): JSONObject = runBlocking {
        val envelope = fixture.runtimeTransport.json(path, if (body == null) "GET" else "POST", body)
        assertTrue(envelope.toString(), envelope.getBoolean("ok")); envelope.getJSONObject("result")
    }
    private fun text(language: String, id: Int): String = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(language)) }).getString(id)
    private fun fixtureCalls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf { it.isFile }?.readLines()
        ?.filter(String::isNotBlank)?.map(::JSONObject).orEmpty()
}

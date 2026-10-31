package io.droyagent.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.companion.data.NativeCompanionClient
import io.droyagent.app.nativeui.companion.data.NativeCompanionProfilePatch
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.workspaces.NativeWorkspaceJson
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputAction
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputPhase
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputViewModel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Emulator AudioRecord receives host-injected public PCM. No direct send call, fake capture or production seam. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionHoldInjectedAudioDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val explicitInjection = object : ExternalResource() {
        override fun before() {
            assertEquals("The host must explicitly select controlled microphone injection", "true", arguments.getString("nativeInjectedAudio"))
            assertEquals("io.droyagent.app.dev", context.packageName)
            assertTrue("Only the dedicated emulator accepts this fixture", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
            // Host disables the real host microphone and preserves/grants/restores this permission
            // outside instrumentation: revocation can kill the process before fixture restoration.
            assertEquals("Host must grant RECORD_AUDIO before instrumentation", PackageManager.PERMISSION_GRANTED, microphonePermission())
            assertFalse("Remove the previous fixture control file before starting", control.exists())
            assertFalse("Remove the previous fixture result file before starting", hostResult.exists())
        }
    }
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "native-isolated-evidence")
    private val control get() = File(evidence, "injected-voice-control.json")
    private val hostResult get() = File(evidence, "injected-voice-result.json")
    private val runId = UUID.randomUUID().toString()

    @Test fun companionHoldUploadsOriginalOnceKeepsComposerAndCancelsWithoutWorkbenchDelivery() = runBlocking {
        lateinit var work: NativeWorkbenchViewModel
        lateinit var voice: NativeVoiceInputViewModel
        lateinit var companions: NativeCompanionViewModel
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            voice = ViewModelProvider(it)[NativeVoiceInputViewModel::class.java]
            companions = ViewModelProvider(it)[NativeCompanionViewModel::class.java]
        }
        assertTrue(context.getSharedPreferences("product", 0).edit().putBoolean("allowAudioMicrophone", true)
            .putBoolean("allowAudioTranscription", true).commit())
        fixture.scenario.onActivity { voice.refresh() }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.models.isNotEmpty()
            && voice.state.value.permissions.permitted && voice.state.value.catalog?.ready == true }
        compose.onNodeWithTag("native-tab-workbench").performClick()
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        compose.onNodeWithTag("native-composer").assertIsDisplayed()
        assertNull(voice.state.value.operationId)
        assertNull(work.state.value.selectedSessionId)
        assertEquals("", work.state.value.draft)
        val workDraft = work.state.value.draftId
        val workWorkspace = work.state.value.selectedWorkspaceId
        fixture.scenario.onActivity { work.setDraft(WORKBENCH_DRAFT) }
        compose.onNodeWithTag("native-composer").assertTextContains(WORKBENCH_DRAFT)
        val workSessions = ordinarySessions()
        compose.onNodeWithTag("native-tab-messages").performClick()
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.templateId == "kaka" }
            && companions.state.value.models.any { it.provider == LOCAL_PROVIDER && it.id == LOCAL_MODEL } }
        val kaka = companions.state.value.companions.first { it.templateId == "kaka" }
        val local = companions.state.value.models.first { it.provider == LOCAL_PROVIDER && it.id == LOCAL_MODEL }
        val client = NativeCompanionClient()
        val before = client.root()
        assertLocalRoute(before.getJSONObject("defaultModel"))
        fixture.scenario.onActivity { companions.updateProfile(kaka.id,
            NativeCompanionProfilePatch(model = local, settings = kaka.settings.copy(proactiveEnabled = false))) }
        compose.waitUntil(15_000) { !companions.state.value.profileSaving && companions.state.value.companions.any {
            it.id == kaka.id && !it.modelInherited && it.model?.provider == LOCAL_PROVIDER && it.model?.id == LOCAL_MODEL } }
        val configured = client.root()
        assertLocalRoute(configured.getJSONObject("defaultModel"))
        val profiles = configured.getJSONArray("companions")
        val configuredKaka = (0 until profiles.length()).map(profiles::getJSONObject).single { it.getString("id") == kaka.id }
        assertLocalRoute(configuredKaka.getJSONObject("model"))
        assertFalse(configuredKaka.getBoolean("modelInherited"))
        assertFalse(configuredKaka.getJSONObject("settings").getBoolean("proactiveEnabled"))
        assertTrue(client.page(kaka.id).messages.isEmpty())
        compose.onNodeWithTag("native-companion-conversation:${kaka.id}").performClick()
        compose.waitUntil(10_000) { companions.state.value.selectedCompanionId == kaka.id && !companions.state.value.loading }
        compose.onNodeWithTag("native-companion-chat").assertIsDisplayed()
        compose.onNodeWithTag("native-companion-composer").assertIsDisplayed()
        assertTrue("No recording already owns the emulator microphone", AudioCapture.get(context).uiStatus().isNull("active"))
        assertTrue("There are no earlier companion fixture calls", companionCalls().isEmpty())
        assertTrue(evidence.isDirectory || evidence.mkdirs())

        val originals = ConcurrentHashMap<String, Original>()
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val observedFailure = AtomicReference<Throwable?>()
        val peak = AtomicReference(0f)
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Observe before pressing. A synchronous bounded read on this test observer captures the
        // admitted original before the real VM/Shell automatically retires it after acceptance.
        val observer = observation.launch {
            try {
                val store = NativeDraftMediaStore(context)
                voice.state.collect { state ->
                    state.operationId?.let { operation ->
                        check(state.target?.companionId == kaka.id && state.target?.workbench == null) { "Voice target changed during the operation" }
                        if (state.recording && state.level > peak.get()) peak.set(state.level)
                        state.source?.takeIf { !originals.containsKey(operation) }?.let { item ->
                            val bytes = store.open(item).use { it.readNBytes(NativeVoiceInputHost.MAX_AUDIO_BYTES + 1) }
                            check(bytes.size.toLong() == item.bytes && bytes.size <= NativeVoiceInputHost.MAX_AUDIO_BYTES)
                            originals[operation] = Original(item.name, sha256(bytes), bytes.size, state.durationMs)
                        }
                        if (state.accepted) {
                            accepted.add(operation)
                            check(state.resultSessionId == null) { "A companion voice was delivered to the workbench" }
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                observedFailure.set(failure)
            }
        }
        var pressed = false
        var operation: String? = null
        var secondOperation: String? = null
        var completed = false
        try {
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { down(center) }
            pressed = true
            awaitVoice("AudioRecord did not enter RECORDING", 10_000, voice) { it.phase == NativeVoiceInputPhase.RECORDING }
            operation = requireNotNull(voice.state.value.operationId)
            assertInlineRecording()
            val captured = AudioCapture.get(context).uiStatus().getJSONObject("active")
            assertEquals("microphone", captured.getString("source"))
            assertEquals("droy-native-input", captured.getString("owner"))
            assertEquals(operation, captured.getString("requestId"))
            assertEquals("recording", captured.getString("state"))
            writeControl("ready_to_inject", operation)
            compose.waitUntil(20_000) { hostResult.isFile }
            val injected = JSONObject(hostResult.readText())
            assertEquals("Host result must belong to this run", runId, injected.getString("runId"))
            assertEquals("Host result must belong to the actual capture", operation, injected.getString("operationId"))
            assertTrue("Host injection failed: $injected", injected.getBoolean("ok"))
            assertEquals(PUBLIC_WAVE_SHA256, injected.getString("sourceSha256"))
            assertFalse("Host microphone must remain disabled", injected.getBoolean("hostMicrophoneEnabled"))
            assertTrue("The injected speech must have a real duration", injected.getDouble("audioSeconds") > 1)
            assertEquals(operation, voice.state.value.operationId)
            assertEquals(NativeVoiceInputPhase.RECORDING, voice.state.value.phase)
            assertTrue("Injected PCM must produce a real non-zero recording level", peak.get() > 0f)
            assertInlineRecording()
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { advanceEventTime(100); up() }
            pressed = false
            writeControl("released", operation)
            compose.waitUntil(120_000) {
                observedFailure.get() != null || operation in accepted && voice.state.value.operationId == null
                    || voice.state.value.phase in setOf(NativeVoiceInputPhase.FAILED, NativeVoiceInputPhase.UNCONFIRMED, NativeVoiceInputPhase.INTERRUPTED)
            }
            observedFailure.get()?.let { throw AssertionError("Could not observe the original recording", it) }
            assertTrue("Release must automatically send without a second action: ${voice.state.value}", operation in accepted)
            assertNull("The Shell must automatically consume the SENT operation", voice.state.value.operationId)
            assertEquals(setOf(operation), accepted.toSet())
            val original = requireNotNull(originals[operation]) { "The real VM never admitted its recorded WAV" }
            assertTrue(original.durationMs >= (injected.getDouble("audioSeconds") * 1000).toLong())
            compose.waitUntil(30_000) { companions.state.value.selectedCompanionId == kaka.id
                && companions.state.value.messages.any { it.role == "assistant" && it.text == "Native companion reply received." }
                && companions.state.value.status.chatExecution?.quiescent == true }
            // No navigation is performed here: assert the actual automatic Done result.
            compose.onNodeWithTag("native-companion-chat").assertIsDisplayed()
            compose.onNodeWithTag("native-companion-composer").assertIsDisplayed()
            val actualPage = client.page(kaka.id)
            val users = actualPage.messages.filter { it.role == "user" }
            assertEquals("Exactly one durable companion voice message is allowed", 1, users.size)
            val message = users.single()
            assertEquals(operation, message.id)
            assertEquals(operation, message.clientId)
            assertEquals("voice", message.type)
            val storedVoice = requireNotNull(message.voice)
            assertEquals("ready", storedVoice.transcriptionState)
            assertEquals(original.durationMs, requireNotNull(storedVoice.durationMs))
            assertEquals(message.text, storedVoice.transcript)
            assertTrue("The actual offline recognizer must produce speech", message.text.length > 3)
            val expected = arguments.getString("audioAsrExpected", "")
            if (expected.isNotBlank()) assertTrue(message.text, message.text.contains(expected))
            val url = requireNotNull(storedVoice.url)
            assertTrue("The original must belong to this companion", url.startsWith("/droy/companions/${kaka.id}/attachments/"))
            val downloaded = client.media(url)
            assertEquals(original.sha256, sha256(downloaded))
            assertEquals(original.bytes, downloaded.size)
            assertEquals(1, companions.state.value.messages.count { it.role == "user" && (it.id == operation || it.clientId == operation) })
            val calls = companionCalls()
            assertTrue("A real local planner must handle this nonce", calls.any { it.optString("kind") == "planner"
                && it.optString("companionId") == kaka.id && it.optString("signature").split(':').contains(operation) })
            assertTrue("The fixture replyer must complete the native companion path", calls.any { it.optString("kind") == "replyer" })
            assertEquals(workDraft, work.state.value.draftId)
            assertEquals(workWorkspace, work.state.value.selectedWorkspaceId)
            assertNull(work.state.value.selectedSessionId)
            assertEquals(WORKBENCH_DRAFT, work.state.value.draft)
            assertTrue(work.state.value.messages.isEmpty())
            assertEquals("Companion voice cannot create an ordinary workbench session", workSessions, ordinarySessions())
            compose.onNodeWithTag("native-companion-history").performScrollToNode(hasTestTag("native-companion-message:${message.id}"))
            compose.onNodeWithTag("native-companion-message:${message.id}").assertIsDisplayed()
            fixture.capture("native-companion-injected-voice-actual-history")
            val settledCallCount = calls.size

            // The emulator source is now silent with host microphone still disabled.
            // This is a second real AudioRecord operation, cancelled by the actual upward gesture.
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { down(center) }
            pressed = true
            awaitVoice("Second AudioRecord did not enter RECORDING", 10_000, voice) { it.phase == NativeVoiceInputPhase.RECORDING }
            secondOperation = requireNotNull(voice.state.value.operationId)
            assertNotEquals(operation, secondOperation)
            assertInlineRecording()
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { moveTo(Offset(center.x, center.y - height * 2)) }
            compose.waitUntil(5_000) { voice.state.value.cancelGesture }
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { advanceEventTime(100); up() }
            pressed = false
            compose.waitUntil(15_000) { voice.state.value.operationId == null && AudioCapture.get(context).uiStatus().isNull("active") }
            assertEquals("Cancelled second recording must not be admitted or accepted", setOf(operation), originals.keys.toSet())
            assertEquals(setOf(operation), accepted.toSet())
            assertEquals("The cancelled recording must not invoke another local companion round", settledCallCount, companionCalls().size)
            assertEquals(1, client.page(kaka.id).messages.count { it.role == "user" })
            assertEquals(kaka.id, companions.state.value.selectedCompanionId)
            assertEquals(workDraft, work.state.value.draftId)
            assertEquals(WORKBENCH_DRAFT, work.state.value.draft)
            assertNull(work.state.value.selectedSessionId)
            assertEquals(workSessions, ordinarySessions())
            assertTrue(work.state.value.messages.isEmpty())
            assertFalse(AudioTranscription.get(context).hasActiveJobs())
            assertEquals(PackageManager.PERMISSION_GRANTED, microphonePermission())
            val receipt = JSONObject().put("runId", runId).put("operationId", operation).put("cancelledOperationId", secondOperation)
                .put("companionId", kaka.id).put("messageId", message.id).put("sourceSha256", PUBLIC_WAVE_SHA256)
                .put("originalSha256", original.sha256).put("historySha256", sha256(downloaded)).put("originalBytes", original.bytes)
                .put("recordedDurationMs", original.durationMs).put("injectedAudioSeconds", injected.getDouble("audioSeconds"))
                .put("transcript", message.text).put("peakLevel", peak.get()).put("userMessages", 1).put("localCompanionCalls", settledCallCount)
                .put("provider", LOCAL_PROVIDER).put("model", LOCAL_MODEL).put("workbenchDraftPreserved", true).put("inlineComposerPreserved", true)
                .put("automaticConsumption", true).put("realAudioRecord", true).put("hostMicrophoneEnabled", false)
                .put("coverage", "emulator_injected_public_pcm_native_companion_hold_asr_upload_nonce_done_and_cancel_not_phone_microphone")
            writeAtomic(File(evidence, "companion-injected-voice-receipt.json"), receipt)
            writeControl("completed", operation)
            completed = true
        } finally {
            try {
                if (pressed) {
                    // Cancel the real pointer rather than allowing cleanup to emit a successful release.
                    compose.onNodeWithTag("native-hold-to-record").performTouchInput { cancel() }
                }
                if (!completed && voice.state.value.operationId in setOfNotNull(operation, secondOperation)) {
                    fixture.scenario.onActivity { voice.onAction(NativeVoiceInputAction.Cancel) }
                }
                compose.waitUntil(30_000) { AudioCapture.get(context).uiStatus().isNull("active") && !AudioTranscription.get(context).hasActiveJobs() }
                assertEquals("The test does not alter the host-managed permission", PackageManager.PERMISSION_GRANTED, microphonePermission())
                if (!completed) writeControl("failed", operation, voice.state.value.error)
            } finally { observer.cancelAndJoin(); observation.cancel() }
        }
    }

    private fun awaitVoice(message: String, timeout: Long, voice: NativeVoiceInputViewModel,
        predicate: (io.droyagent.app.nativeui.voiceinput.NativeVoiceInputState) -> Boolean) {
        compose.waitUntil(timeout) { predicate(voice.state.value) || voice.state.value.error != null }
        assertTrue("$message: ${voice.state.value}", predicate(voice.state.value))
    }
    private fun microphonePermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    private fun writeControl(phase: String, operation: String?, error: String? = null) = writeAtomic(control,
        JSONObject().put("runId", runId).put("phase", phase).put("operationId", operation ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL).put("timeMillis", System.currentTimeMillis()))
    private fun writeAtomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(file)) { "Could not atomically publish ${file.name}" }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertInlineRecording() {
        compose.onNodeWithTag("native-companion-composer").assertIsDisplayed()
        compose.onNodeWithTag("native-hold-to-record").assertIsDisplayed()
        compose.onNodeWithTag("native-inline-voice").assertIsDisplayed()
    }
    private fun assertLocalRoute(route: JSONObject) {
        assertEquals(LOCAL_PROVIDER, route.getString("provider"))
        assertEquals(LOCAL_MODEL, route.getString("model"))
    }
    private suspend fun ordinarySessions(): Set<String> = NativeWorkspaceJson.catalog(NativeApiClient().get("workspaces") as JSONObject)
        .sessions.filterNot { it.readOnly }.map { it.id }.toSet()
    private fun companionCalls(): List<JSONObject> = File(fixture.home, "native-fixture-companion.jsonl").takeIf(File::isFile)
        ?.readLines()?.filter(String::isNotBlank)?.map(::JSONObject).orEmpty()
    private data class Original(val name: String, val sha256: String, val bytes: Int, val durationMs: Long)
    companion object {
        private const val PUBLIC_WAVE_SHA256 = "b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373"
        private const val LOCAL_PROVIDER = "droy-native-fixture"
        private const val LOCAL_MODEL = "native-local"
        private const val WORKBENCH_DRAFT = "UNSENT_WORKBENCH_COMPANION_VOICE_GUARD"
    }
}

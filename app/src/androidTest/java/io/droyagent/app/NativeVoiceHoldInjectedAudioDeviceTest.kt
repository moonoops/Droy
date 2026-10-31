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
import io.droyagent.app.nativeui.tools.NativeToolSource
import io.droyagent.app.nativeui.tools.NativeToolsClient
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
class NativeVoiceHoldInjectedAudioDeviceTest {
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

    @Test fun realHoldReleaseAutomaticallySendsOriginalOnceAndSwipeCancelDoesNotSend() = runBlocking {
        lateinit var work: NativeWorkbenchViewModel
        lateinit var voice: NativeVoiceInputViewModel
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            voice = ViewModelProvider(it)[NativeVoiceInputViewModel::class.java]
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
        val expectedSession = work.state.value.draftId
        assertTrue("The isolated conversation has no earlier requests", fixtureCalls().isEmpty())
        assertTrue("No recording already owns the emulator microphone", AudioCapture.get(context).uiStatus().isNull("active"))
        assertTrue(evidence.isDirectory || evidence.mkdirs())

        val originals = ConcurrentHashMap<String, Original>()
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val acceptedSession = AtomicReference<String?>()
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
                        if (state.recording && state.level > peak.get()) peak.set(state.level)
                        state.source?.takeIf { !originals.containsKey(operation) }?.let { item ->
                            val bytes = store.open(item).use { it.readNBytes(NativeVoiceInputHost.MAX_AUDIO_BYTES + 1) }
                            check(bytes.size.toLong() == item.bytes && bytes.size <= NativeVoiceInputHost.MAX_AUDIO_BYTES)
                            originals[operation] = Original(item.name, sha256(bytes), bytes.size, state.durationMs)
                        }
                        if (state.accepted) {
                            accepted.add(operation)
                            acceptedSession.set(state.resultSessionId)
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
            assertEquals(expectedSession, acceptedSession.get())
            compose.waitUntil(30_000) { work.state.value.selectedSessionId == expectedSession && work.state.value.sessionBaselineId == expectedSession
                && work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming } }
            // No navigation is performed here: this asserts the actual automatic Done result.
            compose.onNodeWithTag("native-workbench").assertIsDisplayed()
            compose.onNodeWithTag("native-composer").assertIsDisplayed()
            compose.onNodeWithTag("native-root-navigation").assertIsDisplayed()
            compose.onNodeWithTag("native-tab-workbench").assertIsSelected()
            // DSH also uses user/message for engine context; the reducer marks those as context.
            val users = work.state.value.messages.filter { it.role == "user" && it.kind != "context" }
            assertEquals("Exactly one voice message must reach the conversation", 1, users.size)
            val message = users.single()
            assertEquals("The visible message must retain this capture's actual RPC identity", operation, message.requestId)
            val item = message.attachments.single()
            assertEquals("file", item.kind)
            assertEquals(original.name, item.name)
            assertTrue(requireNotNull(item.name).endsWith(".wav"))
            assertTrue("The real offline recognizer must produce speech", message.text.length > 3)
            val expected = arguments.getString("audioAsrExpected", "")
            if (expected.isNotBlank()) assertTrue(message.text, message.text.contains(expected))
            val downloaded = NativeToolsClient().attachmentBytes(NativeToolSource.Attachment(expectedSession,
                requireNotNull(message.seq), requireNotNull(item.attachmentId)))
            assertEquals(original.sha256, sha256(downloaded))
            assertEquals(original.bytes, downloaded.size)
            assertEquals(1, modelCalls(expectedSession))
            compose.onNodeWithTag("native-history").performScrollToNode(hasText(original.name))
            compose.onNodeWithText(original.name).assertIsDisplayed()
            fixture.capture("native-injected-voice-actual-history")

            // The emulator source is now silent with host microphone still disabled.
            // This is a second real AudioRecord operation, cancelled by the actual upward gesture.
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { down(center) }
            pressed = true
            awaitVoice("Second AudioRecord did not enter RECORDING", 10_000, voice) { it.phase == NativeVoiceInputPhase.RECORDING }
            secondOperation = requireNotNull(voice.state.value.operationId)
            assertNotEquals(operation, secondOperation)
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { moveTo(Offset(center.x, center.y - height * 2)) }
            compose.waitUntil(5_000) { voice.state.value.cancelGesture }
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { advanceEventTime(100); up() }
            pressed = false
            compose.waitUntil(15_000) { voice.state.value.operationId == null && AudioCapture.get(context).uiStatus().isNull("active") }
            assertEquals("Cancelled second recording must not be admitted or accepted", setOf(operation), originals.keys.toSet())
            assertEquals(setOf(operation), accepted.toSet())
            assertEquals(1, modelCalls(expectedSession))
            assertEquals(1, work.state.value.messages.count { it.role == "user" && it.kind != "context" })
            assertFalse(AudioTranscription.get(context).hasActiveJobs())
            assertEquals(PackageManager.PERMISSION_GRANTED, microphonePermission())
            val receipt = JSONObject().put("runId", runId).put("operationId", operation).put("cancelledOperationId", secondOperation)
                .put("sessionId", expectedSession).put("sourceSha256", PUBLIC_WAVE_SHA256)
                .put("originalSha256", original.sha256).put("historySha256", sha256(downloaded)).put("originalBytes", original.bytes)
                .put("recordedDurationMs", original.durationMs).put("injectedAudioSeconds", injected.getDouble("audioSeconds"))
                .put("transcript", message.text).put("peakLevel", peak.get()).put("userMessages", 1).put("modelCalls", 1)
                .put("automaticConsumption", true).put("realAudioRecord", true).put("hostMicrophoneEnabled", false)
                .put("coverage", "emulator_injected_public_pcm_real_native_hold_release_asr_send_and_cancel_not_phone_microphone")
            writeAtomic(File(evidence, "injected-voice-receipt.json"), receipt)
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
    private fun fixtureCalls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf(File::isFile)
        ?.readLines()?.filter(String::isNotBlank)?.map(::JSONObject).orEmpty()
    private fun modelCalls(session: String) = fixtureCalls().count { !it.optBoolean("title") && it.optString("sessionId") == session }
    private data class Original(val name: String, val sha256: String, val bytes: Int, val durationMs: Long)
    companion object { private const val PUBLIC_WAVE_SHA256 = "b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373" }
}

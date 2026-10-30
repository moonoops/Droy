package io.droyagent.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputAction
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputPhase
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputViewModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Real silent AudioRecord -> offline ASR -> empty-text original upload. Emulator only. */
@RunWith(AndroidJUnit4::class)
class NativeVoiceSilentRecordingDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val silence = object : ExternalResource() {
        override fun before() {
            assertEquals("Host must disable host microphone and clear injected audio first", "true",
                InstrumentationRegistry.getArguments().getString("nativeControlledSilence"))
            assertEquals("io.droyagent.app.dev", context.packageName)
            assertTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertFalse("Clear prior silence control before this run", control.exists())
            assertFalse("Clear prior silence receipt before this run", hostResult.exists())
        }
    }
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val evidence get() = File(context.getExternalFilesDir(null), "native-isolated-evidence")
    private val control get() = File(evidence, "silent-voice-control.json")
    private val hostResult get() = File(evidence, "silent-voice-result.json")
    private val runId = UUID.randomUUID().toString()

    @Test fun silentOriginalSendsWithoutRecoveryCardOrSecondAction() = runBlocking {
        lateinit var work: NativeWorkbenchViewModel
        lateinit var voice: NativeVoiceInputViewModel
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            voice = ViewModelProvider(it)[NativeVoiceInputViewModel::class.java]
        }
        assertTrue(context.getSharedPreferences("product", 0).edit().putBoolean("allowAudioMicrophone", true)
            .putBoolean("allowAudioTranscription", true).commit())
        fixture.scenario.onActivity { voice.refresh() }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved
            && voice.state.value.permissions.permitted && voice.state.value.catalog?.ready == true }
        compose.onNodeWithTag("native-tab-workbench").performClick()
        val session = work.state.value.draftId
        val originals = ConcurrentHashMap<String, ByteArray>()
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val immediateBubbles = ConcurrentHashMap.newKeySet<String>()
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val observer = observation.launch {
            val store = NativeDraftMediaStore(context)
            voice.state.collect { state ->
                state.operationId?.let { id ->
                    state.source?.takeIf { !originals.containsKey(id) }?.let { originals[id] = store.open(it).use { source -> source.readBytes() } }
                    if (state.showPendingBubble) immediateBubbles.add(id)
                    if (state.accepted) accepted.add(id)
                }
            }
        }
        var held = false
        try {
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { down(center) }
            held = true
            compose.waitUntil(10_000) { voice.state.value.phase == NativeVoiceInputPhase.RECORDING }
            val operation = requireNotNull(voice.state.value.operationId)
            assertTrue(evidence.isDirectory || evidence.mkdirs())
            writeAtomic(control, JSONObject().put("runId", runId).put("operationId", operation).put("phase", "ready_to_inject"))
            compose.waitUntil(30_000) { hostResult.isFile }
            val injected = JSONObject(hostResult.readText())
            assertEquals(runId, injected.getString("runId"))
            assertEquals(operation, injected.getString("operationId"))
            assertTrue(injected.toString(), injected.getBoolean("ok"))
            assertFalse(injected.getBoolean("hostMicrophoneEnabled"))
            assertEquals(16000, injected.getInt("sampleRate"))
            assertEquals(96000, injected.getInt("pcmBytes"))
            assertEquals(sha256(ByteArray(96000)), injected.getString("pcmSha256"))
            assertEquals(NativeVoiceInputPhase.RECORDING, voice.state.value.phase)
            compose.onNodeWithTag("native-hold-to-record").performTouchInput { advanceEventTime(100); up() }
            held = false
            compose.waitUntil(120_000) { operation in accepted && voice.state.value.operationId == null
                || voice.state.value.phase in setOf(NativeVoiceInputPhase.FAILED, NativeVoiceInputPhase.UNCONFIRMED) }
            assertTrue("Released original must enter the timeline before ASR/send completes", operation in immediateBubbles)
            assertTrue("Silent release must automatically send original: ${voice.state.value}", operation in accepted)
            assertNull(voice.state.value.operationId)
            val original = requireNotNull(originals[operation])
            assertTrue(original.size > 44)
            // Emulator fallback input before/after injection can contain generated samples. Require
            // a real one-second zero-PCM interior, rather than assuming disabled host mic means zero PCM.
            var longestSilence = 0
            var silenceRun = 0
            for (offset in 44 until original.size - 1 step 2) {
                silenceRun = if (original[offset] == 0.toByte() && original[offset + 1] == 0.toByte()) silenceRun + 1 else 0
                longestSilence = maxOf(longestSilence, silenceRun)
            }
            assertTrue("Controlled injection must reach the real AudioRecord WAV: longest zero run $longestSilence", longestSilence >= AudioCapture.SAMPLE_RATE)
            compose.waitUntil(30_000) { work.state.value.messages.any { it.role == "assistant" && !it.streaming } }
            val messages = work.state.value.messages.filter { it.role == "user" && it.kind != "context" }
            assertEquals(1, messages.size)
            assertEquals("", messages.single().text)
            assertEquals(operation, messages.single().requestId)
            assertEquals(1, messages.single().attachments.size)
            assertTrue(requireNotNull(messages.single().attachments.single().name).matches(Regex("Audio-\\d{8}-\\d{6}\\.wav")))
            val capturedDuration = requireNotNull(messages.single().attachments.single().durationMs)
            assertTrue(capturedDuration in 1..60_000L)
            assertEquals(capturedDuration, context.getSharedPreferences("native_workbench", 0)
                .getLong("voice-duration:$session:$operation", 0L))
            compose.onNodeWithText(requireNotNull(messages.single().attachments.single().name)).assertDoesNotExist()
            compose.onNodeWithTag("native-message-attachment:${messages.single().id}:0").assertDoesNotExist()
            // Reload the journal to exercise persisted request-scoped presentation metadata.
            fixture.scenario.onActivity { work.openDraft(null); work.selectSession(session) }
            compose.waitUntil(30_000) { work.state.value.messages.any {
                it.requestId == operation && it.attachments.singleOrNull()?.durationMs == capturedDuration
            } }
            compose.onNodeWithContentDescription(context.getString(R.string.native_companion_play_voice)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.native_work_pause))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.native_work_pause)).performClick()
            val calls = File(fixture.home, "native-fixture-calls.jsonl").readLines().filter(String::isNotBlank).map(::JSONObject)
            assertEquals(1, calls.count { !it.optBoolean("title") && it.optString("sessionId") == session })
            compose.onNodeWithTag("native-inline-voice").assertDoesNotExist()
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-silent-voice-sent")
            writeAtomic(File(evidence, "native-silent-voice-receipt.json"), JSONObject().put("runId", runId)
                .put("operationId", operation).put("originalSha256", sha256(original)).put("originalBytes", original.size)
                .put("longestSilenceSamples", longestSilence).put("sampleRate", AudioCapture.SAMPLE_RATE)
                .put("transcript", messages.single().text).put("userMessages", 1).put("modelCalls", 1)
                .put("coverage", "controlled_injected_zero_pcm_real_record_asr_empty_text_original_send"))
        } finally {
            if (held) compose.onNodeWithTag("native-hold-to-record").performTouchInput { cancel() }
            if (voice.state.value.operationId != null) fixture.scenario.onActivity { voice.onAction(NativeVoiceInputAction.Cancel) }
            compose.waitUntil(30_000) { AudioCapture.get(context).uiStatus().isNull("active") && !AudioTranscription.get(context).hasActiveJobs() }
            observer.cancelAndJoin()
            observation.cancel()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun writeAtomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value.toString(2))
        check(temporary.renameTo(file))
    }
}

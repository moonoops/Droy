package io.droyagent.app

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.tools.NativeToolSource
import io.droyagent.app.nativeui.tools.NativeToolsClient
import io.droyagent.app.nativeui.voiceinput.NativeSpeechClient
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native adapters, offline ASR and local DSH. Does not exercise microphone capture or VM release dispatch. */
@RunWith(AndroidJUnit4::class)
class NativeVoiceAudioPipelineDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun controlledWaveKeepsOriginalAttachmentDeduplicatesSubmissionAndHonorsAsrCancellation() = runBlocking {
        val context = fixture.context
        val microphonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val sourcePath = InstrumentationRegistry.getArguments().getString("audioAsrFixture", "")
        assertFalse("Pass the app-private public SenseVoice zh.wav as audioAsrFixture", sourcePath.isEmpty())
        val source = AudioTranscription.controlledFile(context.filesDir, context.cacheDir, sourcePath)
        // Same public test utterance pinned by AudioDictationDeviceTest and CompanionVoiceInputDeviceTest.
        assertEquals("Only the pinned public SenseVoice zh.wav is accepted", PUBLIC_WAVE_SHA256, sha256(source.readBytes()))
        val transcription = AudioTranscription.get(context)
        compose.waitUntil(30_000) {
            transcription.request(JSONObject().put("operation", "preparation"), "native-audio-pipeline-fixture").optString("state") != "preparing"
        }
        assertTrue("Bundled offline ASR must be ready; this test does not download resources",
            transcription.request(JSONObject().put("operation", "preparation"), "native-audio-pipeline-fixture").getBoolean("ready"))
        assertFalse("Do not interrupt another transcription", transcription.hasActiveJobs())
        assertTrue(context.getSharedPreferences("product", 0).edit().putBoolean("allowAudioTranscription", true).commit())
        lateinit var work: NativeWorkbenchViewModel
        fixture.scenario.onActivity { work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java] }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.models.isNotEmpty() }

        val captureId = UUID.randomUUID().toString()
        val importId = UUID.randomUUID().toString()
        val recognitionId = UUID.randomUUID().toString()
        val cancelledId = UUID.randomUUID().toString()
        val wave = File(context.filesDir, "audio-captures/$captureId.wav")
        assertFalse(wave.exists())
        assertTrue(wave.parentFile!!.isDirectory || wave.parentFile!!.mkdirs())
        val admission = MediaInputStore.nativeDrafts(context)
        val speech = NativeSpeechClient()
        var imported = false
        try {
            writeCaptureFormat(source, wave)
            val original = wave.readBytes()
            val digest = sha256(original)
            val manifest = admission.importCompletedWave(wave, importId)
            imported = true
            val attachment = NativeLocalAttachment.parse(JSONObject(manifest.getJSONArray("items").getJSONObject(0).toString()).put("importId", importId))
            val bytes = NativeDraftMediaStore(context).bytes(attachment)
            assertArrayEquals("Import must retain the original 48 kHz recording", original, bytes)
            assertTrue(speech.catalog().ready)
            val result = speech.transcribe(bytes, recognitionId)
            assertTrue(result.text, result.text.length > 3)
            assertTrue(result.audioSeconds > 1)
            val expected = InstrumentationRegistry.getArguments().getString("audioAsrExpected", "")
            if (expected.isNotEmpty()) assertTrue(result.text, result.text.contains(expected))
            assertEquals("The same recognition identity returns its actual retained result", result, speech.transcribe(bytes, recognitionId))
            assertTrue(speech.operation(recognitionId).let { it.phase == "completed" && it.settled && it.quiescent })
            assertTrue("Recognition itself must never submit a conversation", fixtureCalls().isEmpty())

            val cancelled = speech.cancel(cancelledId)
            assertEquals("cancelled", cancelled.phase)
            assertTrue(cancelled.settled)
            try {
                speech.transcribe(bytes, cancelledId)
                fail("A delayed recognition after cancellation must be rejected")
            } catch (failure: NativeApiException) { assertEquals("NATIVE_SPEECH_CANCELLED", failure.code) }
            assertTrue("Cancelled recognition cannot create a model request", fixtureCalls().isEmpty())

            // Materialize without a prompt so the normal workbench observer displays this scoped submission.
            val session = withContext(Dispatchers.Main) { work.ensureSessionForGoal() }
            val target = withContext(Dispatchers.Main) { work.mediaTarget() }
            assertEquals(session, target.sessionId)
            val nonce = UUID.randomUUID().toString()
            assertEquals(session, work.submitExternalShare(target, nonce, result.text, listOf(attachment)))
            compose.waitUntil(30_000) {
                work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming }
            }
            assertEquals(session, work.submitExternalShare(target, nonce, result.text, listOf(attachment)))
            fixture.scenario.onActivity { work.refresh() }
            compose.waitUntil(15_000) { !work.state.value.loading && !work.state.value.sending && work.state.value.pendingRequestId == null }
            val sent = work.state.value.messages.filter { it.role == "user" && it.attachments.any { item -> item.name == attachment.name } }
            assertEquals("A repeated admission identity must not duplicate the voice message", 1, sent.size)
            val message = sent.single()
            assertEquals(result.text, message.text)
            val stored = message.attachments.single()
            // DSH FileAttachmentRef retains kind/name/identity, not a MIME field.
            assertEquals("file", stored.kind)
            assertEquals(attachment.name, stored.name)
            assertTrue(requireNotNull(stored.name).endsWith(".wav"))
            val downloaded = NativeToolsClient().attachmentBytes(NativeToolSource.Attachment(session, requireNotNull(message.seq), requireNotNull(stored.attachmentId)))
            assertEquals("The history attachment must remain the full original, not the 16 kHz ASR view", digest, sha256(downloaded))
            assertEquals(1, fixtureCalls().count { !it.optBoolean("title") && it.optString("sessionId") == session })
            assertTrue("No hardware capture is allowed", AudioCapture.get(context).uiStatus().isNull("active"))
            assertEquals("Android microphone permission must remain unchanged", microphonePermission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            fixture.capture("native-voice-original-wave-history")
            File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { mkdirs() }
                .resolve("native-voice-audio-pipeline.json").writeText(JSONObject()
                    .put("source", "pinned_public_sensevoice_zh_wav").put("sourceSha256", PUBLIC_WAVE_SHA256)
                    .put("original48kSha256", digest).put("historySha256", sha256(downloaded))
                    .put("audioSeconds", result.audioSeconds).put("transcript", result.text)
                    .put("userMessages", sent.size).put("modelCalls", 1).put("microphoneOpened", false)
                    .put("coverage", "native_adapters_asr_attachment_nonce_and_cancellation_not_vm_release").toString(2))
        } finally {
            try {
                speech.cancel(recognitionId)
                compose.waitUntil(30_000) { !transcription.hasActiveJobs() }
            } finally {
                if (imported) admission.complete(importId, "work")
                assertTrue("Remove only this fixture's generated waveform", !wave.exists() || wave.delete())
            }
        }
    }

    private fun writeCaptureFormat(source: File, output: File) {
        // Deterministic 16 -> 48 kHz sample hold. The production ASR adapter performs its real downsampling.
        PcmWavFile(output, 48000, 1).use { wave ->
            AudioTranscriptionDecoder.decode(source, { samples, rate ->
                assertEquals(16000, rate)
                val pcm = ByteArray(samples.size * 6)
                samples.forEachIndexed { index, sample ->
                    val value = (sample * 32768f).toInt().coerceIn(-32768, 32767)
                    repeat(3) { repeated ->
                        val offset = index * 6 + repeated * 2
                        pcm[offset] = value.toByte(); pcm[offset + 1] = (value shr 8).toByte()
                    }
                }
                wave.append(pcm)
            }, {})
        }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixtureCalls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf(File::isFile)
        ?.readLines()?.filter(String::isNotBlank)?.map(::JSONObject).orEmpty()
    companion object { private const val PUBLIC_WAVE_SHA256 = "b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373" }
}

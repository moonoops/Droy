package io.droyagent.app

import android.Manifest
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.voiceinput.NativeSpeechClient
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Real offline ASR, task creation and privacy persistence; no microphone capture or external model. */
@RunWith(AndroidJUnit4::class)
class NativeVoicePrivacyStartupDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val emptyPrivateStores = object : ExternalResource() {
        private var ownsPaths = false
        override fun before() {
            assumeTrue("Explicit empty-store voice privacy fixture required", arguments.getString("nativeVoicePrivacyFixture") == "true")
            assertEquals("io.droyagent.app.dev", context.packageName)
            assertTrue("Only a dedicated emulator accepts this fixture", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
            assertTrue("Stop existing tasks before this fixture", RuntimeService.canManageHistory())
            assertFalse("Existing privacy activation cannot enter this fixture", context.getSharedPreferences("droy_memory", 0).getBoolean("privacyEverActivated", false))
            privatePaths.forEach { name ->
                assertFalse("Existing private data must not enter this fixture: $name", Files.exists(File(context.filesDir, name).toPath(), LinkOption.NOFOLLOW_LINKS))
            }
            assertSourcesOff()
            ownsPaths = true
        }
        override fun after() {
            if (!ownsPaths) return
            assertTrue("The isolated runtime must stop before removing only fixture-created stores", RuntimeService.canManageHistory())
            privatePaths.forEach { name ->
                val path = File(context.filesDir, name)
                if (Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) HistoryData.deleteTree(path.toPath())
                else Files.deleteIfExists(path.toPath())
            }
            ownsPaths = false
        }
    }
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture(providerAsset = "native-voice-privacy-provider.mjs")

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val transport = NativeLocalTransport()

    @Test fun firstVoiceSurvivesPrivacyActivationNewTaskAndRestartWhileUnknownHistoryStaysBlocked() = runBlocking {
        val microphonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val sourcePath = arguments.getString("audioAsrFixture", "")
        assertFalse("Pass the app-private pinned public SenseVoice zh.wav", sourcePath.isEmpty())
        val source = AudioTranscription.controlledFile(context.filesDir, context.cacheDir, sourcePath)
        assertEquals(PUBLIC_WAVE_SHA256, sha256(source.readBytes()))
        assertTrue(context.getSharedPreferences("product", 0).edit().putBoolean("allowAudioTranscription", true).commit())
        compose.waitUntil(30_000) {
            AudioTranscription.get(context).request(JSONObject().put("operation", "preparation"), "voice-privacy-fixture").optString("state") != "preparing"
        }
        assertTrue("Bundled offline ASR must be ready without downloads", NativeSpeechClient().catalog().ready)
        var work = currentWorkbench()
        awaitWorkbench(work)
        compose.onNodeWithTag("native-tab-workbench").performClick()
        assertNull(work.state.value.selectedSessionId)
        assertFalse(MemoryRuntime.get(context).settings().getBoolean("privacyEverActivated"))

        val wave = File(context.filesDir, "audio-captures/${UUID.randomUUID()}.wav")
        assertFalse(wave.exists())
        assertTrue(wave.parentFile!!.isDirectory || wave.parentFile!!.mkdirs())
        val recognition = UUID.randomUUID().toString()
        val speech = NativeSpeechClient()
        try {
            writeCaptureFormat(source, wave)
            val transcript = speech.transcribe(wave.readBytes(), recognition).text
            assertTrue("The real offline recognizer must return speech", transcript.length > 3)
            arguments.getString("audioAsrExpected", "").takeIf(String::isNotBlank)?.let { assertTrue(transcript, transcript.contains(it)) }

            // Sending from a blank draft exercises actual first-send creation, not goal materialization.
            val beforeActivation = sendVoice(work, wave, transcript, 1)
            assertFalse(File(context.filesDir, "droy-privacy/privacy.sqlite").exists())
            memory("settings", JSONObject().put("enabled", true).put("automaticExtraction", false))
            compose.waitUntil(30_000) { MemoryRuntime.get(context).status().getJSONObject("execution").optBoolean("ready") }
            assertTrue(MemoryRuntime.get(context).settings().getBoolean("privacyEverActivated"))
            assertSourcesOff()
            assertEquals(beforeActivation, sendVoice(work, wave, transcript, 2))
            assertEquals("known-clean", privacyState(beforeActivation))

            fixture.scenario.onActivity { work.createSession() }
            assertNull(work.state.value.selectedSessionId)
            val afterActivation = sendVoice(work, wave, transcript, 1)
            assertNotEquals(beforeActivation, afterActivation)
            assertEquals("known-clean", privacyState(afterActivation))
            fixture.capture("native-voice-privacy-new-task-${locale()}")

            fixture.scenario.onActivity { RuntimeService.requestStop(context) }
            compose.waitUntil(30_000) { RuntimeService.canManageHistory() }
            fixture.startRuntime()
            fixture.relaunch()
            work = currentWorkbench()
            awaitWorkbench(work)
            fixture.scenario.onActivity { work.selectSession(afterActivation) }
            compose.waitUntil(30_000) { work.state.value.sessionBaselineId == afterActivation && !work.state.value.loading }
            assertEquals("known-clean", privacyState(afterActivation))
            assertEquals(afterActivation, sendVoice(work, wave, transcript, 2))
            for (id in listOf(beforeActivation, afterActivation)) {
                val log = sessionEvents(id)
                assertEquals(listOf("permission/preset", "sandbox/mode", "approval/policy"), log.take(3).map { it.getString("type") })
                val endings = log.filter { it.optString("type") == "turn/end" }
                assertEquals(2, endings.size)
                assertTrue(endings.toString(), endings.all { it.getJSONObject("data").getJSONObject("reason").getString("kind") == "completed" })
            }

            // Imported history intentionally lacks provenance. Verify both the real guard and its native recovery message.
            val imported = transport.json("/droy/native/fixture/privacy-seed", "POST", JSONObject()).getJSONObject("result")
            assertTrue(imported.getBoolean("isSeeded"))
            val unknown = imported.getString("sessionId")
            assertNull(privacyState(unknown))
            fixture.scenario.onActivity { work.selectSession(unknown) }
            compose.waitUntil(30_000) { work.state.value.sessionBaselineId == unknown && !work.state.value.loading }
            fixture.scenario.onActivity { work.setDraft("Synthetic imported-history check"); work.send() }
            compose.waitUntil(30_000) { work.state.value.error == "PRIVACY_SESSION_UNKNOWN" }
            val failureCode = work.state.value.error
            assertEquals(0, modelCalls(unknown))
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale()))
            })
            val privacyError = localized.getString(R.string.native_error_privacy_unknown)
            compose.onNodeWithText(privacyError).assertIsDisplayed()
            compose.waitForIdle()
            fixture.capture("native-voice-privacy-unknown-${locale()}")
            compose.onNodeWithContentDescription(localized.getString(R.string.native_dismiss)).performClick()
            compose.waitUntil(10_000) { work.state.value.error == null }
            compose.onNodeWithText(privacyError).assertDoesNotExist()

            assertTrue("This pipeline fixture must never capture hardware audio", AudioCapture.get(context).uiStatus().isNull("active"))
            assertEquals(microphonePermission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            val evidence = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
            File(evidence, "native-voice-privacy-startup-${locale()}.json").writeText(JSONObject()
                .put("coverage", "offline_asr_original_attachment_first_task_privacy_activation_new_task_and_runtime_restart")
                .put("sourceSha256", PUBLIC_WAVE_SHA256).put("locale", locale()).put("microphoneOpened", false)
                .put("beforeActivation", JSONObject().put("sessionId", beforeActivation).put("state", privacyState(beforeActivation)).put("modelCalls", modelCalls(beforeActivation)))
                .put("afterActivation", JSONObject().put("sessionId", afterActivation).put("state", privacyState(afterActivation)).put("modelCalls", modelCalls(afterActivation)))
                .put("unknownSeed", JSONObject().put("blocked", true).put("modelCalls", modelCalls(unknown))
                    .put("code", failureCode).put("noticeDismissed", work.state.value.error == null))
                .put("externalModelRequests", 0).toString(2))
        } finally {
            speech.cancel(recognition)
            compose.waitUntil(30_000) { !AudioTranscription.get(context).hasActiveJobs() }
            assertTrue("Remove only this fixture's generated waveform", !wave.exists() || wave.delete())
        }
    }

    private suspend fun sendVoice(work: NativeWorkbenchViewModel, wave: File, transcript: String, expectedCalls: Int): String {
        val importId = UUID.randomUUID().toString()
        val admission = MediaInputStore.nativeDrafts(context)
        val manifest = admission.importCompletedWave(wave, importId)
        val attachment = NativeLocalAttachment.parse(JSONObject(manifest.getJSONArray("items").getJSONObject(0).toString()).put("importId", importId))
        assertArrayEquals(wave.readBytes(), NativeDraftMediaStore(context).bytes(attachment))
        try {
            val target = withContext(Dispatchers.Main) { work.mediaTarget() }
            val request = UUID.randomUUID().toString()
            val durationMs = (wave.length() - 44) * 1000 / (48000 * 2)
            val session = work.submitVoiceInput(target, request, transcript, listOf(attachment), durationMs)
            compose.waitUntil(30_000) {
                work.state.value.error != null || work.state.value.messages.count { it.role == "assistant" && it.text == REPLY && !it.streaming } >= expectedCalls
            }
            assertNull("Voice task failed before the local model response", work.state.value.error)
            assertEquals(expectedCalls, modelCalls(session))
            assertTrue(work.state.value.messages.any { it.requestId == request && it.text == transcript && it.attachments.size == 1 })
            compose.waitUntil(15_000) { !work.state.value.sending && !work.state.value.running && work.state.value.pendingRequestId == null }
            return session
        } finally { admission.complete(importId, "work") }
    }

    private fun currentWorkbench(): NativeWorkbenchViewModel {
        lateinit var work: NativeWorkbenchViewModel
        fixture.scenario.onActivity { work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java] }
        return work
    }
    private fun awaitWorkbench(work: NativeWorkbenchViewModel) {
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.models.isNotEmpty() }
    }
    private suspend fun memory(operation: String, args: JSONObject): JSONObject = transport.json("/droy/native/memory", "POST",
        JSONObject().put("operation", operation).put("args", args)).also { assertTrue(it.toString(), it.getBoolean("ok")) }.getJSONObject("result")
    private fun assertSourcesOff() {
        val config = MemoryRuntime.get(context).historyRequest(JSONObject().put("operation", "status")).getJSONObject("configuration")
        for (field in listOf("enabled", "activityEnabled", "pageTextEnabled", "deviceStateEnabled", "locationEnabled")) assertFalse(field, config.getBoolean(field))
    }
    private fun privacyState(id: String): String? = SQLiteDatabase.openDatabase(File(context.filesDir, "droy-privacy/privacy.sqlite").path,
        null, SQLiteDatabase.OPEN_READONLY).use { db -> db.rawQuery("SELECT state FROM sessions WHERE session_id=?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } }
    private fun sessionEvents(id: String): List<JSONObject> = File(fixture.home, "sessions").walkTopDown()
        .single { it.name == "session.v4.jsonl" && it.parentFile?.name == id }.readLines().filter(String::isNotBlank).map(::JSONObject).drop(1)
    private fun modelCalls(id: String): Int = File(fixture.home, "native-fixture-calls.jsonl").takeIf(File::isFile)?.readLines()
        ?.filter(String::isNotBlank)?.map(::JSONObject)?.count { it.optString("sessionId") == id && !it.optBoolean("title") } ?: 0
    private fun locale() = arguments.getString("nativeFixtureLocale", "en")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun writeCaptureFormat(source: File, output: File) {
        PcmWavFile(output, 48000, 1).use { wave -> AudioTranscriptionDecoder.decode(source, { samples, rate ->
            assertEquals(16000, rate)
            val pcm = ByteArray(samples.size * 6)
            samples.forEachIndexed { index, sample ->
                val value = (sample * 32768f).toInt().coerceIn(-32768, 32767)
                repeat(3) { repeated -> val offset = index * 6 + repeated * 2; pcm[offset] = value.toByte(); pcm[offset + 1] = (value shr 8).toByte() }
            }
            wave.append(pcm)
        }, {}) }
    }
    companion object {
        private const val PUBLIC_WAVE_SHA256 = "b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373"
        private const val REPLY = "Native DSH reply received."
        private val privatePaths = listOf("droy-memory", "droy-memory.identity.json", "droy-privacy", "droy-privacy.identity.json",
            "droy-privacy-marker.json", "droy-history-spool", "droy-memory-archives", "droy-execution")
    }
}

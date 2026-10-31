package io.droyagent.app.nativeui.voice

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.permissions.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeVoiceCoordinatorTest {
    @Test fun failedCredentialStepKeepsCommittedSettingsAndRetriesOnlyTheCredential() = runBlocking {
        val (model, gateway) = fixture(this)
        val draft = gateway.latest.tts.copy(provider = "openai", url = "https://speech.invalid/v1", model = "fixture-tts")
        model.onAction(NativeVoiceAction.EditTts(draft)); gateway.failCredential = true
        model.onAction(NativeVoiceAction.SaveTts("fictional-private-credential")); yield()
        assertEquals("openai", model.state.value.snapshot!!.tts.provider)
        assertTrue(model.state.value.credentialRetry); assertEquals("voice_credential_retry", model.state.value.error)
        assertFalse(model.state.value.toString().contains("fictional-private-credential"))
        gateway.failCredential = false
        model.onAction(NativeVoiceAction.RetryCredential("fictional-private-credential")); yield()
        assertEquals(1, gateway.settingsWrites); assertEquals(2, gateway.keyWrites.size)
        assertTrue(model.state.value.snapshot!!.hasKey); assertEquals(1, model.state.value.credentialVersion)
        assertFalse(NativeVoiceAction.SaveTts("fictional-private-credential").toString().contains("fictional-private-credential"))
    }

    @Test fun blankCredentialLeavesTheSavedKeyAloneAndClearingIsExplicit() = runBlocking {
        val (model, gateway) = fixture(this)
        gateway.latest = gateway.latest.copy(hasKey = true)
        model.onAction(NativeVoiceAction.SaveTts("")); yield()
        assertTrue(gateway.keyWrites.isEmpty()); assertTrue(model.state.value.snapshot!!.hasKey)
        model.onAction(NativeVoiceAction.ClearCredential); yield()
        assertEquals(listOf<String?>(null), gateway.keyWrites); assertFalse(model.state.value.snapshot!!.hasKey)
    }

    @Test fun failedConfigurationCannotProceedToTheCredentialStep() = runBlocking {
        val (model, gateway) = fixture(this); gateway.failSettings = true
        val draft = gateway.latest.tts.copy(voiceId = "new-voice")
        model.onAction(NativeVoiceAction.EditTts(draft)); model.onAction(NativeVoiceAction.SaveTts("fictional-private-credential")); yield()
        assertTrue(gateway.keyWrites.isEmpty()); assertEquals("", model.state.value.snapshot!!.tts.voiceId)
        assertEquals(draft, model.state.value.ttsDraft); assertNotNull(model.state.value.error)
    }

    @Test fun nativeWakeApplicationIsNotPresentedAsFullSaveAndRetryDoesNotReapplyIt() = runBlocking {
        val (model, gateway) = fixture(this); gateway.failWakeSave = true
        model.onAction(NativeVoiceAction.SetWakeEnabled(true)); yield()
        assertTrue(model.state.value.snapshot!!.actualWake!!.enabled); assertFalse(model.state.value.snapshot!!.savedWake.enabled)
        assertTrue(model.state.value.wakeSyncRetry); assertEquals("NATIVE_VOICE_WAKE_NOT_SAVED", model.state.value.error)
        gateway.failWakeSave = false; model.onAction(NativeVoiceAction.RetryWakeSync); yield()
        assertEquals(1, gateway.nativeWakeWrites); assertTrue(model.state.value.snapshot!!.savedWake.enabled)
        assertFalse(model.state.value.wakeSyncRetry)
    }

    @Test fun leavingWaitsForItsPreviewStartThenStopsOnlyThatToken() = runBlocking {
        val (model, gateway) = fixture(this); val start = CompletableDeferred<Unit>(); gateway.previewGate = start
        model.onAction(NativeVoiceAction.Preview); yield(); val owner = gateway.previewStarts.single()
        model.leave(); yield(); assertTrue(gateway.previewStops.isEmpty())
        start.complete(Unit); yield(); yield()
        assertEquals(listOf(owner), gateway.previewStops); assertFalse(model.state.value.ownPreview)
    }

    @Test fun anOldPageCleanupCannotStopThePreviewStartedAfterReturning() = runBlocking {
        val (model, gateway) = fixture(this)
        model.onAction(NativeVoiceAction.Preview); yield(); val old = gateway.previewStarts.single()
        val stop = CompletableDeferred<Unit>(); gateway.stopGate = stop
        model.leave(); yield()
        model.onAction(NativeVoiceAction.Preview); yield(); val current = gateway.previewStarts.last()
        assertNotEquals(old, current); stop.complete(Unit); yield(); yield()
        assertEquals(listOf(old), gateway.previewStops); assertEquals(current, gateway.latest.previewOwner)
        assertTrue(model.state.value.ownPreview)
    }

    @Test fun unconfirmedStopDoesNotClaimThePreviewStopped() = runBlocking {
        val (model, gateway) = fixture(this)
        model.onAction(NativeVoiceAction.Preview); yield(); gateway.failStop = true
        model.leave(); yield()
        assertTrue(model.state.value.ownPreview); assertEquals("voice_stop_failed", model.state.value.error)
    }

    @Test fun productChoiceAndActualRecordingGrantRemainSeparateFacts() = runBlocking {
        val (model, _) = fixture(this)
        model.permissions(NativePermissionsState(fresh = true, toolsPaused = true,
            products = listOf(NativeProductPermission("audioMicrophone", "allowAudioMicrophone", "fixture", "fixture", true)),
            system = listOf(NativeSystemPermission("microphone", NativePermissionGrant.NOT_GRANTED))))
        val status = model.state.value.permissions
        assertTrue(status.microphoneProduct); assertFalse(status.microphoneSystem); assertFalse(status.transcriptionProduct); assertTrue(status.paused)
    }

    @Test fun unavailableStatesStayUnknownAndInvalidQwenRateIsRejected() {
        val wire = wire(); wire.put("runtime", JSONObject().put("available", false)); wire.put("voices", JSONObject().put("available", false))
        wire.put("preview", JSONObject().put("available", false).put("active", false))
        val value = NativeVoiceJson.parse(wire)
        assertFalse(value.runtimeAvailable); assertFalse(value.voicesAvailable); assertFalse(value.systemReady); assertFalse(value.previewAvailable)
        wire.getJSONObject("config").getJSONObject("tts").put("provider", "qwen3").put("rate", 1.15)
        assertThrows(NativeApiException::class.java) { NativeVoiceJson.parse(wire) }
    }

    @Test fun activeDownloadsPollUntilActualReadyAndSystemReadinessIsNotInvented() = runBlocking {
        val (model, gateway) = fixture(this)
        assertFalse(model.state.value.ttsReady)
        gateway.latest = gateway.latest.copy(resources = listOf(NativeVoiceResource("melo", NativeVoiceProgress(true, "downloading", false, 1, 20))))
        model.refresh(); yield(); assertTrue(model.state.value.needsPolling)
        gateway.latest = gateway.latest.copy(resources = listOf(NativeVoiceResource("melo", NativeVoiceProgress(true, "ready", true, 20, 20))))
        model.refresh(); yield(); assertFalse(model.state.value.needsPolling)
    }

    @Test fun switchingProvidersRetainsIndependentDraftsAndDiscardRestoresSavedValues() = runBlocking {
        val (model, gateway) = fixture(this)
        val saved = gateway.latest.tts
        model.onAction(NativeVoiceAction.SelectProvider("openai"))
        val custom = model.state.value.ttsDraft!!.copy(url = "https://voice.example.test/v1", model = "test-voice", voiceId = "calm")
        model.onAction(NativeVoiceAction.EditTts(custom))
        model.onAction(NativeVoiceAction.SelectProvider("qwen3"))
        assertEquals(1.0, model.state.value.ttsDraft!!.rate, 0.0)
        model.onAction(NativeVoiceAction.SelectProvider("openai"))
        assertEquals(custom, model.state.value.ttsDraft)
        model.onAction(NativeVoiceAction.DiscardEdits)
        assertEquals(saved, model.state.value.ttsDraft)
    }

    private suspend fun fixture(scope: CoroutineScope): Pair<NativeVoiceCoordinator, FakeGateway> {
        val gateway = FakeGateway(); val model = NativeVoiceCoordinator(gateway, scope)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh(); yield(); return model to gateway
    }
    private class FakeGateway : NativeVoiceGateway {
        var latest = NativeVoiceJson.parse(wire())
        var settingsWrites = 0; val keyWrites = mutableListOf<String?>(); var nativeWakeWrites = 0
        var failSettings = false; var failCredential = false; var failWakeSave = false; var failStop = false
        var previewGate: CompletableDeferred<Unit>? = null; var stopGate: CompletableDeferred<Unit>? = null
        val previewStarts = mutableListOf<String>(); val previewStops = mutableListOf<String>()
        override suspend fun read() = latest
        override suspend fun standard(enabled: Boolean) { latest = latest.copy(standardEntry = enabled) }
        override suspend fun saveTts(draft: NativeVoiceTtsDraft) {
            settingsWrites++; if (failSettings) throw NativeApiException("NATIVE_VOICE_INVALID")
            latest = latest.copy(tts = draft)
        }
        override suspend fun credential(value: String?) {
            keyWrites.add(value); if (failCredential) throw NativeApiException("NATIVE_VOICE_CREDENTIAL_NOT_CONFIRMED")
            latest = latest.copy(hasKey = value != null)
        }
        override suspend fun saveWake(draft: NativeVoiceWakeDraft, commitOnly: Boolean) {
            if (!commitOnly) { nativeWakeWrites++; latest = latest.copy(actualWake = draft) }
            if (failWakeSave) throw NativeApiException("NATIVE_VOICE_WAKE_NOT_SAVED")
            latest = latest.copy(savedWake = draft)
        }
        override suspend fun resource(provider: String, cancel: Boolean) = Unit
        override suspend fun prepareWake() = Unit
        override suspend fun preview(owner: String, draft: NativeVoiceTtsDraft) {
            previewStarts.add(owner); previewGate?.await(); latest = latest.copy(previewOwner = owner, previewPhase = "playing")
        }
        override suspend fun stopPreview(owner: String) {
            stopGate?.await(); if (failStop) throw NativeApiException("NATIVE_VOICE_FAILED")
            previewStops.add(owner); if (latest.previewOwner == owner) latest = latest.copy(previewOwner = null, previewPhase = null)
        }
    }
    companion object {
        private fun wire() = JSONObject("""{
          "config":{"entries":{"standard":true},"tts":{"provider":"system","voiceId":"","rate":1,"url":"","model":"tts-1","hasKey":false},
            "wake":{"enabled":false,"timing":"screen_on","useCompanionName":false,"chinese":"你好测试","english":"Hey Test"}},
          "runtime":{"available":true,"state":"idle","speaking":false,"failed":false},
          "wake":{"available":true,"enabled":false,"timing":"screen_on","useCompanionName":false,"chinese":"你好测试","english":"Hey Test",
            "state":"off","failed":false,"screenAvailable":true,"suspended":false,"pausedForVoice":false,"voiceClaimed":false,"companionName":"fixture",
            "preparation":{"available":true,"state":"ready","ready":true,"downloadedBytes":1,"totalBytes":1,"failed":false}},
          "voices":{"available":true,"systemReady":false,"failed":false,"items":[]},"resources":{"available":true,"items":[]},
          "asr":{"available":true,"state":"ready","ready":true,"downloadedBytes":1,"totalBytes":1,"failed":false},
          "preview":{"available":true,"active":false}
        }""")
    }
}

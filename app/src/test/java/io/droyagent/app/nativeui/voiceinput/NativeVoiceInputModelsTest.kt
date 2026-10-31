package io.droyagent.app.nativeui.voiceinput

import io.droyagent.app.nativeui.media.NativeWorkbenchMediaTarget
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

class NativeVoiceInputModelsTest {
    @Test fun releasedVoiceBelongsInTimelineThroughoutOptionalRecognitionAndUpload() {
        val recording = NativeVoiceInputState(operationId = "voice", phase = NativeVoiceInputPhase.RECORDING, durationMs = 3000)
        assertFalse(recording.showPendingBubble)
        for (phase in listOf(NativeVoiceInputPhase.STOPPING, NativeVoiceInputPhase.RECOGNIZING, NativeVoiceInputPhase.READY, NativeVoiceInputPhase.SENDING)) {
            assertTrue(phase.name, recording.copy(phase = phase).showPendingBubble)
            assertTrue("Navigation must not hide an already submitted original", recording.copy(phase = phase, visible = false).showPendingBubble)
        }
        assertFalse(recording.copy(phase = NativeVoiceInputPhase.CANCELLING).showPendingBubble)
        assertFalse(recording.copy(phase = NativeVoiceInputPhase.SENT, accepted = true).showPendingBubble)
        assertFalse(recording.copy(phase = NativeVoiceInputPhase.INTERRUPTED).showPendingBubble)
    }

    @Test fun accidentalTapsNeverSubmitAudio() {
        assertFalse(isVoiceCaptureLongEnough(0))
        assertFalse(isVoiceCaptureLongEnough(299))
        assertTrue(isVoiceCaptureLongEnough(300))
    }

    @Test fun onlyAnActualDurationLimitFinishesAHeldGestureWithoutAnotherUserRelease() {
        assertTrue(mayAutoFinishVoiceCapture("completed", "duration_limit", true))
        for (reason in listOf("user_stop", "runtime_stopped", "operations_paused", "permission_revoked", "process_interrupted", "unknown"))
            assertFalse(reason, mayAutoFinishVoiceCapture("completed", reason, true))
        assertFalse(mayAutoFinishVoiceCapture("interrupted", "duration_limit", true))
        assertFalse(mayAutoFinishVoiceCapture("completed", "duration_limit", false))
    }
    @Test fun grantingPermissionNeverArmsARecordingAndAPendingOriginalBlocksANewCapture() {
        val target = NativeVoiceInputTarget(workbench = NativeWorkbenchMediaTarget("draft-a", null, "space-a"))
        val blocked = NativeVoiceInputState(visible = true, target = target, catalog = NativeSpeechCatalog(4 * 1024 * 1024, 120.0, 20 * 1024 * 1024, true))
        val granted = blocked.copy(permissions = NativeVoiceInputPermissions(true, true, true, false, true))
        assertTrue(granted.canRecord)
        assertFalse(granted.recording)
        assertNull(granted.operationId)
        assertFalse(granted.copy(operationId = "original-capture", phase = NativeVoiceInputPhase.INTERRUPTED, fileReady = true).canRecord)
        assertEquals(target, NativeVoiceInputTarget.parse(target.json()))
    }

    @Test fun stoppedCaptureIsNotQuiescentWhileRecognitionOrSendRemainsUnconfirmed() {
        val stopped = NativeVoiceInputState(operationId = "fixture", captureQuiescent = true, recognitionQuiescent = false, fileReady = true,
            phase = NativeVoiceInputPhase.CANCELLING)
        assertFalse(stopped.quiescent)
        assertFalse(stopped.canRecord)
        assertFalse(stopped.canRetry)
        assertTrue(stopped.copy(recognitionQuiescent = true, phase = NativeVoiceInputPhase.READY).quiescent)
        assertFalse(stopped.copy(recognitionQuiescent = true, phase = NativeVoiceInputPhase.SENDING).quiescent)
    }

    @Test fun aRetainedRecordingCanBeHandledWithoutNewMicrophoneOrTranscriptionAuthorization() {
        val retained = NativeVoiceInputState(operationId = "retained", phase = NativeVoiceInputPhase.INTERRUPTED, fileReady = true,
            permissions = NativeVoiceInputPermissions(false, false, false, true, false))
        assertTrue(retained.canRetry)
        assertFalse(retained.canRecognize)
        assertFalse(retained.canRecord)
        val transcription = retained.copy(permissions = NativeVoiceInputPermissions(false, false, true, false, true),
            catalog = NativeSpeechCatalog(4000000, 120.0, 20000000, true))
        assertTrue(transcription.canRecognize)
        assertFalse(transcription.canRecord)
        assertFalse(transcription.copy(sendAttempted = true).canRecognize)
    }

    @Test fun captureDurationAccountsForOriginalAndCanonicalRecognitionBudgets() {
        assertEquals(60000L, NativeSpeechCatalog(4 * 1024 * 1024, 120.0, 20 * 1024 * 1024, true).captureLimitMs)
        val small = NativeSpeechCatalog(32044, 120.0, 20 * 1024 * 1024, true)
        assertEquals(1000L, small.captureLimitMs)
        assertEquals(1500L, NativeSpeechCatalog(64044, 1.5, 20 * 1024 * 1024, true).captureLimitMs)
    }

    @Test fun originalRecordingDoesNotDependOnRecognitionResourcesOrConsent() {
        val target = NativeVoiceInputTarget(companionId = "companion")
        val originalOnly = NativeVoiceInputState(visible = true, target = target,
            permissions = NativeVoiceInputPermissions(true, true, false, false, true))
        assertTrue(originalOnly.canRecord)
        assertFalse(originalOnly.canRecognize)
        assertTrue(originalOnly.copy(catalog = NativeSpeechCatalog(4000000, 120.0, 20000000, false)).canRecord)
        assertFalse(originalOnly.copy(permissions = originalOnly.permissions.copy(microphoneAllowed = false)).canRecord)
        assertFalse(originalOnly.copy(permissions = originalOnly.permissions.copy(paused = true)).canRecord)
    }

    @Test fun missingRecognizerDoesNotShortenSafeOriginalRecording() {
        assertEquals(60000L, NativeSpeechCatalog(32044, 1.0, 20000000, false).captureLimitMs)
        assertEquals(1000L, NativeSpeechCatalog(32044, 1.0, 20000000, true).captureLimitMs)
        assertEquals(1000L, NativeSpeechCatalog(32044, 1.0, 96044, false).captureLimitMs)
    }

    @Test fun silenceAndRecognizerFailureLeaveOriginalSendAvailableWithoutInventedWords() = runBlocking {
        val silence = NativeSpeechResult("", 3.0, .1)
        assertEquals(silence, recognizeVoiceOrOriginal({ silence }, { fail("Completed recognition needs no cancellation") }))
        var settled = false
        assertNull(recognizeVoiceOrOriginal({ throw IllegalStateException("recognizer failed") }, { settled = true }))
        assertTrue("A failed recognizer must settle before sending the original", settled)
    }

    @Test fun cancelledOrUnsettledRecognitionCannotFallThroughToAutomaticSend() = runBlocking {
        val cancelled = CancellationException("user cancelled")
        try {
            recognizeVoiceOrOriginal({ throw cancelled }, { fail("User cancellation belongs to the caller") })
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        val unsettled = IllegalStateException("recognizer still owns audio")
        try {
            recognizeVoiceOrOriginal({ throw IllegalStateException("ASR failed") }, { throw unsettled })
            fail("Unsettled ownership must propagate")
        } catch (actual: IllegalStateException) { assertSame(unsettled, actual) }
    }

    @Test fun recognitionTagsAndSilenceNeverBecomeAnInventedMessage() {
        assertEquals("你好", cleanVoiceTranscript("<|zh|><|NEUTRAL|>你好"))
        assertEquals("", cleanVoiceTranscript("<|en|> ... 🎵"))
        assertEquals("1 2", cleanVoiceTranscript("1 2"))
    }
}

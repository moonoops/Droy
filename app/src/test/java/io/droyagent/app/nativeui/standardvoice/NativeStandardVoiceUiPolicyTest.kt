package io.droyagent.app.nativeui.standardvoice

import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceUiPolicyTest {
    @Test fun unknownAndSubmittingOnlyOfferReconciliationAndCopyEvenWhenInsertionIsAllowed() {
        for (delivery in listOf(NativeStandardVoiceDelivery.UNKNOWN, NativeStandardVoiceDelivery.SUBMITTING)) {
            val actions = standardVoiceRetainedActions(delivery, canInsert = true)
            assertTrue(actions.reconcile); assertTrue(actions.copy)
            assertFalse(actions.retry); assertFalse(actions.insert); assertFalse(actions.discard)
        }
    }

    @Test fun onlyReadyOrExplicitlyNotAcceptedTextMayRetryInsertOrDiscard() {
        for (delivery in listOf(NativeStandardVoiceDelivery.READY, NativeStandardVoiceDelivery.NOT_ACCEPTED)) {
            val safe = standardVoiceRetainedActions(delivery, canInsert = false)
            assertTrue(safe.retry); assertTrue(safe.discard); assertFalse(safe.insert)
            assertTrue(standardVoiceRetainedActions(delivery, canInsert = true).insert)
        }
        for (delivery in listOf(NativeStandardVoiceDelivery.ACCEPTED, NativeStandardVoiceDelivery.DISCARDED)) {
            assertEquals(StandardVoiceRetainedActions(false, false, false, false, false), standardVoiceRetainedActions(delivery, true))
        }
    }

    @Test fun idleAndEndedShowStartButUnresolvedTextOrWakeCannotStartAnotherConversation() {
        assertTrue(standardVoiceUiControls(NativeStandardVoiceState()).canStart)
        val unresolved = active().copy(phase = NativeStandardVoicePhase.ENDED,
            journal = journal().copy(submissions = listOf(NativeStandardVoiceSubmission("utterance", "nonce", "saved", NativeStandardVoiceDelivery.UNKNOWN))))
        assertTrue(standardVoiceUiControls(unresolved).showStart); assertFalse(standardVoiceUiControls(unresolved).canStart)
        val wake = unresolved.copy(journal = journal().copy(captureId = null, startAttempted = false,
            wake = NativeStandardVoiceWake(1, NativeStandardVoiceWakePhase.CONSUMED)))
        assertFalse(standardVoiceUiControls(wake).canStart); assertTrue(standardVoiceUiControls(wake).canResumeWake)
    }

    @Test fun anotherOperationWithTheSameTargetAndCaptureCannotExposeRecordingControls() {
        val state = active().copy(native = native().copy(voiceOperationId = "00000000-0000-0000-0000-000000000099"))
        val controls = standardVoiceUiControls(state)
        assertFalse(controls.showMicrophone); assertFalse(controls.canReadAloud)
        assertFalse(controls.canStopReadout); assertFalse(controls.canRequestPermission)
    }

    @Test fun recoveryNeverOffersAutomaticResumeButCanCheckAndEndTheOwnedConversation() {
        val controls = standardVoiceUiControls(active().copy(phase = NativeStandardVoicePhase.RECOVERED, recovered = true, userMuted = true))
        assertFalse(controls.showMicrophone); assertFalse(controls.canResumeMicrophone); assertFalse(controls.canReadAloud)
        assertTrue(controls.needsReconcile); assertTrue(controls.canEnd)
    }

    @Test fun userPauseAndAutomaticReadoutPauseRemainDifferentAndPendingTextBlocksResume() {
        val automatic = standardVoiceUiControls(active().copy(automaticMuted = true, native = native().copy(muted = true)))
        assertTrue(automatic.canPauseMicrophone)
        val pending = active().copy(userMuted = true, journal = journal().copy(submissions = listOf(
            NativeStandardVoiceSubmission("utterance", "nonce", "saved", NativeStandardVoiceDelivery.READY))))
        assertFalse(standardVoiceUiControls(pending).canResumeMicrophone)
        assertFalse(standardVoiceUiControls(active().copy(userMuted = true, outputUnconfirmed = true)).canResumeMicrophone)
    }

    @Test fun permissionIsOnlyOfferedForTheActualOwnedAwaitingMicrophoneCapture() {
        val awaiting = active().copy(phase = NativeStandardVoicePhase.AWAITING_PERMISSION,
            native = native().copy(state = "awaiting_user", needsPermission = "microphone"))
        assertTrue(standardVoiceUiControls(awaiting).canRequestPermission)
        assertFalse(standardVoiceUiControls(awaiting).showMicrophone)
        assertFalse(standardVoiceUiControls(awaiting.copy(native = awaiting.native!!.copy(needsPermission = null))).canRequestPermission)
        assertFalse(standardVoiceUiControls(awaiting.copy(phase = NativeStandardVoicePhase.FOREIGN)).canRequestPermission)
    }

    @Test fun aConsumedWakeCanOnlyContinueBeforeAnyStartAttemptOrStopIntent() {
        for (phase in NativeStandardVoiceWakePhase.entries) {
            val state = NativeStandardVoiceState(phase = NativeStandardVoicePhase.RECOVERED, journal = journal().copy(
                captureId = null, startAttempted = false, wake = NativeStandardVoiceWake(1, phase)))
            assertEquals(phase == NativeStandardVoiceWakePhase.CONSUMED, standardVoiceUiControls(state).canResumeWake)
            assertFalse(standardVoiceUiControls(state.copy(journal = state.journal!!.copy(startAttempted = true))).canResumeWake)
            assertFalse(standardVoiceUiControls(state.copy(journal = state.journal!!.copy(stopRequested = true))).canResumeWake)
        }
    }

    @Test fun anUnconfirmedStopCanBeExplicitlyRetriedWhileCaptureResumeRemainsBlocked() {
        val state = active().copy(phase = NativeStandardVoicePhase.UNCONFIRMED, journal = journal().copy(stopRequested = true), error = "network_error")
        assertTrue(standardVoiceUiControls(state).canEnd)
        assertFalse(standardVoiceUiControls(state).canResumeMicrophone)
        assertTrue(standardVoiceUiControls(state.copy(phase = NativeStandardVoicePhase.STOPPING)).canEnd)
        assertFalse(standardVoiceUiControls(state.copy(phase = NativeStandardVoicePhase.STOPPING, error = null)).canEnd)
    }

    companion object {
        private const val OPERATION = "00000000-0000-0000-0000-000000000001"
        private fun journal() = NativeStandardVoiceJournal(OPERATION, "host-epoch", NativeStandardVoiceTarget("draft", "session", "space", "token"),
            captureId = "capture", startAttempted = true)
        private fun native() = NativeStandardVoiceNativeState("recording", "standard", "session", "capture", "transcript", false,
            false, null, 0.1f, false, OPERATION)
        private fun active() = NativeStandardVoiceState(phase = NativeStandardVoicePhase.ACTIVE, journal = journal(), native = native())
    }
}

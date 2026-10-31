package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceCoordinatorTest {
    @Test fun disabledVoiceEntryNeverConsumesAWakeOrAllocatesItsFreshDraft() = runBlocking {
        val f = Fixture(this); f.gateway.standardEnabled = false; f.gateway.wake = wake().copy(enabled = true, eventId = 12)
        f.model.startFromWake(12)
        assertEquals(0, f.gateway.consumeCalls); assertTrue(f.bindings.freshRequests.isEmpty()); assertTrue(f.gateway.starts.isEmpty())
        assertNull(f.model.state.value.journal); assertEquals("native_standard_voice_disabled", f.model.state.value.error)
    }
    @Test fun endFencesAStalePollBeforeTheSerializedPollReturnsAndNeverSendsItsFinal() = runBlocking {
        val f = Fixture(this); f.model.start()
        f.gateway.pollGate = CompletableDeferred()
        f.gateway.rows = listOf(segment("s1", "u1", "late final", true))
        val poll = launch { f.model.pollOnce() }; yield()
        val stop = launch { f.model.stop() }; yield()
        assertEquals("The owned capture stops without waiting for the old poll", 1, f.gateway.stopCalls)
        assertTrue(f.gateway.actual.stopped)
        f.gateway.pollGate!!.complete(Unit); poll.join(); stop.join()
        assertTrue(f.bindings.sent.isEmpty()); assertEquals(NativeStandardVoicePhase.ENDED, f.model.state.value.phase)
    }

    @Test fun endFencesAnInFlightStartWithoutWaitingForItsResponseAndANewOperationCanStart() = runBlocking {
        val f = Fixture(this); f.gateway.startGate = CompletableDeferred()
        val start = launch { f.model.start() }; yield()
        val stop = launch { f.model.stop() }; yield()
        assertEquals(1, f.gateway.stopCalls); assertTrue(f.gateway.actual.stopped)
        f.gateway.startGate!!.complete(Unit); start.join(); stop.join()
        assertEquals(NativeStandardVoicePhase.ENDED, f.model.state.value.phase)
        f.gateway.startGate = null; f.model.start()
        assertEquals(NativeStandardVoicePhase.ACTIVE, f.model.state.value.phase)
    }
    @Test fun openingCapturesTheBlankDraftButDoesNotCreateOrSubmitADshSession() = runBlocking {
        val f = Fixture(this); f.model.start()
        assertEquals(listOf("blank-draft"), f.gateway.starts)
        assertTrue(f.bindings.sent.isEmpty()); assertEquals(NativeStandardVoicePhase.ACTIVE, f.model.state.value.phase)
        assertFalse(f.model.state.value.readAloud)
        assertTrue(f.gateway.calls.indexOf("status") < f.gateway.calls.indexOf("start"))
    }

    @Test fun invalidReadOnlyOrUnownedTargetsAreRejectedBeforeRecordingWithoutFallback() = runBlocking {
        val f = Fixture(this); f.bindings.writableAndOwned = false; f.model.start()
        assertTrue(f.gateway.starts.isEmpty()); assertEquals(listOf(false), f.bindings.freshRequests)
        assertEquals("native_standard_voice_draft_changed", f.model.state.value.error)
    }

    @Test fun anActiveSameSessionWithoutTheCaptureReceiptIsNeverAdoptedOrStopped() = runBlocking {
        val f = Fixture(this); f.gateway.actual = recording()
        f.model.start(); f.model.stop()
        assertTrue(f.gateway.starts.isEmpty()); assertEquals(0, f.gateway.stopCalls)
        assertEquals("native_standard_voice_owner", f.model.state.value.error)
    }

    @Test fun aSameSessionStartRaceIsRejectedByTheAtomicOperationIdAndNeverAdopted() = runBlocking {
        val f = Fixture(this); f.gateway.takeoverBeforeOperation = "start"; f.model.start()
        assertEquals("VOICE_OWNER_MISMATCH", f.model.state.value.error)
        assertEquals(NativeStandardVoicePhase.FOREIGN, f.model.state.value.phase)
        assertNull(f.model.state.value.journal!!.captureId)
        assertEquals(OTHER_OPERATION, f.gateway.actual.voiceOperationId)
        f.model.reconcile(); f.model.stop(); assertEquals(0, f.gateway.stopCalls)
        assertEquals(OTHER_OPERATION, f.gateway.actual.voiceOperationId)
    }

    @Test fun aLostStartCanNeverRecoverAnotherOperationEvenWithTheSameSessionAndCaptureId() = runBlocking {
        val f = Fixture(this); f.gateway.failStart = true; f.model.start()
        f.gateway.actual = f.gateway.actual.copy(voiceOperationId = OTHER_OPERATION)
        f.model.reconcile(); f.model.pollOnce()
        assertNull(f.model.state.value.journal!!.captureId)
        assertEquals(NativeStandardVoicePhase.FOREIGN, f.model.state.value.phase)
        assertEquals(0, f.gateway.pollCalls)
    }

    @Test fun aPollRaceCarriesTheOriginalOperationAndCannotReadTheNewOwnersAsr() = runBlocking {
        val f = Fixture(this); f.model.start(); f.gateway.takeoverBeforeOperation = "poll"
        f.model.pollOnce()
        assertEquals("poll" to FIRST_OPERATION, f.gateway.operationIds.last())
        assertEquals(0, f.gateway.pollCalls); assertTrue(f.bindings.sent.isEmpty())
        assertEquals("VOICE_OWNER_MISMATCH", f.model.state.value.error)
        assertEquals(NativeStandardVoicePhase.FOREIGN, f.model.state.value.phase)
    }

    @Test fun aMuteRaceCannotPauseTheNewOwnersMicrophone() = runBlocking {
        val f = Fixture(this); f.model.start(); f.gateway.takeoverBeforeOperation = "mute"
        f.model.setUserMuted(true)
        assertEquals("mute" to FIRST_OPERATION, f.gateway.operationIds.last())
        assertFalse(f.gateway.actual.muted); assertEquals(OTHER_OPERATION, f.gateway.actual.voiceOperationId)
        assertEquals("VOICE_OWNER_MISMATCH", f.model.state.value.error)
        assertEquals(NativeStandardVoicePhase.FOREIGN, f.model.state.value.phase)
    }

    @Test fun aLateOldStopAfterTheStatusCheckCannotStopTheNewOwnersCaptureOrSpeech() = runBlocking {
        val f = Fixture(this); f.model.start(); f.gateway.takeoverBeforeOperation = "stop"
        f.model.stop()
        assertEquals("stop" to FIRST_OPERATION, f.gateway.operationIds.last())
        assertEquals(0, f.gateway.stopCalls); assertTrue(f.gateway.actual.speaking)
        assertEquals(OTHER_OPERATION, f.gateway.actual.voiceOperationId)
        assertEquals(NativeStandardVoicePhase.FOREIGN, f.model.state.value.phase)
        assertEquals("VOICE_OWNER_MISMATCH", f.model.state.value.error)
    }

    @Test fun lateOldReadoutAndSpeechStopCannotChangeTheNewOwnersOutput() = runBlocking {
        val f = Fixture(this); f.model.start(); f.acceptFinal("first"); f.model.setReadAloud(true)
        f.gateway.takeoverBeforeOperation = "speak"
        f.model.onReplyEvent(bound("turn-1", f.bindings.sent.single().second))
        f.model.onReplyEvent(text("turn-1", "a", "e1", "旧回复。", true)); yield(); yield()
        assertTrue(f.gateway.spoken.isEmpty()); assertTrue(f.gateway.actual.speaking)
        assertEquals("speak" to FIRST_OPERATION, f.gateway.operationIds.last())
        assertEquals("VOICE_OWNER_MISMATCH", f.model.state.value.error)
        val g = Fixture(this); g.model.start(); g.acceptFinal("first"); g.model.setReadAloud(true)
        g.model.onReplyEvent(bound("turn-1", g.bindings.sent.single().second)); g.model.onReplyEvent(text("turn-1", "a", "e1", "已排队。", true)); yield(); yield()
        g.gateway.takeoverBeforeOperation = "stop_speaking"; g.model.interruptOutput()
        assertEquals(0, g.gateway.stopSpeakingCalls); assertTrue(g.gateway.actual.speaking)
        assertEquals("stop_speaking" to FIRST_OPERATION, g.gateway.operationIds.last())
        assertEquals("VOICE_OWNER_MISMATCH", g.model.state.value.error)
    }

    @Test fun aLostStartResponseCanOnlyRecoverTheExactAtomicOwnerWithoutReplayingAsr() = runBlocking {
        val f = Fixture(this); f.gateway.failStart = true
        f.model.start(); f.model.reconcile(); f.model.pollOnce()
        assertEquals(NativeStandardVoicePhase.RECOVERED, f.model.state.value.phase)
        assertEquals("capture-1", f.model.state.value.journal!!.captureId)
        assertTrue(f.model.state.value.recovered); assertEquals(0, f.gateway.pollCalls)
        f.model.stop(); assertEquals(1, f.gateway.stopCalls)
    }

    @Test fun onlyTheFinalUtteranceIsSubmittedAndRepeatedPollsCannotDuplicateIt() = runBlocking {
        val f = Fixture(this); f.model.start()
        f.gateway.rows = listOf(segment("s1", "u1", "<|zh|>半句", false)); f.model.pollOnce()
        assertTrue(f.bindings.sent.isEmpty()); assertEquals("半句", f.model.state.value.partialTranscript)
        f.gateway.rows = listOf(segment("s2", "u1", "完成。", true)); f.model.pollOnce(); f.model.pollOnce()
        val sent = f.bindings.sent.single()
        assertEquals("半句完成。", sent.third); assertEquals("blank-draft", sent.first.draftId)
        assertEquals(sent.second, f.model.state.value.journal!!.submissions.single().nonce)
        assertEquals(NativeStandardVoiceDelivery.ACCEPTED, f.model.state.value.journal!!.submissions.single().delivery)
    }

    @Test fun completeChineseAndEnglishExitUtterancesStopVoiceWithoutSendingTheExitToDsh() = runBlocking {
        for (phrase in listOf("请结束语音对话，谢谢你。", "结束语音", "没事儿了", "Please end voice chat, thank you!", "That's all.")) {
            val f = Fixture(this); f.model.start()
            f.gateway.rows = listOf(segment("s1", "u1", phrase, true)); f.model.pollOnce()
            assertTrue(phrase, f.bindings.sent.isEmpty()); assertEquals(phrase, 1, f.gateway.stopCalls)
            assertEquals(NativeStandardVoicePhase.ENDED, f.model.state.value.phase)
            assertTrue(f.model.state.value.pending.isEmpty())
        }
    }

    @Test fun exitWordsInsideAnOrdinarySentenceAreSentNormallyAndPartialExitWordsDoNotStopVoice() = runBlocking {
        for (phrase in listOf("不要结束语音对话，继续工作", "解释一下停止对话是什么意思", "Tell me how to end voice chat")) {
            val f = Fixture(this); f.model.start(); f.acceptFinal(phrase)
            assertEquals(phrase, f.bindings.sent.single().third); assertEquals(0, f.gateway.stopCalls)
        }
        val f = Fixture(this); f.model.start()
        f.gateway.rows = listOf(segment("s1", "u1", "结束语音", false)); f.model.pollOnce()
        assertEquals(0, f.gateway.stopCalls); assertTrue(f.bindings.sent.isEmpty())
        f.gateway.rows = listOf(segment("s2", "u1", "对话。", true)); f.model.pollOnce()
        assertEquals(1, f.gateway.stopCalls); assertTrue(f.bindings.sent.isEmpty())
    }

    @Test fun voiceExitPreservesAnEarlierUnconfirmedFinalAndItsNonceWithoutResendingOrCancellingDsh() = runBlocking {
        val f = Fixture(this); f.model.start(); f.bindings.failSend = true
        f.gateway.rows = listOf(segment("s1", "u1", "先前请求的原文", true), segment("s2", "u2", "结束语音对话", true))
        f.model.pollOnce()
        val row = f.model.state.value.pending.single()
        assertEquals("先前请求的原文", row.text); assertEquals(NativeStandardVoiceDelivery.UNKNOWN, row.delivery)
        assertEquals(row.nonce, f.bindings.sent.single().second); assertEquals(1, f.bindings.sent.size)
        assertEquals("REJECTED", f.model.state.value.error); assertEquals(1, f.gateway.stopCalls)
        assertEquals(NativeStandardVoicePhase.ENDED, f.model.state.value.phase)
    }

    @Test fun draftChangesRetainEveryFinalInTheBatchAndMuteWithoutOverwritingOrSending() = runBlocking {
        val f = Fixture(this); f.model.start(); f.bindings.unchanged = false
        f.gateway.rows = listOf(segment("s1", "u1", "第一句", true), segment("s2", "u2", "第二句", true))
        f.model.pollOnce(); f.model.pollOnce()
        assertEquals(listOf("第一句", "第二句"), f.model.state.value.pending.map { it.text })
        assertTrue(f.model.state.value.userMuted); assertTrue(f.gateway.actual.muted); assertTrue(f.bindings.sent.isEmpty())
        assertEquals("draft-fingerprint", f.model.state.value.journal!!.target.draftToken)
    }

    @Test fun anInterruptedPollRetainsItsFinalTextButDoesNotDispatchIt() = runBlocking {
        val f = Fixture(this); f.model.start()
        f.gateway.rows = listOf(segment("s1", "u1", "结束前的最终转写", true))
        f.gateway.pollResult = recording().copy(state = "interrupted", failed = true)
        f.model.pollOnce()
        assertEquals("结束前的最终转写", f.model.state.value.pending.single().text)
        assertTrue(f.bindings.sent.isEmpty()); assertTrue(f.model.state.value.userMuted)
    }

    @Test fun unexpectedCaptureRotationIsForeignAndCannotBeMutedOrRead() = runBlocking {
        val f = Fixture(this); f.model.start()
        f.gateway.actual = f.gateway.actual.copy(captureId = "other-capture")
        f.model.pollOnce(); f.model.setUserMuted(true)
        assertEquals(0, f.gateway.pollCalls); assertFalse(f.gateway.actual.muted)
        assertEquals("native_standard_voice_owner", f.model.state.value.error)
    }

    @Test fun anAcknowledgedUnmuteMayRotateItsCaptureAndTranscriptWithoutReusingOldAsrSegments() = runBlocking {
        val f = Fixture(this); f.model.start(); f.model.setUserMuted(true)
        f.gateway.rotateOnUnmute = true; f.model.setUserMuted(false)
        assertEquals("capture-2", f.model.state.value.journal!!.captureId)
        f.gateway.rows = listOf(segment("s1", "u1", "新录音", true)); f.model.pollOnce()
        assertEquals("新录音", f.bindings.sent.single().third)
    }

    @Test fun lostSubmissionKeepsTheOriginalNonceAndTextAndUnknownCannotRetry() = runBlocking {
        val f = Fixture(this); f.model.start(); f.bindings.failSend = true
        f.gateway.rows = listOf(segment("s1", "u1", "原始正文", true)); f.model.pollOnce()
        val pending = f.model.state.value.pending.single()
        assertEquals("原始正文", f.model.retainedText(pending.nonce))
        assertEquals("REJECTED", f.model.state.value.error)
        f.model.retry(pending.nonce); f.model.reconcile()
        assertEquals(1, f.bindings.sent.size); assertEquals(NativeStandardVoiceDelivery.UNKNOWN, f.model.state.value.pending.single().delivery)
        f.bindings.lookup = NativeStandardVoiceSubmissionStatus.NotAccepted; f.model.reconcile()
        f.bindings.failSend = false; f.model.retry(pending.nonce)
        assertEquals(2, f.bindings.sent.size); assertEquals(f.bindings.sent.first(), f.bindings.sent.last())
        assertTrue(f.model.state.value.pending.isEmpty())
    }

    @Test fun receiptReconciliationDoesNotReplayThePrompt() = runBlocking {
        val f = Fixture(this); f.model.start(); f.bindings.failSend = true
        f.gateway.rows = listOf(segment("s1", "u1", "未知请求", true)); f.model.pollOnce()
        val row = f.model.state.value.pending.single()
        f.bindings.lookup = NativeStandardVoiceSubmissionStatus.Accepted(NativeStandardVoiceReceipt("real-session", row.nonce))
        f.model.reconcile()
        assertTrue(f.model.state.value.pending.isEmpty()); assertEquals(1, f.bindings.sent.size)
        assertEquals("real-session", f.model.state.value.journal!!.submissions.single().receipt!!.sessionId)
    }

    @Test fun processRecoveryKeepsFinalTextButNeverPollsSubmitsOrReadsOutAutomatically() = runBlocking {
        val f = Fixture(this); f.model.start(); f.bindings.failSend = true
        f.gateway.rows = listOf(segment("s1", "u1", "恢复后需确认", true)); f.model.pollOnce()
        val next = NativeStandardVoiceCoordinator(f.gateway, f.store, f.bindings, this)
        val polls = f.gateway.pollCalls; next.initialize(); next.reconcile(); next.pollOnce(); next.setUserMuted(false)
        assertEquals(polls, f.gateway.pollCalls); assertEquals(1, f.bindings.sent.size)
        assertEquals("恢复后需确认", next.state.value.pending.single().text)
        assertTrue(next.state.value.recovered); assertFalse(next.state.value.readAloud)
        assertEquals("native_standard_voice_review_required", next.state.value.error)
    }

    @Test fun changedNativeLifetimeCannotReuseTheOldCaptureOrNumericWakeEvent() = runBlocking {
        val f = Fixture(this); f.model.start(); f.bindings.facts = f.bindings.facts.copy(runtimeIdentity = "native-process-2")
        f.model.reconcile(); f.model.stop()
        assertEquals(NativeStandardVoicePhase.FOREIGN, f.model.state.value.phase); assertEquals(0, f.gateway.stopCalls)
    }

    @Test fun stoppingUsesStatusEvenWhenTheStopCallThrowsAndDoesNotClaimUnfinishedStops() = runBlocking {
        val f = Fixture(this); f.model.start(); f.gateway.failStop = true
        f.model.stop()
        assertEquals(NativeStandardVoicePhase.ENDED, f.model.state.value.phase)
        assertEquals("STOP_REJECTED", f.model.state.value.error); assertEquals("status", f.gateway.calls.last())
        val g = Fixture(this); g.model.start(); g.gateway.stopSettles = false; g.model.stop()
        assertEquals(NativeStandardVoicePhase.STOPPING, g.model.state.value.phase)
        assertEquals("native_standard_voice_stop_unconfirmed", g.model.state.value.error)
    }

    @Test fun unavailableStatusStillAllowsAnAtomicallyFencedStopButDoesNotClaimItsResultIsConfirmed() = runBlocking {
        val f = Fixture(this); f.model.start(); f.gateway.failStatus = true; f.model.stop()
        assertTrue(f.model.state.value.journal!!.stopRequested)
        assertEquals(NativeStandardVoicePhase.UNCONFIRMED, f.model.state.value.phase)
        assertEquals(1, f.gateway.stopCalls)
        assertEquals("stop" to FIRST_OPERATION, f.gateway.operationIds.last())
    }

    @Test fun stoppingAConsumedWakeReleasesOnlyTheMatchingLocalUnclaimedEvent() = runBlocking {
        val f = Fixture(this); f.gateway.wake = wake().copy(enabled = true, eventId = 12); f.gateway.failConsume = true
        f.model.startFromWake(12); f.model.reconcile(); f.model.stop()
        assertEquals(1, f.gateway.releaseCalls); assertEquals(NativeStandardVoicePhase.ENDED, f.model.state.value.phase)
        val other = Fixture(this); other.gateway.wake = wake().copy(enabled = true, eventId = 13); other.gateway.failConsume = true
        other.model.startFromWake(13); other.gateway.wake = other.gateway.wake.copy(voiceClaimed = true); other.model.stop()
        assertEquals(0, other.gateway.releaseCalls)
    }

    @Test fun aLateOldWakeReleaseIsFencedByItsOperationAndCannotReleaseTheNextClaim() = runBlocking {
        val f = Fixture(this); f.gateway.wake = wake().copy(enabled = true, eventId = 21); f.gateway.failConsume = true
        f.model.startFromWake(21); f.model.reconcile(); f.gateway.takeoverBeforeOperation = "wake.release"; f.model.stop()
        assertEquals("wake.release" to FIRST_OPERATION, f.gateway.operationIds.last())
        assertEquals(0, f.gateway.releaseCalls); assertTrue(f.gateway.wake.voiceClaimed)
        assertEquals(OTHER_OPERATION, f.gateway.wake.voiceOperationId)
        assertEquals("VOICE_OWNER_MISMATCH", f.model.state.value.error)
    }

    @Test fun unknownWakeConsumeDoesNotUseAnEventIdAloneToAdoptAnotherStartingOwner() = runBlocking {
        val f = Fixture(this); f.gateway.wake = wake().copy(enabled = true, eventId = 22); f.gateway.failConsume = true
        f.model.startFromWake(22); f.gateway.wake = f.gateway.wake.copy(startingVoiceOperationId = OTHER_OPERATION)
        f.model.reconcile(); f.model.resumeWake()
        assertEquals(NativeStandardVoiceWakePhase.UNKNOWN, f.model.state.value.journal!!.wake!!.phase)
        assertTrue(f.gateway.starts.isEmpty()); assertEquals(1, f.gateway.consumeCalls)
    }

    @Test fun waitingForMicrophoneUsesOnlyTheRequiredHostAuthorizationEntry() = runBlocking {
        val f = Fixture(this); f.gateway.awaitingPermission = true; f.model.start()
        assertEquals(listOf("capture-1"), f.bindings.permissions)
        assertEquals(NativeStandardVoicePhase.AWAITING_PERMISSION, f.model.state.value.phase)
        f.bindings.facts = f.bindings.facts.copy(foreground = false); f.model.requestPermission()
        assertEquals(1, f.bindings.permissions.size)
    }

    @Test fun wakeConsumeIsOnceOnlyAndUnknownIsReconciledWithoutStartingOrRecapturingATarget() = runBlocking {
        val f = Fixture(this); f.gateway.wake = wake().copy(enabled = true, eventId = 7); f.gateway.failConsume = true
        f.model.startFromWake(7); f.model.reconcile(); f.model.startFromWake(7)
        assertEquals(1, f.gateway.consumeCalls); assertTrue(f.gateway.starts.isEmpty()); assertEquals(1, f.bindings.captures)
        assertEquals(NativeStandardVoiceWakePhase.CONSUMED, f.model.state.value.journal!!.wake!!.phase)
        assertEquals("wake-draft", f.model.state.value.journal!!.target.draftId)
        f.gateway.failConsume = false; f.model.resumeWake()
        assertEquals(listOf("wake-draft"), f.gateway.starts); assertEquals(1, f.bindings.captures)
        assertEquals(1, f.gateway.consumeCalls)
    }

    @Test fun successfulWakeStartsWithItsOriginalDraftAndEventWithoutAnEagerDshSubmission() = runBlocking {
        val f = Fixture(this); f.gateway.wake = wake().copy(enabled = true, eventId = 9)
        f.model.startFromWake(9)
        assertEquals(1, f.gateway.consumeCalls); assertEquals(listOf(9L), f.gateway.startWakeIds)
        assertEquals(listOf("wake-draft"), f.gateway.starts); assertTrue(f.bindings.sent.isEmpty())
        assertEquals(listOf(true), f.bindings.freshRequests)
    }

    @Test fun anAlreadyHandledWakeEventCannotAllocateAnotherTargetAfterTheVoiceConversationEnds() = runBlocking {
        val f = Fixture(this); f.gateway.wake = wake().copy(enabled = true, eventId = 19)
        f.model.startFromWake(19); f.model.stop(); f.model.startFromWake(19)
        assertEquals(1, f.gateway.consumeCalls); assertEquals(1, f.bindings.captures)
        assertEquals("native_standard_voice_wake_replayed", f.model.state.value.error)
    }

    @Test fun readoutOnlyAdmitsTheCurrentLiveRpcTurnAndFreezesTheAttemptChoice() = runBlocking {
        val f = Fixture(this); f.model.start(); f.acceptFinal("first")
        val rpc = f.bindings.sent.single().second
        f.model.setReadAloud(true)
        f.model.onReplyEvent(bound("old", "wrong")); f.model.onReplyEvent(text("old", "a", "old", "旧历史。", true))
        f.model.onReplyEvent(bound("turn-1", rpc, live = false)); f.model.onReplyEvent(text("turn-1", "a", "replayed", "回放。", true))
        f.model.onReplyEvent(bound("turn-1", rpc)); f.model.onReplyEvent(text("turn-1", "a", "e1", "本次回复。", false))
        f.model.onReplyEvent(text("turn-1", "a", "e1", "本次回复。", false))
        f.model.onReplyEvent(text("turn-1", "a", "e2", "本次回复。", true)); yield(); yield()
        assertEquals(listOf("本次回复。"), f.gateway.spoken)
        assertTrue(f.model.state.value.automaticMuted); assertTrue(f.gateway.actual.muted)
        f.model.setUserMuted(true); f.gateway.actual = f.gateway.actual.copy(speaking = false); f.model.pollOnce()
        assertTrue(f.model.state.value.userMuted); assertFalse(f.model.state.value.automaticMuted); assertTrue(f.gateway.actual.muted)
    }

    @Test fun enablingReadoutDoesNotRetroactivelyReadAnAttemptStartedWithReadoutOff() = runBlocking {
        val f = Fixture(this); f.model.start(); f.acceptFinal("first"); val rpc = f.bindings.sent.single().second
        f.model.onReplyEvent(bound("turn-1", rpc)); f.model.onReplyEvent(text("turn-1", "a", "e1", "尚未完成", false))
        f.model.setReadAloud(true); f.model.onReplyEvent(text("turn-1", "a", "e2", "尚未完成。", true)); yield()
        f.model.onReplyEvent(NativeStandardVoiceReplyEvent.Interrupted("real-session", "turn-1", "a", true))
        f.model.onReplyEvent(text("turn-1", "b", "e3", "重试也使用本轮最初的朗读选择。", true)); yield()
        assertTrue(f.gateway.spoken.isEmpty())
    }

    @Test fun interruptCancelsInFlightAndQueuedOutputAndConfirmsNativeSpeakingStopped() = runBlocking {
        val f = Fixture(this); f.model.start(); f.acceptFinal("first"); f.model.setReadAloud(true)
        f.gateway.speakGate = CompletableDeferred()
        f.model.onReplyEvent(bound("turn-1", f.bindings.sent.single().second))
        f.model.onReplyEvent(text("turn-1", "a", "e1", "第一句。" + "后续".repeat(130) + "。", true)); yield(); yield()
        assertEquals(1, f.gateway.spoken.size)
        f.model.onReplyEvent(NativeStandardVoiceReplyEvent.Interrupted("real-session", "turn-1", "a", true)); yield()
        assertEquals(1, f.gateway.stopSpeakingCalls); assertFalse(f.model.state.value.outputUnconfirmed)
        assertEquals(0, f.model.state.value.outputPending); assertFalse(f.gateway.actual.speaking)
    }

    @Test fun unknownReadoutNeverReplaysAndRequiresAnExplicitOutputStop() = runBlocking {
        val f = Fixture(this); f.model.start(); f.acceptFinal("first"); f.model.setReadAloud(true); f.gateway.failSpeak = true
        f.model.onReplyEvent(bound("turn-1", f.bindings.sent.single().second))
        f.model.onReplyEvent(text("turn-1", "a", "e1", "本次回复。", true)); yield(); yield()
        assertTrue(f.model.state.value.outputUnconfirmed); assertTrue(f.model.state.value.userMuted)
        f.model.pollOnce(); assertEquals(1, f.gateway.spoken.size)
        f.model.interruptOutput(); assertFalse(f.model.state.value.outputUnconfirmed)
        assertTrue(f.model.state.value.userMuted); assertTrue(f.gateway.actual.muted)
    }

    @Test fun failingToReadStatusDuringOutputInterruptionCannotClaimThatOutputStopped() = runBlocking {
        val f = Fixture(this); f.model.start(); f.acceptFinal("first"); f.model.setReadAloud(true)
        f.model.onReplyEvent(bound("turn-1", f.bindings.sent.single().second))
        f.model.onReplyEvent(text("turn-1", "a", "e1", "本次回复。", true)); yield(); yield()
        f.gateway.failStatus = true; f.model.interruptOutput()
        assertTrue(f.model.state.value.outputUnconfirmed); assertEquals("network_error", f.model.state.value.error)
    }

    @Test fun failedFinalPersistenceRetainsTextInMemoryAndCannotDispatch() = runBlocking {
        val f = Fixture(this); f.model.start(); f.store.fail = true
        f.gateway.rows = listOf(segment("s1", "u1", "保留正文", true)); f.model.pollOnce()
        assertEquals("保留正文", f.model.state.value.pending.single().text)
        assertTrue(f.model.state.value.userMuted); assertTrue(f.bindings.sent.isEmpty())
        assertEquals("native_standard_voice_storage", f.model.state.value.error)
    }

    private class Fixture(scope: CoroutineScope) {
        val gateway = Gateway(); val store = Store(); val bindings = Bindings()
        private var next = 0
        val model = NativeStandardVoiceCoordinator(gateway, store, bindings, scope) { java.util.UUID(0L, (++next).toLong()).toString() }
        suspend fun acceptFinal(text: String) { gateway.rows = listOf(segment("s1", "u1", text, true)); model.pollOnce() }
    }
    private class Store : NativeStandardVoiceStore {
        var value: NativeStandardVoiceJournal? = null; var fail = false
        override suspend fun load() = value
        override suspend fun save(journal: NativeStandardVoiceJournal?) { if (fail) throw NativeApiException("native_standard_voice_storage"); value = journal }
    }
    private class Bindings : NativeStandardVoiceBindings {
        var facts = NativeStandardVoiceHostFacts("native-process-1", true, true)
        var captures = 0; var unchanged = true; var failSend = false; var writableAndOwned = true
        val freshRequests = mutableListOf<Boolean>()
        var lookup: NativeStandardVoiceSubmissionStatus = NativeStandardVoiceSubmissionStatus.Unknown
        val sent = mutableListOf<Triple<NativeStandardVoiceTarget, String, String>>(); val permissions = mutableListOf<String>()
        override suspend fun captureTarget(fresh: Boolean): NativeStandardVoiceTarget {
            captures++; freshRequests += fresh
            return if (fresh) NativeStandardVoiceTarget("wake-draft", null, "workspace", "wake-fingerprint")
            else NativeStandardVoiceTarget("blank-draft", null, "workspace", "draft-fingerprint")
        }
        override suspend fun validateTarget(target: NativeStandardVoiceTarget) = NativeStandardVoiceTargetCheck(writableAndOwned, unchanged)
        override suspend fun submitText(target: NativeStandardVoiceTarget, nonce: String, text: String): NativeStandardVoiceReceipt {
            sent += Triple(target, nonce, text); if (failSend) throw NativeApiException("REJECTED")
            return NativeStandardVoiceReceipt("real-session", nonce)
        }
        override suspend fun submissionStatus(target: NativeStandardVoiceTarget, nonce: String) = lookup
        override suspend fun hostFacts() = facts
        override suspend fun openAudioPermission(captureId: String) { permissions += captureId }
    }
    private class Gateway : NativeStandardVoiceGateway {
        var standardEnabled = true
        var actual = idle(); var wake = wake(); val calls = mutableListOf<String>(); val starts = mutableListOf<String>(); val startWakeIds = mutableListOf<Long?>()
        var failStart = false; var failConsume = false; var failStop = false; var stopSettles = true; var failSpeak = false; var awaitingPermission = false
        var failStatus = false; var releaseCalls = 0
        var rows = emptyList<NativeStandardVoiceSegment>(); var consumeCalls = 0; var pollCalls = 0; var stopCalls = 0; var stopSpeakingCalls = 0
        var pollResult: NativeStandardVoiceNativeState? = null; var rotateOnUnmute = false
        var takeoverBeforeOperation: String? = null
        val operationIds = mutableListOf<Pair<String, String>>()
        val spoken = mutableListOf<String>(); var speakGate: CompletableDeferred<Unit>? = null
        var pollGate: CompletableDeferred<Unit>? = null; var startGate: CompletableDeferred<Unit>? = null
        override suspend fun config() = NativeStandardVoiceConfig(standardEnabled, NativeStandardVoiceTone("system", "", 1.0, "", "tts-1"))
        override suspend fun status(): NativeStandardVoiceNativeState { calls += "status"; if (failStatus) throw NativeApiException("network_error"); return actual }
        override suspend fun start(voiceOperationId: String, sessionId: String, wakeEventId: Long?): NativeStandardVoiceNativeState {
            calls += "start"; starts += sessionId; startWakeIds += wakeEventId
            mutation("start", voiceOperationId, requireOwner = false)
            if (actual.active && (actual.voiceOperationId != voiceOperationId || actual.sessionId != sessionId)) throw NativeApiException("VOICE_OWNER_MISMATCH")
            actual = recording().copy(sessionId = sessionId, voiceOperationId = voiceOperationId, state = if (awaitingPermission) "awaiting_user" else "recording",
                needsPermission = if (awaitingPermission) "microphone" else null)
            wake = wake.copy(voiceClaimed = true, voiceOperationId = voiceOperationId)
            startGate?.await()
            if (failStart) throw NativeApiException("network_error")
            return actual
        }
        override suspend fun poll(voiceOperationId: String, cursor: Long, transcriptCursor: Int, captureId: String?, transcriptId: String?): NativeStandardVoicePoll {
            mutation("poll", voiceOperationId)
            calls += "poll"; pollCalls++
            val result = NativeStandardVoicePoll(pollResult ?: actual, cursor + 10, transcriptCursor + rows.size, rows)
            pollGate?.await(); return result
        }
        override suspend fun mute(voiceOperationId: String, muted: Boolean): NativeStandardVoiceNativeState {
            mutation("mute", voiceOperationId)
            calls += "mute:$muted"; actual = actual.copy(muted = muted)
            if (!muted && rotateOnUnmute) actual = actual.copy(captureId = "capture-2", transcriptId = "transcript-2")
            return actual
        }
        override suspend fun speak(voiceOperationId: String, text: String, sessionId: String, replyId: String, utteranceId: String, tone: NativeStandardVoiceTone) {
            mutation("speak", voiceOperationId)
            spoken += text; actual = actual.copy(speaking = true); speakGate?.await(); if (failSpeak) throw NativeApiException("TTS_REJECTED")
        }
        override suspend fun stopSpeaking(voiceOperationId: String) { mutation("stop_speaking", voiceOperationId); stopSpeakingCalls++; actual = actual.copy(speaking = false) }
        override suspend fun stop(voiceOperationId: String) {
            mutation("stop", voiceOperationId)
            calls += "stop"; stopCalls++; if (stopSettles) actual = idle().copy(state = "ended", sessionId = actual.sessionId, voiceOperationId = actual.voiceOperationId)
            wake = wake.copy(voiceClaimed = false, voiceOperationId = null, startingEventId = 0, startingVoiceOperationId = null)
            if (failStop) throw NativeApiException("STOP_REJECTED")
        }
        override suspend fun wakeStatus() = wake
        override suspend fun consumeWake(voiceOperationId: String, eventId: Long): Boolean {
            operationIds += "wake.consume" to voiceOperationId
            consumeCalls++; wake = wake.copy(eventId = 0, startingEventId = eventId, startingVoiceOperationId = voiceOperationId)
            if (failConsume) throw NativeApiException("network_error")
            return true
        }
        override suspend fun claimWake(voiceOperationId: String) { wake = wake.copy(voiceClaimed = true, voiceOperationId = voiceOperationId) }
        override suspend fun releaseWake(voiceOperationId: String) {
            operationIds += "wake.release" to voiceOperationId
            if (takeoverBeforeOperation == "wake.release") { takeoverBeforeOperation = null; wake = wake.copy(voiceClaimed = true, voiceOperationId = OTHER_OPERATION, startingVoiceOperationId = OTHER_OPERATION) }
            if (wake.voiceOperationId != voiceOperationId && wake.startingVoiceOperationId != voiceOperationId) throw NativeApiException("VOICE_OWNER_MISMATCH")
            releaseCalls++; wake = wake.copy(voiceClaimed = false, voiceOperationId = null, eventId = 0, startingEventId = 0, startingVoiceOperationId = null)
        }
        private fun mutation(operation: String, id: String, requireOwner: Boolean = true) {
            operationIds += operation to id
            if (takeoverBeforeOperation == operation) {
                takeoverBeforeOperation = null; actual = recording().copy(voiceOperationId = OTHER_OPERATION, captureId = "other-capture", speaking = true)
            }
            if (requireOwner && actual.voiceOperationId != id) throw NativeApiException("VOICE_OWNER_MISMATCH")
        }
    }
    companion object {
        private const val FIRST_OPERATION = "00000000-0000-0000-0000-000000000001"
        private const val OTHER_OPERATION = "00000000-0000-0000-0000-000000000099"
        private fun idle() = NativeStandardVoiceNativeState("idle", "standard", null, null, null, false, false, null, 0f, false)
        private fun recording() = NativeStandardVoiceNativeState("recording", "standard", "blank-draft", "capture-1", "transcript-1", false, false, null, 0f, false, FIRST_OPERATION)
        private fun wake() = NativeStandardVoiceWakeState(false, "screen_on", true, false, false, 0, 0)
        private fun segment(id: String, utterance: String, text: String, end: Boolean) = NativeStandardVoiceSegment(id, utterance, text, true, end)
        private fun bound(turn: String, rpc: String, live: Boolean = true) = NativeStandardVoiceReplyEvent.Bound("real-session", turn, rpc, live)
        private fun text(turn: String, attempt: String, event: String, text: String, final: Boolean) = NativeStandardVoiceReplyEvent.Text("real-session", turn, attempt, event, text, final, true)
    }
}

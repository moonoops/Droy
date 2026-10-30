package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceBaselineTest {
    @Test fun aSecondSubmissionWaitsForTheReconnectBaselineAndAnOldReceiptCannotReleaseIt() = runBlocking {
        val baseline = NativeStandardVoiceBaseline()
        val first = baseline.ticket(); baseline.opened(first); baseline.await()
        assertTrue(baseline.failed(first, NativeApiException("network_error")))
        var dispatched = false
        val second = launch { baseline.await(); dispatched = true }; yield()
        assertFalse(dispatched)
        baseline.opened(first); yield(); assertFalse(dispatched)
        baseline.opened(baseline.ticket()); second.join(); assertTrue(dispatched)
    }

    @Test fun failureBeforeTheFirstBaselinePropagatesAndNeverAuthorizesAPrompt() = runBlocking {
        val baseline = NativeStandardVoiceBaseline()
        val waiting = async { try { baseline.await(); "unexpected" } catch (failure: NativeApiException) { failure.code } }
        yield()
        assertFalse(baseline.failed(baseline.ticket(), NativeApiException("session/not-found")))
        assertEquals("session/not-found", waiting.await())
    }

    @Test fun cancellingOneWaitDoesNotOpenOrCancelTheConnectionForAnotherWaiter() = runBlocking {
        val baseline = NativeStandardVoiceBaseline()
        val leaving = launch { baseline.await() }; yield(); leaving.cancelAndJoin()
        var dispatched = false
        val remaining = launch { baseline.await(); dispatched = true }; yield(); assertFalse(dispatched)
        baseline.opened(baseline.ticket()); remaining.join(); assertTrue(dispatched)
    }
}

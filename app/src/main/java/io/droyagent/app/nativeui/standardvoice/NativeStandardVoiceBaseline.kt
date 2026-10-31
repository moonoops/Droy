package io.droyagent.app.nativeui.standardvoice

import kotlinx.coroutines.CompletableDeferred

/** Main-thread connection receipt. A previous SSE baseline cannot authorize the next submission. */
internal class NativeStandardVoiceBaseline {
    private var current = CompletableDeferred<Unit>()
    private var connected = false
    fun ticket() = current
    fun opened(ticket: CompletableDeferred<Unit>) {
        if (ticket !== current) return
        connected = true; ticket.complete(Unit)
    }
    /** True permits reconnect after an established connection; opening failure propagates to the submitter. */
    fun failed(ticket: CompletableDeferred<Unit>, failure: Throwable): Boolean {
        if (ticket !== current) return false
        connected = false
        if (!ticket.isCompleted) { ticket.completeExceptionally(failure); return false }
        current = CompletableDeferred(); return true
    }
    fun cancel() { connected = false; current.cancel() }
    suspend fun await() {
        while (true) {
            val ticket = current
            ticket.await()
            if (ticket === current && connected) return
        }
    }
}

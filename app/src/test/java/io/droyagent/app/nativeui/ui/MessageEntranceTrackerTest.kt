package io.droyagent.app.nativeui.ui

import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.companion.data.NativeCompanionMessage
import org.junit.Assert.*
import org.junit.Test

class MessageEntranceTrackerTest {
    @Test fun historyAndPaginationNeverReceiveEntrance() {
        val tracker = MessageEntranceTracker()
        assertTrue(tracker.update(listOf("history", "restored-pending"), setOf("restored-pending"), true).isEmpty())
        assertTrue(tracker.update(listOf("older", "history", "restored-pending"), emptySet(), true).isEmpty())
        assertTrue(tracker.update(listOf("older", "history", "restored-pending", "remote"), emptySet(), true).isEmpty())
    }

    @Test fun newPendingEntersOnceAndReceiptKeepsIdentity() {
        val tracker = MessageEntranceTracker()
        tracker.update(listOf("history"), emptySet(), true)
        assertEquals(setOf("request:send"), tracker.update(listOf("history", "request:send"), setOf("request:send"), true))
        // Content/status/seq changes do not replace the LazyColumn item.
        assertEquals(setOf("request:send"), tracker.update(listOf("history", "request:send"), emptySet(), true))
        tracker.update(listOf("history"), emptySet(), true)
        assertTrue(tracker.update(listOf("history", "request:send"), setOf("request:send"), true).isEmpty())
    }

    @Test fun reconnectLoadingAndBackgroundRestorationEstablishBaselines() {
        val tracker = MessageEntranceTracker()
        tracker.update(emptyList(), emptySet(), true)
        tracker.update(listOf("new"), setOf("new"), true)
        assertTrue(tracker.update(listOf("new", "restored"), setOf("restored"), false).isEmpty())
        assertTrue(tracker.update(listOf("new", "restored"), setOf("restored"), true).isEmpty())
        assertTrue(MessageEntranceTracker().update(listOf("new"), setOf("new"), true).isEmpty())
    }

    @Test fun workbenchReceiptUsesRequestIdentityWithoutChangingDurableAnchor() {
        val pending = NativeMessage("request:send", "user", "Text", kind = "pending")
        val receipt = NativeMessage("server-message", "user", "Text", seq = 42, requestId = "send")
        assertEquals(pending.itemIdentity(), receipt.itemIdentity())
        assertEquals("server-message", receipt.id)
    }

    @Test fun companionReceiptUsesClientIdentityWithoutChangingDurableAnchor() {
        val pending = NativeCompanionMessage("client", "user", "text", "Text", "now", "pending", clientId = "client")
        val receipt = pending.copy(id = "server", status = "sent")
        assertEquals(pending.itemIdentity(), receipt.itemIdentity())
        assertEquals("server", receipt.id)
    }
}

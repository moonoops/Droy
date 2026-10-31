package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeUnreadTurns
import org.junit.Assert.*
import org.junit.Test

class NativeUnreadTurnsTest {
    @Test fun excludingOldCompanionCursorsRetainsTheUsersOwnUnreadResults() {
        val state = NativeUnreadTurns(mapOf("mine" to 1, "friend" to 2))
        state.observe("mine", 2, 20); state.observe("friend", 3, 30)
        state.exclude(setOf("friend"))
        assertEquals(setOf("mine"), state.unread())
        assertEquals(mapOf("mine" to 1), state.snapshot())
        assertNull(state.completionSeq("friend"))
    }

    @Test fun oldHistoryBootstrapsWithoutMarkingEverythingUnread() {
        val state = NativeUnreadTurns()
        state.seedHistory("a", 12)
        assertTrue(state.unread().isEmpty())
        state.observe("a", 13)
        assertEquals(setOf("a"), state.unread())
    }
    @Test fun firstCompletedTurnForANewSessionIsNotMistakenForOldHistory() {
        val state = NativeUnreadTurns()
        state.seedHistory("old", 12)
        state.observe("new", 1, 19)
        assertEquals(setOf("new"), state.unread())
        assertEquals(19L, state.completionSeq("new"))
    }
    @Test fun initialCatalogCannotSwallowALocallyCreatedFirstResult() {
        val state = NativeUnreadTurns()
        state.trackNew("new")
        state.seedHistory("new", 1)
        assertEquals(setOf("new"), state.unread())
        val restored = NativeUnreadTurns(state.snapshot())
        restored.seedHistory("new", 1)
        assertEquals(setOf("new"), restored.unread())
    }
    @Test fun coldHistoryWithoutCachedBoundaryBootstrapsWhenItsExactProjectionArrives() {
        val state = NativeUnreadTurns()
        state.seedHistory("cold", null)
        state.observe("cold", 7, 90)
        assertTrue(state.unread().isEmpty())
        state.observe("cold", 8, 111)
        assertEquals(setOf("cold"), state.unread())
    }
    @Test fun savedReadCursorDetectsColdResultsWithoutNeedingAgentActivation() {
        val state = NativeUnreadTurns(mapOf("cold" to 5))
        state.seedHistory("cold", 7)
        assertEquals(setOf("cold"), state.unread())
        assertNull("Cached sequence spaces cannot authorize reading", state.completionSeq("cold"))
        state.observe("cold", 7, 100)
        assertEquals(100L, state.completionSeq("cold"))
    }
    @Test fun reconnectAndReadingOneConversationPreserveOtherUnread() {
        val state = NativeUnreadTurns(mapOf("a" to 2, "b" to 5))
        state.observe("a", 3); state.observe("b", 7)
        state.observe("a", 2)
        state.markRead("a")
        assertEquals(setOf("b"), state.unread())
        val restored = NativeUnreadTurns(state.snapshot())
        restored.seedHistory("a", 3); restored.seedHistory("b", 7)
        assertEquals(setOf("b"), restored.unread())
    }
    @Test fun runningTurnAndItsStreamingFramesDoNotProduceAdditionalUnreadResults() {
        val state = NativeUnreadTurns()
        state.seedHistory("a", 2)
        // SDK turn 3 is open: the adapter presents only completed turn 2.
        repeat(20) { assertFalse(state.observe("a", 2, 30)) }
        assertTrue(state.unread().isEmpty())
        assertTrue(state.observe("a", 3, 50))
        assertFalse(state.observe("a", 3, 50))
        assertEquals(setOf("a"), state.unread())
    }
    @Test fun reconnectAndOutOfOrderFramesCannotMoveTheReadingGateBeyondTheResult() {
        val state = NativeUnreadTurns(mapOf("a" to 2))
        state.observe("a", 3, 100)
        state.observe("a", 3, 150)
        assertEquals(100L, state.completionSeq("a"))
        state.observe("a", 3, 90)
        assertEquals(90L, state.completionSeq("a"))
        state.observe("a", 4, 200)
        state.observe("a", 3, 90)
        assertEquals(200L, state.completionSeq("a"))
        assertEquals(setOf("a"), state.unread())
    }
    @Test fun aNewColdResultCannotBeClearedUsingThePreviousTurnsRenderedCursor() {
        val state = NativeUnreadTurns(mapOf("a" to 2))
        state.observe("a", 3, 100)
        state.observe("a", 4)
        assertNull(state.completionSeq("a"))
        state.observe("a", 3, 100)
        assertNull(state.completionSeq("a"))
        state.observe("a", 4, 200)
        assertEquals(200L, state.completionSeq("a"))
    }

}

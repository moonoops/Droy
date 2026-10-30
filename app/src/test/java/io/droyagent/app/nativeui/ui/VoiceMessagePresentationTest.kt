package io.droyagent.app.nativeui.ui

import org.junit.Assert.*
import org.junit.Test

class VoiceMessagePresentationTest {
    @Test fun captureDurationGivesIdenticalPendingAndSavedGeometry() {
        assertEquals(164, voiceBubbleWidthDp(3_200))
        assertTrue(voiceBubbleWidthDp(3_200) < voiceBubbleWidthDp(60_000))
        assertEquals(276, voiceBubbleWidthDp(60_000))
        assertEquals(196, voiceBubbleWidthDp(null))
        assertEquals(196, voiceBubbleWidthDp(0))
    }
}

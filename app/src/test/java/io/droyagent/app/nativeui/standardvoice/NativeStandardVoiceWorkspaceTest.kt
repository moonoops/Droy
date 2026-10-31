package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeWorkspace
import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceWorkspaceTest {
    private val spaces = listOf(NativeWorkspace("default", "Default", emptyList(), false),
        NativeWorkspace("work", "Work", emptyList(), false), NativeWorkspace("partner", "Partner", emptyList(), true))

    @Test fun wakingFromAManagedPartnerSpaceUsesTheOrdinaryDefault() {
        assertEquals("default", standardVoiceFreshWorkspace("partner", "default", spaces))
        assertEquals("default", standardVoiceFreshWorkspace("removed-space", "default", spaces))
    }

    @Test fun ordinaryWorkAndExplicitUngroupedStayInTheirOwnSpace() {
        assertEquals("work", standardVoiceFreshWorkspace("work", "default", spaces))
        assertNull(standardVoiceFreshWorkspace(null, "default", spaces))
    }

    @Test fun aMissingOrManagedDefaultFallsBackToUngrouped() {
        assertNull(standardVoiceFreshWorkspace("partner", "missing", spaces))
        assertNull(standardVoiceFreshWorkspace("partner", "partner", spaces))
        assertNull(standardVoiceFreshWorkspace("partner", null, spaces))
    }
}

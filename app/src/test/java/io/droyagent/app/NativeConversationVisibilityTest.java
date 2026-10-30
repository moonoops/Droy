package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeConversationVisibilityTest {
    @Test public void exitingPageCannotHideTheIncomingConversation() {
        NativeConversationVisibility state = new NativeConversationVisibility();
        Object old = new Object(), next = new Object();
        assertEquals("old-session", state.update(old, "old-session", true));
        assertEquals("next-session", state.update(next, "next-session", true));
        assertEquals("next-session", state.update(old, "old-session", false));
        assertNull(state.update(next, "next-session", false));
    }

    @Test public void cancelledNavigationRevealsTheStillVisiblePage() {
        NativeConversationVisibility state = new NativeConversationVisibility();
        Object old = new Object(), next = new Object();
        state.update(old, "old-session", true);
        state.update(next, "next-session", true);
        assertEquals("next-session", state.update(old, "old-session", true));
        assertEquals("old-session", state.update(next, "next-session", false));
        assertNull(state.update(old, null, true));
    }
}

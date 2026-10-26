package io.droyagent.androiduse;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ActionChannelsTest {
    @Test public void ordinaryCallsUseAvailableImplementations() throws Exception {
        assertEquals(List.of("accessibility", "shizuku", "root"), candidates("tap", false, false, "auto", true, true, true));
        assertEquals(List.of("shizuku", "root"), candidates("tap", false, false, "auto", false, true, true));
        assertEquals(List.of("root"), candidates("screenshot", false, false, "auto", false, false, true));
        assertTrue(candidates("tap", false, false, "auto", false, false, false).isEmpty());
    }

    @Test public void oldChannelIsAPreferenceAndCannotBreakOtherDevices() throws Exception {
        assertEquals(List.of("accessibility"), candidates("tap", false, false, "root", true, false, false));
        assertEquals(List.of("root", "accessibility"), candidates("tap", false, false, "root", true, false, true));
        assertEquals(List.of("accessibility"), candidates("tap", true, false, "root", true, false, true));
    }

    @Test public void routingNeverChangesTheMeaningOfAnOperation() throws Exception {
        for (String action : List.of("observe", "input_text", "wait_for", "gesture")) {
            assertEquals(List.of("accessibility"), candidates(action, false, false, "root", true, true, true));
            assertTrue(candidates(action, false, false, "auto", false, true, true).isEmpty());
        }
        assertTrue(candidates("swipe", false, true, "auto", false, true, true).isEmpty());
    }

    @Test public void uncertainActionsAndSecurityFailuresAreNotReplayed() {
        assertTrue(ActionChannels.mayFallback("accessibility_unavailable", false));
        assertTrue(ActionChannels.mayFallback("shizuku_unavailable", false));
        assertFalse(ActionChannels.mayFallback("shizuku_unavailable", true));
        for (String code : List.of("action_rejected", "gesture_cancelled", "stale_snapshot", "secure_screen", "root_denied_or_failed"))
            assertFalse(ActionChannels.mayFallback(code, true));
        assertFalse(ActionChannels.mayFallback("secure_screen", false));
    }

    private static List<String> candidates(String action, boolean node, boolean natural, String preferred,
                                           boolean a, boolean s, boolean r) throws Exception {
        return ActionChannels.candidates(action, node, natural, preferred, a, s, r);
    }
}

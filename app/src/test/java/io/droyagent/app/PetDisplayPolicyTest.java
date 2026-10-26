package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetDisplayPolicyTest {
    @Test public void defaultsPreserveAnExplicitOldOptOut() {
        assertEquals("always", PetDisplayPolicy.initial(false, true));
        assertEquals("off", PetDisplayPolicy.initial(true, true));
        assertEquals("off", PetDisplayPolicy.initial(false, false));
        assertEquals("off", PetDisplayPolicy.initial(true, false));
    }
    @Test public void adaptiveIsEmptyUntilWorkOrARealNoticeAndOffTakesPriority() {
        PetDisplayPolicy policy = new PetDisplayPolicy();
        assertFalse(policy.visible("adaptive", false, false, false, 100));
        assertTrue(policy.visible("adaptive", true, false, false, 100));
        assertTrue(policy.visible("adaptive", false, true, false, 100));
        assertFalse(policy.visible("off", true, true, false, 100));
        assertTrue(policy.visible("off", false, false, true, 100));
    }
    @Test public void refreshAndCaptureRecoveryDoNotExtendACompletedNotice() {
        PetDisplayPolicy policy = new PetDisplayPolicy();
        policy.observe("task:working", true, false, true, false, 1000);
        policy.observe("task:completed", false, false, true, false, 2000);
        assertFalse(policy.visible("adaptive", false, false, false, 2000));
        policy.announce("selected-notice", 2000);
        assertTrue(policy.visible("adaptive", false, false, false, 7999));
        policy.announce("selected-notice", 7500);
        policy.observe("task:completed", false, false, true, false, 7500);
        assertFalse(policy.visible("adaptive", false, false, false, 8000));
    }
    @Test public void ReadingNoticeInConversationDoesNotReshowItOnReturningHome() {
        PetDisplayPolicy policy = new PetDisplayPolicy();
        policy.announce("selected-notice", 2000);
        policy.observe("task:completed", false, false, true, false, 2000);
        policy.observe("task:completed", false, false, true, true, 3000);
        policy.observe("task:completed", false, false, true, false, 4000);
        assertFalse(policy.visible("adaptive", false, false, false, 4000));
        assertTrue(policy.visible("always", false, false, false, 4000));
    }
}

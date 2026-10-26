package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationAccessPolicyTest {
    @Test public void authorizedStartupWaitsThenRequestsOnlyOneRebind() {
        NotificationAccessPolicy policy = new NotificationAccessPolicy();
        var initial = policy.observe(true, false, true, false, true, 100);
        assertEquals("connecting", initial.state()); assertFalse(initial.actionRequired()); assertFalse(initial.requestRebind());
        assertEquals(3000, initial.checkAfterMs());
        assertFalse(policy.observe(true, false, true, false, true, 3099).requestRebind());
        var rebind = policy.observe(true, false, true, false, true, 3100);
        assertTrue(rebind.requestRebind()); assertEquals(12000, rebind.checkAfterMs());
        for (long time : new long[]{3101, 6000, 15099}) {
            var waiting = policy.observe(true, false, true, false, true, time);
            assertFalse(waiting.requestRebind()); assertFalse(waiting.actionRequired());
        }
        var failure = policy.observe(true, false, true, false, true, 15100);
        assertEquals("listener_unavailable", failure.state()); assertTrue(failure.actionRequired());
        assertEquals("notificationAccess", failure.action()); assertFalse(failure.requestRebind()); assertEquals(0, failure.checkAfterMs());
        assertFalse(policy.observe(true, false, true, false, true, 60000).requestRebind());
    }

    @Test public void revokedPermissionNeverRebindsAndActualConnectionImmediatelyClearsFailure() {
        NotificationAccessPolicy policy = new NotificationAccessPolicy();
        policy.observe(true, false, true, false, true, 0);
        assertTrue(policy.observe(true, false, true, false, true, 3000).requestRebind());
        var revoked = policy.observe(true, false, false, false, true, 3100);
        assertEquals("read_permission_required", revoked.state()); assertFalse(revoked.requestRebind()); assertEquals(0, revoked.checkAfterMs());
        var restored = policy.observe(true, false, true, true, true, 3200);
        assertEquals("ready", restored.state()); assertFalse(restored.actionRequired());
        assertEquals("connecting", policy.observe(true, false, true, false, true, 4000).state());
        assertTrue(policy.observe(true, false, true, false, true, 7000).requestRebind());
    }

    @Test public void disabledPausedAndPostingDeniedAreDifferentConditions() {
        NotificationAccessPolicy policy = new NotificationAccessPolicy();
        assertEquals("off", policy.observe(false, false, false, false, false, 0).state());
        var paused = policy.observe(true, true, true, false, true, 0);
        assertEquals("paused", paused.state()); assertFalse(paused.actionRequired()); assertFalse(paused.requestRebind());
        var denied = policy.observe(true, false, true, true, false, 0);
        assertEquals("post_permission_required", denied.state()); assertEquals("notifications", denied.action()); assertTrue(denied.actionRequired());
        policy.observe(true, false, true, false, false, 100);
        assertTrue(policy.observe(true, false, true, false, false, 3100).requestRebind());
        assertEquals("post_permission_required", policy.observe(true, false, true, false, false, 15100).state());
    }
}

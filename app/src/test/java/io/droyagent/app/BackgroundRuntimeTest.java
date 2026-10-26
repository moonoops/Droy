package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackgroundRuntimeTest {
    @Test public void notificationManagementCanRunIndependentlyOfContinuousMode() {
        assertFalse(BackgroundRuntime.shouldKeepRunning(false, false, false));
        assertTrue(BackgroundRuntime.shouldKeepRunning(true, false, false));
        assertTrue(BackgroundRuntime.shouldKeepRunning(false, true, false));
    }

    @Test public void explicitStopOverridesEveryBackgroundReason() {
        assertFalse(BackgroundRuntime.shouldKeepRunning(true, false, true));
        assertFalse(BackgroundRuntime.shouldKeepRunning(false, true, true));
        assertFalse(BackgroundRuntime.shouldKeepRunning(true, true, true));
    }

    @Test public void notificationUsesCurrentTaskStateAndReturnsToStandbyAfterCompletion() {
        assertEquals("Droy · 正在启动", RuntimeService.notificationTitle(false, false, ""));
        assertEquals("Droy · 正在处理任务", RuntimeService.notificationTitle(true, true, "executing"));
        assertEquals("Droy · 需要你的回应", RuntimeService.notificationTitle(true, true, "waiting_input"));
        assertEquals("Droy · 待命", RuntimeService.notificationTitle(true, false, "completed"));
        assertEquals("Droy · 待命", RuntimeService.notificationTitle(true, false, "interrupted"));
    }
}

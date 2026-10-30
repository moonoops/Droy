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
        assertEquals("正在启动", RuntimeService.notificationTitle(false, false, ""));
        assertEquals("正在处理任务", RuntimeService.notificationTitle(true, true, "executing"));
        assertEquals("需要你的确认", RuntimeService.notificationTitle(true, true, "waiting_input"));
        assertEquals("后台运行中", RuntimeService.notificationTitle(true, false, "completed"));
        assertEquals("后台运行中", RuntimeService.notificationTitle(true, false, "interrupted"));
    }
}

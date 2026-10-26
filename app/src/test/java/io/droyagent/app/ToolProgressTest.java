package io.droyagent.app;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class ToolProgressTest {
    @org.junit.Before public void isolateTasks() { ToolProgress.interruptAll(); }
    @org.junit.After public void releaseTasks() { ToolProgress.interruptAll(); }
    @Test public void repeatedPhaseKeepsItsStartAndOldCompletionDoesNotClearCurrentWork() throws Exception {
        ToolProgress.update("current-chat", "thinking", true);
        long startedAt = ToolProgress.current(null).getLong("startedAt");
        ToolProgress.update("current-chat", "thinking", true);
        assertEquals(startedAt, ToolProgress.current(null).getLong("startedAt"));
        ToolProgress.update("older-chat", "completed", false);
        JSONObject current = ToolProgress.current(null);
        assertEquals("current-chat", current.getString("taskId"));
        assertTrue(current.getBoolean("active"));
        ToolProgress.update("current-chat", "completed", false);
        assertFalse(ToolProgress.current(null).getBoolean("active"));
    }

    @Test public void malformedPhaseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ToolProgress.update("task", "unknown", true));
    }

    @Test public void chatAndBackgroundToolsCannotStartTheScreenIndicator() {
        ToolProgress.update("chat", "thinking", true);
        ToolProgress.update("chat", "executing", true);
        assertFalse(ToolProgress.screenOperationActive());
        ToolProgress.phoneOperation("unknown-task");
        assertFalse(ToolProgress.screenOperationActive());
    }

    @Test public void phoneSessionContinuesBetweenToolsButWaitsAndTerminalStatesHideIt() throws Exception {
        ToolProgress.update("phone", "executing", true); ToolProgress.phoneOperation("phone");
        assertTrue(ToolProgress.screenOperationActive());
        enginePhase("phone", "thinking");
        assertTrue(ToolProgress.screenOperationActive());
        enginePhase("phone", "waiting");
        assertFalse(ToolProgress.screenOperationActive());
        ToolProgress.update("phone", "verifying", true);
        assertFalse("Native receipts must not end an announced wait", ToolProgress.screenOperationActive());
        enginePhase("phone", "executing");
        assertTrue(ToolProgress.screenOperationActive());
        enginePhase("phone", "blocked");
        assertFalse(ToolProgress.screenOperationActive());
        ToolProgress.update("phone", "completed", false);
        assertFalse(ToolProgress.screenOperationActive());
        enginePhase("phone", "thinking");
        assertFalse("A new execution round must not inherit the previous phone session", ToolProgress.screenOperationActive());
    }

    @Test public void pendingSelectedTaskCannotHideAnotherPhoneTask() throws Exception {
        ToolProgress.update("phone", "executing", true); ToolProgress.phoneOperation("phone");
        ToolProgress.updateFromEngine(new JSONObject().put("taskId", "question").put("phase", "waiting_input").put("active", true)
            .put("pending", new JSONObject().put("requestId", "q1").put("title", "测试选择").put("message", "请选择")
                .put("inline", true).put("options", new org.json.JSONArray())));
        assertEquals("question", ToolProgress.current(null).getString("taskId"));
        assertTrue(ToolProgress.screenOperationActive());
        ToolProgress.update("question", "completed", false);
        assertTrue(ToolProgress.screenOperationActive());
        ToolProgress.endScreenOperation("phone");
        assertFalse(ToolProgress.screenOperationActive());
    }

    @Test public void pauseAndShutdownClearAllPhoneSessionsWithoutRevivingThem() throws Exception {
        for (String task : new String[]{"first", "second"}) {
            ToolProgress.update(task, "executing", true); ToolProgress.phoneOperation(task);
        }
        ToolProgress.endScreenOperation("first");
        assertTrue(ToolProgress.screenOperationActive());
        ToolProgress.clearScreenOperations();
        enginePhase("second", "thinking");
        assertFalse(ToolProgress.screenOperationActive());
        ToolProgress.phoneOperation("second");
        assertTrue(ToolProgress.screenOperationActive());
        ToolProgress.interruptAll();
        assertFalse(ToolProgress.screenOperationActive());
    }

    @Test public void onlyForegroundToolsStartAPhoneSession() {
        assertTrue(ProductBridge.screenOperation("tap", true, false, false));
        assertTrue(ProductBridge.screenOperation("open_app", false, false, false));
        assertTrue(ProductBridge.screenOperation("browser_open", false, false, false));
        assertTrue(ProductBridge.screenOperation("browser_click", false, false, true));
        assertFalse(ProductBridge.screenOperation("browser_observe", false, false, false));
        assertFalse(ProductBridge.screenOperation("browser_status", false, false, true));
        assertFalse(ProductBridge.screenOperation("compute_run", false, false, false));
        assertFalse(ProductBridge.screenOperation("verification", true, false, true));
        assertTrue(ProductBridge.screenOperation("verification", true, true, false));
    }

    private static void enginePhase(String task, String phase) throws Exception {
        ToolProgress.updateFromEngine(new JSONObject().put("taskId", task).put("phase", phase).put("active", true));
    }
}

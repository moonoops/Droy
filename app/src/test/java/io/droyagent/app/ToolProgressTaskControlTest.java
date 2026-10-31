package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ToolProgressTaskControlTest {
    @Before public void prepare() { ToolProgress.interruptAll(); }
    @After public void restore() { ToolProgress.interruptAll(); }

    @Test public void pausedTaskRetainsItsResumeIdentityAndCannotBeChangedByNativeReceipts() throws Exception {
        ToolProgress.updateFromEngine(progress("scope-a", "executing", true, 0));
        ToolProgress.phoneOperation("scope-a");
        assertTrue(ToolProgress.screenOperationActive());
        ToolProgress.updateFromEngine(progress("scope-a", "paused", true, 2));
        JSONObject paused = ToolProgress.current(null);
        assertTrue(paused.getBoolean("active"));
        assertEquals(2, paused.getLong("controlRevision"));
        assertFalse(ToolProgress.screenOperationActive());
        for (String phase : new String[]{"executing", "verifying", "interrupted"}) {
            ToolProgress.update("scope-a", phase, !phase.equals("interrupted"));
            assertEquals(paused.toString(), ToolProgress.current(null).toString());
        }
        ToolProgress.updateFromEngine(progress("scope-a", "interrupted", false, 1));
        JSONObject legacy = progress("scope-a", "thinking", true, 0);
        legacy.remove("controlRevision");
        ToolProgress.updateFromEngine(legacy);
        assertEquals(paused.toString(), ToolProgress.current(null).toString());
        ToolProgress.updateFromEngine(progress("scope-a", "resuming", true, 3));
        assertEquals("resuming", ToolProgress.current(null).getString("phase"));
        ToolProgress.updateFromEngine(progress("scope-a", "thinking", true, 4));
        assertEquals("thinking", ToolProgress.current(null).getString("phase"));
    }

    @Test public void delayedPauseCannotReviveAStoppedTaskOrReplaceOtherTasksQuestion() throws Exception {
        ToolProgress.updateFromEngine(progress("scope-a", "paused", true, 2));
        ToolProgress.updateFromEngine(progress("scope-b", "waiting_input", true, 0).put("pending", question("question-b", "question")));
        ToolProgress.updateFromEngine(progress("scope-a", "stopped", false, 3));
        JSONObject other = ToolProgress.current(null);
        assertEquals("scope-b", other.getString("taskId"));
        ToolProgress.updateFromEngine(progress("scope-a", "pausing", true, 1));
        ToolProgress.updateFromEngine(progress("scope-a", "paused", true, 2));
        assertEquals(other.toString(), ToolProgress.current(null).toString());
        ToolProgress.updateFromEngine(progress("scope-b", "interrupted", false, 0));
        ToolProgress.updateFromEngine(progress("scope-a", "thinking", true, 4));
        assertEquals("scope-a", ToolProgress.current(null).getString("taskId"));
        assertEquals(4, ToolProgress.current(null).getLong("controlRevision"));
    }

    @Test public void unsettledPauseKeepsItsErrorVisibleAndCannotLookLikePhoneExecution() throws Exception {
        for (String phase : new String[]{"pausing", "paused", "resuming", "stopping"}) {
            ToolProgress.updateFromEngine(progress("scope-a", phase, true, 2)
                .put("summaryKind", "error").put("activityKind", "controlling")
                .put("summary", "Execution has not confirmed its stop."));
            ToolProgress.phoneOperation("scope-a");
            JSONObject state = ToolProgress.current(null);
            assertEquals(phase, state.getString("phase"));
            assertEquals("error", state.getString("summaryKind"));
            assertEquals("", state.getString("activityKind"));
            assertFalse(ToolProgress.screenOperationActive());
        }
    }

    @Test public void controlRevisionsMustBeSafeIntegersAndInvalidReceiptsDoNotReplaceTheView() throws Exception {
        ToolProgress.updateFromEngine(progress("scope-a", "paused", true, 2));
        String original = ToolProgress.current(null).toString();
        for (Object value : new Object[]{-1, 1.5, "3", true, 9_007_199_254_740_992L, JSONObject.NULL}) {
            JSONObject bad = progress("scope-a", "resuming", true, 3).put("controlRevision", value);
            try { ToolProgress.updateFromEngine(bad); fail("Invalid revision accepted: " + value); }
            catch (IllegalArgumentException expected) { assertEquals(original, ToolProgress.current(null).toString()); }
        }
        try { ToolProgress.updateFromEngine(progress("scope-a", "paused", false, 3)); fail("Inactive resumable task accepted"); }
        catch (IllegalArgumentException expected) { assertEquals(original, ToolProgress.current(null).toString()); }
    }

    @Test public void approvalAndQuestionKindsRemainDistinctAndPauseWithdrawsOldQuestions() throws Exception {
        for (String kind : new String[]{"question", "approval"}) {
            ToolProgress.updateFromEngine(progress("scope-a", "waiting_input", true, 0).put("pending", question("ask", kind)));
            assertEquals(kind, ToolProgress.current(null).getJSONObject("pending").getString("kind"));
        }
        ToolProgress.updateFromEngine(progress("scope-a", "pausing", true, 1).put("pending", question("stale", "question")));
        assertTrue(ToolProgress.current(null).isNull("pending"));
        assertEquals("pausing", ToolProgress.current(null).getString("phase"));
        assertFalse(ToolProgress.hasPending("scope-a", "stale"));
    }

    @Test public void familyControlsKeepTheQuestionOwnerAndUseTheParentsRevisionNamespace() throws Exception {
        ToolProgress.updateFromEngine(progress("child-a", "thinking", true, 8));
        ToolProgress.updateFromEngine(progress("child-a", "paused", true, 1).put("controlTaskId", "parent-a"));
        JSONObject paused = ToolProgress.current(null);
        assertEquals("child-a", paused.getString("taskId"));
        assertEquals("parent-a", paused.getString("controlTaskId"));
        assertEquals(1, paused.getLong("controlRevision"));
        ToolProgress.updateFromEngine(progress("child-b", "paused", true, 2).put("controlTaskId", "parent-a"));
        String current = ToolProgress.current(null).toString();
        ToolProgress.updateFromEngine(progress("child-a", "thinking", true, 1).put("controlTaskId", "parent-a"));
        assertEquals(current, ToolProgress.current(null).toString());
        ToolProgress.updateFromEngine(progress("child-a", "waiting_input", true, 3).put("controlTaskId", "parent-a")
            .put("pending", question("child-question", "question")));
        JSONObject question = ToolProgress.current(null);
        assertEquals("child-a", question.getString("taskId"));
        assertEquals("parent-a", question.getString("controlTaskId"));
        assertTrue(ToolProgress.hasPending("child-a", "child-question"));
        assertFalse(ToolProgress.hasPending("parent-a", "child-question"));
        ToolProgress.phoneOperation("child-a");
        assertEquals("parent-a", ToolProgress.current(null).getString("controlTaskId"));
    }

    private static JSONObject progress(String task, String phase, boolean active, long revision) throws Exception {
        return new JSONObject().put("taskId", task).put("phase", phase).put("active", active)
            .put("controlRevision", revision).put("summary", phase).put("pending", JSONObject.NULL);
    }

    private static JSONObject question(String id, String kind) throws Exception {
        return new JSONObject().put("requestId", id).put("kind", kind).put("title", "Fictional question")
            .put("message", "Choose a fictional option.").put("inline", true).put("options", new JSONArray());
    }
}

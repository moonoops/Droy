package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ToolProgressPetTest {
    @Test public void timedWaitIsNotAQuestionAndSurvivesNativeStagesUntilEngineResumes() throws Exception {
        String summary = "等待10:00开抢，到点后领取优惠券";
        ToolProgress.updateFromEngine(event("timed-wait", "waiting", true, null).put("summary", summary));
        long began = ToolProgress.current(null).getLong("startedAt");
        for (String phase : new String[]{"executing", "verifying"}) {
            ToolProgress.update("timed-wait", phase, true);
            JSONObject current = ToolProgress.current(null);
            assertEquals("waiting", current.getString("phase"));
            assertEquals(summary, current.getString("summary"));
            assertEquals(began, current.getLong("startedAt"));
            assertEquals("", current.getString("summaryKind"));
            assertTrue(current.isNull("pending"));
        }
        ToolProgress.updateFromEngine(event("timed-wait", "executing", true, null).put("summary", "已到时间，正在领取优惠券"));
        assertEquals("executing", ToolProgress.current(null).getString("phase"));
        assertNotEquals(summary, ToolProgress.current(null).getString("summary"));
        ToolProgress.updateFromEngine(event("timed-wait", "waiting", true, null).put("summary", summary));
        ToolProgress.interruptAll();
        assertEquals("interrupted", ToolProgress.current(null).getString("phase"));
        assertFalse(ToolProgress.current(null).getBoolean("active"));
        assertNotEquals(summary, ToolProgress.current(null).getString("summary"));
    }

    @Test public void onlyLiveCompletionTransitionRequestsReturn() throws Exception {
        ToolProgress.update("return-fixture", "interrupted", false);
        assertFalse(ToolProgress.updateFromEngine(event("return-fixture", "completed", false, null)));
        assertFalse(ToolProgress.updateFromEngine(event("return-fixture", "thinking", true, null)));
        assertFalse(ToolProgress.updateFromEngine(event("older-fixture", "completed", false, null)));
        assertTrue(ToolProgress.updateFromEngine(event("return-fixture", "completed", false, null)));
        assertFalse(ToolProgress.updateFromEngine(event("return-fixture", "completed", false, null)));
        assertFalse(ToolProgress.updateFromEngine(event("return-fixture", "thinking", true, null)));
        assertFalse(ToolProgress.updateFromEngine(event("return-fixture", "interrupted", false, null)));
        assertFalse(ToolProgress.updateFromEngine(event("return-fixture", "completed", false, null)));
    }
    @Test public void questionNavigationMatchesLiveRequestAndCannotFollowReplacedOrCancelledQuestions() throws Exception {
        ToolProgress.updateFromEngine(event("question-task", "waiting_input", true, question("first")));
        assertTrue(ToolProgress.hasPending("question-task", "first"));
        assertFalse(ToolProgress.hasPending("other-task", "first"));
        ToolProgress.updateFromEngine(event("question-task", "waiting_input", true, question("second")));
        assertFalse(ToolProgress.hasPending("question-task", "first"));
        assertTrue(ToolProgress.hasPending("question-task", "second"));
        ToolProgress.updateFromEngine(event("question-task", "thinking", true, null));
        assertFalse(ToolProgress.hasPending("question-task", "second"));
        ToolProgress.updateFromEngine(event("question-task", "waiting_input", true, question("third")));
        ToolProgress.interruptAll();
        assertFalse(ToolProgress.hasPending("question-task", "third"));
    }
    @Test public void slowRequestIsDistinctFromChatterAndCannotReplaceAQuestion() throws Exception {
        ToolProgress.updateFromEngine(event("slow-task", "thinking", true, null)
            .put("summary", "模型请求较慢，已等待超过10秒，可停止任务").put("summaryKind", "slow"));
        assertEquals("slow", ToolProgress.current(null).getString("summaryKind"));
        ToolProgress.updateFromEngine(event("slow-task", "waiting_input", true, question("slow-question")).put("summaryKind", "slow"));
        assertEquals("", ToolProgress.current(null).getString("summaryKind"));
        ToolProgress.updateFromEngine(event("slow-task", "executing", true, null));
        assertEquals("", ToolProgress.current(null).getString("summaryKind"));
    }
    @Before public void resetCurrentTask() {
        ToolProgress.interruptAll();
    }

    @org.junit.After public void releaseTasks() { ToolProgress.interruptAll(); }

    @Test public void completingOneOfTwoTasksKeepsTheOtherActive() throws Exception {
        ToolProgress.updateFromEngine(event("task-a", "executing", true, null).put("summary", "任务甲执行中"));
        ToolProgress.updateFromEngine(event("task-b", "thinking", true, null).put("summary", "任务乙执行中"));
        assertTrue(ToolProgress.updateFromEngine(event("task-a", "completed", false, null)));
        JSONObject current = ToolProgress.current(null);
        assertTrue(current.getBoolean("active"));
        assertEquals("task-b", current.getString("taskId"));
        assertEquals("任务乙执行中", current.getString("summary"));
        assertFalse(ToolProgress.updateFromEngine(event("task-a", "completed", false, null)));
        assertEquals(current.toString(), ToolProgress.current(null).toString());
        assertTrue(ToolProgress.updateFromEngine(event("task-b", "completed", false, null)));
        assertFalse(ToolProgress.current(null).getBoolean("active"));
    }

    @Test public void backgroundTaskCompletionDoesNotReplaceCurrentWork() throws Exception {
        ToolProgress.updateFromEngine(event("task-visible", "executing", true, null));
        ToolProgress.updateFromEngine(event("task-background", "executing", true, null));
        String before = ToolProgress.current(null).toString();
        assertTrue(ToolProgress.updateFromEngine(event("task-background", "completed", false, null)));
        assertEquals(before, ToolProgress.current(null).toString());
        ToolProgress.update("task-visible", "interrupted", false);
        assertFalse(ToolProgress.current(null).getBoolean("active"));
    }

    @Test public void pendingQuestionsTakePriorityAndReturnToRemainingWorkWhenAnswered() throws Exception {
        ToolProgress.updateFromEngine(event("task-working", "executing", true, null));
        ToolProgress.updateFromEngine(event("task-question", "waiting_input", true, question("question-a")));
        ToolProgress.updateFromEngine(event("task-working", "verifying", true, null));
        assertEquals("task-question", ToolProgress.current(null).getString("taskId"));
        assertEquals("question-a", ToolProgress.current(null).getJSONObject("pending").getString("requestId"));
        ToolProgress.updateFromEngine(event("task-question", "thinking", true, null));
        assertTrue(ToolProgress.current(null).isNull("pending"));
        ToolProgress.updateFromEngine(event("task-question", "interrupted", false, null));
        assertEquals("task-working", ToolProgress.current(null).getString("taskId"));
        assertEquals("verifying", ToolProgress.current(null).getString("phase"));
        assertTrue(ToolProgress.current(null).getBoolean("active"));
    }

    @Test public void engineRecoveryClearsAllTasksAndPendingQuestions() throws Exception {
        ToolProgress.updateFromEngine(event("task-working", "executing", true, null));
        ToolProgress.updateFromEngine(event("task-question", "waiting_input", true, question("question-recovery")));
        ToolProgress.interruptAll();
        JSONObject current = ToolProgress.current(null);
        assertFalse(current.getBoolean("active"));
        assertTrue(current.isNull("pending"));
        assertEquals("interrupted", current.getString("phase"));
        assertFalse(ToolProgress.updateFromEngine(event("task-working", "completed", false, null)));
        assertFalse(ToolProgress.updateFromEngine(event("task-question", "completed", false, null)));
        ToolProgress.updateFromEngine(event("new-task", "thinking", true, null));
        ToolProgress.updateFromEngine(event("new-task", "completed", false, null));
        assertFalse(ToolProgress.current(null).getBoolean("active"));
    }

    @Test public void workingKindIsOptionalAndCannotDecorateQuestionsOrTerminalStates() throws Exception {
        ToolProgress.updateFromEngine(event("pet-voice", "thinking", true, null).put("summaryKind", "working"));
        assertEquals("working", ToolProgress.current(null).getString("summaryKind"));
        ToolProgress.update("pet-voice", "executing", true);
        assertEquals("working", ToolProgress.current(null).getString("summaryKind"));
        ToolProgress.updateFromEngine(event("pet-voice", "waiting_input", true, question("voice-question")).put("summaryKind", "working"));
        assertEquals("", ToolProgress.current(null).getString("summaryKind"));
        ToolProgress.updateFromEngine(event("pet-voice", "thinking", true, null).put("summaryKind", "working"));
        ToolProgress.update("pet-voice", "interrupted", false);
        assertEquals("", ToolProgress.current(null).getString("summaryKind"));
        ToolProgress.updateFromEngine(event("pet-voice", "thinking", true, null));
        assertEquals("", ToolProgress.current(null).getString("summaryKind"));
        rejectsWithoutReplacingCurrent(event("pet-voice", "thinking", true, null).put("summaryKind", "other"));
        rejectsWithoutReplacingCurrent(event("pet-voice", "thinking", true, null).put("summaryKind", 1));
    }

    private static JSONObject question(String id) throws Exception {
        return new JSONObject().put("requestId", id).put("title", "需要你的选择")
            .put("message", "使用哪一个应用？").put("inline", true)
            .put("options", new JSONArray().put(new JSONObject().put("id", "0").put("label", "应用甲")));
    }

    private static JSONObject event(String task, String phase, boolean active, JSONObject pending) throws Exception {
        return new JSONObject().put("taskId", task).put("phase", phase).put("active", active)
            .put("summary", pending == null ? "正在查找目标应用" : pending.getString("message"))
            .put("pending", pending == null ? JSONObject.NULL : pending);
    }

    @Test public void pendingCanBeReplacedAndExplicitNullRemovesItsOptions() throws Exception {
        ToolProgress.updateFromEngine(event("pet-questions", "waiting_input", true, question("question-1")));
        ToolProgress.updateFromEngine(event("pet-questions", "waiting_input", true, question("question-2")));
        assertEquals("question-2", ToolProgress.current(null).getJSONObject("pending").getString("requestId"));
        ToolProgress.updateFromEngine(event("pet-questions", "thinking", true, null));
        JSONObject current = ToolProgress.current(null);
        assertTrue(current.isNull("pending"));
        assertEquals("thinking", current.getString("phase"));
        assertTrue(current.getBoolean("active"));
    }

    @Test public void olderTerminalEventCannotClearNewTasksQuestionOrSummary() throws Exception {
        ToolProgress.updateFromEngine(event("pet-current", "waiting_input", true, question("question-current")));
        String before = ToolProgress.current(null).toString();
        ToolProgress.updateFromEngine(event("pet-older", "completed", false, null));
        assertEquals(before, ToolProgress.current(null).toString());
        ToolProgress.updateFromEngine(event("pet-current", "interrupted", false, null).put("summary", "本轮执行已停止"));
        JSONObject current = ToolProgress.current(null);
        assertFalse(current.getBoolean("active"));
        assertTrue(current.isNull("pending"));
        assertEquals("本轮执行已停止", current.getString("summary"));
    }

    @Test public void nativeStagesKeepPendingQuestionAndItsDisplayedStartTime() throws Exception {
        ToolProgress.updateFromEngine(event("pet-native", "waiting_input", true, question("question-native")));
        long began = ToolProgress.current(null).getLong("startedAt");
        Thread.sleep(5);
        for (String phase : new String[]{"executing", "verifying", "interrupted"}) {
            ToolProgress.update("pet-native", phase, true);
            JSONObject current = ToolProgress.current(null);
            assertEquals("waiting_input", current.getString("phase"));
            assertEquals("question-native", current.getJSONObject("pending").getString("requestId"));
            assertEquals("使用哪一个应用？", current.getString("summary"));
            assertEquals(began, current.getLong("startedAt"));
        }
    }

    @Test public void nativeRecoveryDoesNotLeaveRunningCopyOnAnInterruptedTask() throws Exception {
        ToolProgress.updateFromEngine(event("pet-recovery", "executing", true, null));
        ToolProgress.update("pet-recovery", "interrupted", false);
        JSONObject current = ToolProgress.current(null);
        assertFalse(current.getBoolean("active"));
        assertEquals("interrupted", current.getString("phase"));
        assertNotEquals("正在查找目标应用", current.getString("summary"));
        assertTrue(current.isNull("pending"));
    }

    @Test public void legacyEnginePayloadAndNativeCallerRemainSupported() throws Exception {
        ToolProgress.updateFromEngine(new JSONObject().put("taskId", "pet-legacy")
            .put("phase", "executing").put("active", true));
        JSONObject current = ToolProgress.current(null);
        assertEquals("executing", current.getString("phase"));
        assertEquals("", current.getString("summary"));
        assertTrue(current.isNull("pending"));
        ToolProgress.update("pet-legacy", "verifying", true);
        assertEquals("verifying", ToolProgress.current(null).getString("phase"));
    }

    @Test public void normalizedPendingAndCurrentSnapshotsDoNotRetainExternalMutationOrExtraData() throws Exception {
        JSONObject source = question("question-copy").put("arguments", "private source data");
        ToolProgress.updateFromEngine(event("pet-copy", "waiting_input", true, source)
            .put("rawReasoning", "private reasoning"));
        source.put("message", "changed source");
        source.getJSONArray("options").getJSONObject(0).put("label", "changed option");
        JSONObject snapshot = ToolProgress.current(null);
        assertFalse(snapshot.toString().contains("private"));
        assertEquals("使用哪一个应用？", snapshot.getJSONObject("pending").getString("message"));
        assertEquals("应用甲", snapshot.getJSONObject("pending").getJSONArray("options").getJSONObject(0).getString("label"));
        snapshot.getJSONObject("pending").put("message", "changed snapshot");
        assertEquals("使用哪一个应用？", ToolProgress.current(null).getJSONObject("pending").getString("message"));
    }

    private static void rejectsWithoutReplacingCurrent(JSONObject invalid) throws Exception {
        String before = ToolProgress.current(null).toString();
        assertThrows(IllegalArgumentException.class, () -> ToolProgress.updateFromEngine(invalid));
        assertEquals(before, ToolProgress.current(null).toString());
    }

    @Test public void invalidBoundaryValuesDoNotReplaceTheCurrentQuestion() throws Exception {
        ToolProgress.updateFromEngine(event("pet-boundary", "waiting_input", true, question("question-safe")));
        rejectsWithoutReplacingCurrent(event("pet-boundary", "thinking", true, null).put("active", "true"));
        rejectsWithoutReplacingCurrent(event("pet-boundary", "thinking", true, null).put("summary", "长".repeat(121)));
        rejectsWithoutReplacingCurrent(event("pet-boundary", "unknown", true, null));
        rejectsWithoutReplacingCurrent(event("", "thinking", true, null));
        rejectsWithoutReplacingCurrent(event("pet-old-invalid", "unknown", false, null));
        rejectsWithoutReplacingCurrent(event("pet-boundary", "waiting_input", true, question("bad-inline").put("inline", "true")));
        JSONObject longLabel = question("long-label");
        longLabel.getJSONArray("options").getJSONObject(0).put("label", "长".repeat(25));
        rejectsWithoutReplacingCurrent(event("pet-boundary", "waiting_input", true, longLabel));
        JSONObject manyOptions = question("too-many");
        for (int i = 1; i < 4; i++) manyOptions.getJSONArray("options").put(new JSONObject().put("id", "" + i).put("label", "选项" + i));
        rejectsWithoutReplacingCurrent(event("pet-boundary", "waiting_input", true, manyOptions));
    }

    @Test public void structuredQuestionsPreserveFullTextAndDoNotAliasTheIncomingForm() throws Exception {
        JSONObject form = question("structured").put("questions", new JSONArray().put(new JSONObject()
            .put("id", "apps").put("question", "完整问题".repeat(40)).put("detail", "分行说明\n保留全文")
            .put("multiSelect", true).put("allowCustom", true).put("options", new JSONArray()
                .put(new JSONObject().put("label", "完整选项".repeat(20)).put("description", "选项详细说明")))));
        ToolProgress.updateFromEngine(event("structured-task", "waiting_input", true, form).put("summary", "等待你的回答"));
        JSONArray stored = ToolProgress.current(null).getJSONObject("pending").getJSONArray("questions");
        assertTrue(stored.getJSONObject(0).getBoolean("multiSelect"));
        assertEquals("分行说明\n保留全文", stored.getJSONObject(0).getString("detail"));
        assertEquals("完整选项".repeat(20), stored.getJSONObject(0).getJSONArray("options").getJSONObject(0).getString("label"));
        form.getJSONArray("questions").getJSONObject(0).put("question", "mutated");
        assertEquals("完整问题".repeat(40), ToolProgress.current(null).getJSONObject("pending").getJSONArray("questions").getJSONObject(0).getString("question"));
        JSONObject invalid = question("invalid").put("questions", new JSONArray(stored.toString()).put(stored.getJSONObject(0)));
        rejectsWithoutReplacingCurrent(event("structured-task", "waiting_input", true, invalid).put("summary", "等待你的回答"));
    }

    @Test public void summaryLimitCountsUnicodeCodePointsLikeTheEngine() throws Exception {
        String exactLimit = "🐈".repeat(120);
        ToolProgress.updateFromEngine(event("pet-unicode", "thinking", true, null).put("summary", exactLimit));
        assertEquals(exactLimit, ToolProgress.current(null).getString("summary"));
        rejectsWithoutReplacingCurrent(event("pet-unicode", "thinking", true, null).put("summary", exactLimit + "🐈"));
    }
}

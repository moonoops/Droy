package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActionSequenceTest {
    private static JSONObject node(String id, String identity, String text) throws Exception {
        return new JSONObject().put("nodeId", id).put("sequenceIdentity", identity).put("text", text)
                .put("description", "").put("resourceId", "fixture:" + identity).put("className", "Fixture")
                .put("bounds", new JSONObject().put("left", 1).put("top", 2).put("right", 100).put("bottom", 102))
                .put("clickable", true).put("checked", false).put("selected", false).put("checkable", false);
    }
    private static JSONObject page(String snapshot, JSONObject... nodes) throws Exception {
        return new JSONObject().put("snapshotId", snapshot).put("packageName", "com.example.fixture")
                .put("nodes", new JSONArray(nodes)).put("truncated", false);
    }
    private static JSONObject target(String node) throws Exception { return new JSONObject().put("nodeId", node); }
    private static JSONObject tap(JSONObject target) throws Exception { return new JSONObject().put("action", "tap").put("target", target); }
    private static JSONObject sequence(JSONObject... steps) throws Exception {
        return new JSONObject().put("snapshotId", "initial").put("packageName", "com.example.fixture").put("steps", new JSONArray(steps));
    }
    private interface Execution { JSONObject run(String action, JSONObject args, int step, Fake host) throws Exception; }
    private static final class Fake implements ActionSequence.Host {
        JSONObject current;
        final List<JSONObject> calls = new ArrayList<>(), saved = new ArrayList<>();
        Execution execution;
        int checks, stopAt = Integer.MAX_VALUE, failSaveAt = -1;
        Fake(JSONObject current) { this.current = current; }
        @Override public void check() throws Exception { if (++checks >= stopAt) throw new InterruptedException("stopped"); }
        @Override public long now() { return System.nanoTime() / 1_000_000; }
        @Override public JSONObject basis(String id) throws Exception {
            if (!id.equals(current.getString("snapshotId"))) throw new ActionException("stale_snapshot", "snapshot mismatch");
            return new JSONObject(current.toString());
        }
        @Override public JSONObject observe() throws Exception { return new JSONObject(current.toString()); }
        @Override public JSONObject execute(String action, JSONObject args, int index) throws Exception {
            calls.add(new JSONObject().put("action", action).put("args", new JSONObject(args.toString())));
            return execution == null ? receipt() : execution.run(action, args, index, this);
        }
        JSONObject receipt() throws Exception {
            return new JSONObject().put("status", "action_completed").put("dispatched", true)
                    .put("afterObservation", current).put("inputTextVerified", true);
        }
        @Override public JSONObject failure(Exception error) throws Exception {
            JSONObject value = error instanceof ActionException e && e.getDetails() != null
                    ? new JSONObject(e.getDetails().toString()) : new JSONObject();
            return value.put("code", error instanceof ActionException e ? e.getCode() : error.getClass().getSimpleName())
                    .put("message", error.getMessage());
        }
        @Override public void save(JSONObject state) throws Exception {
            if (saved.size() + 1 == failSaveAt) throw new IllegalStateException("disk write failed");
            saved.add(new JSONObject(state.toString()));
        }
    }

    @Test public void malformedPlansAreRejectedBeforeAnyExecution() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ActionSequence(sequence(tap(target("n1")))));
        assertThrows(IllegalArgumentException.class, () -> new ActionSequence(sequence(
                tap(target("n1")).put("terminal", true), tap(target("n2")))));
        assertThrows(IllegalArgumentException.class, () -> new ActionSequence(sequence(
                tap(target("n1")).put("x", 10), tap(target("n2")))));
        assertThrows(IllegalArgumentException.class, () -> new ActionSequence(sequence(
                tap(new JSONObject().put("nodeId", "n1").put("text", "搜索")), tap(target("n2")))));
    }

    @Test public void inputThenSearchUsesTheNewSnapshotAndOneFinalObservation() throws Exception {
        JSONObject first = node("n1", "input", ""), search = node("n2", "search", "搜索");
        Fake host = new Fake(page("initial", first, search));
        host.execution = (action, args, index, h) -> {
            if (index == 0) {
                h.current = page("after-input", node("n9", "input", "私密查询"), node("n4", "search", "搜索"));
            } else {
                assertEquals("after-input", args.getString("snapshotId"));
                assertEquals("n4", args.getString("nodeId"));
                h.current = page("result", node("n0", "result", "结果"));
            }
            return h.receipt();
        };
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "input_text")
                .put("target", target("n1")).put("text", "私密查询"), tap(new JSONObject().put("text", "搜索")))).run(host);
        assertEquals("sequence_completed", result.getString("status"));
        assertEquals(2, result.getInt("completedSteps")); assertEquals(2, host.calls.size());
        assertEquals("result", result.getJSONObject("afterObservation").getString("snapshotId"));
        assertFalse(result.getJSONObject("afterObservation").getJSONArray("nodes").getJSONObject(0).has("sequenceIdentity"));
        for (JSONObject saved : host.saved) {
            assertFalse(saved.toString().contains("私密查询")); assertFalse(saved.toString().contains("afterObservation"));
        }
    }

    @Test public void initialNodeReferencesAreMatchedByIdentityNotNewNodeNumbers() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲"), node("n2", "second", "乙")));
        host.execution = (action, args, index, h) -> {
            if (index == 0) h.current = page("next", node("n2", "first", "甲"), node("n8", "second", "乙"));
            else assertEquals("n8", args.getString("nodeId"));
            return h.receipt();
        };
        assertEquals("sequence_completed", new ActionSequence(sequence(tap(target("n1")), tap(target("n2")))).run(host).getString("status"));
    }

    @Test public void replacementAtTheSameBoundsDoesNotReceiveTheNextClick() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲"), node("n2", "second", "乙")));
        host.execution = (action, args, index, h) -> { h.current = page("next", node("n2", "replacement", "乙")); return h.receipt(); };
        JSONObject result = new ActionSequence(sequence(tap(target("n1")), tap(target("n2")))).run(host);
        assertEquals("sequence_stopped", result.getString("status")); assertEquals(1, host.calls.size());
        assertEquals("sequence_target_changed", result.getJSONObject("error").getString("code"));
    }

    @Test public void ambiguousFutureSelectorStopsAfterTheCompletedPrefix() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲")));
        host.execution = (action, args, index, h) -> { h.current = page("next", node("n2", "two", "继续"), node("n3", "three", "继续")); return h.receipt(); };
        JSONObject result = new ActionSequence(sequence(tap(target("n1")), tap(new JSONObject().put("text", "继续")))).run(host);
        assertEquals("sequence_target_ambiguous", result.getJSONObject("error").getString("code"));
        assertEquals(1, result.getInt("completedSteps")); assertEquals(1, host.calls.size());
    }

    @Test public void packageChangeReturnsToTheModelWithoutFollowingThePlan() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲")));
        host.execution = (action, args, index, h) -> { h.current = page("permission", node("n0", "permission", "允许")).put("packageName", "com.android.permissioncontroller"); return h.receipt(); };
        JSONObject result = new ActionSequence(sequence(tap(target("n1")), tap(new JSONObject().put("text", "允许")))).run(host);
        assertEquals("sequence_page_changed", result.getJSONObject("error").getString("code")); assertEquals(1, host.calls.size());
    }

    @Test public void afterConditionTimeoutCannotDispatchTheSubmitStep() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲"), node("n2", "send", "发送")));
        host.execution = (action, args, index, h) -> h.receipt().put("afterConditionStatus", "timed_out");
        JSONObject result = new ActionSequence(sequence(tap(target("n1")).put("afterCondition", new JSONObject().put("text", "已选择")),
                tap(target("n2")).put("terminal", true))).run(host);
        assertEquals(1, host.calls.size()); assertTrue(result.getBoolean("outcomeUnknown"));
        assertEquals("condition_not_matched", result.getJSONObject("error").getString("code"));
    }

    @Test public void unavailablePostconditionObservationCannotDispatchTheSubmitStep() throws Exception {
        for (String conditionStatus : new String[]{"", "unavailable", "failed"}) {
            Fake host = new Fake(page("initial", node("n1", "first", "甲"), node("n2", "send", "发送")));
            host.execution = (action, args, index, h) -> {
                JSONObject receipt = h.receipt().put("afterObservation", new JSONObject().put("status", "unavailable"));
                if (!conditionStatus.isEmpty()) receipt.put("afterConditionStatus", conditionStatus);
                return receipt;
            };
            JSONObject result = new ActionSequence(sequence(tap(target("n1"))
                    .put("afterCondition", new JSONObject().put("text", "已选择")), tap(target("n2")).put("terminal", true))).run(host);
            assertEquals("sequence_stopped", result.getString("status")); assertEquals(1, host.calls.size());
            assertEquals(0, result.getInt("completedSteps")); assertTrue(result.getBoolean("outcomeUnknown"));
            assertTrue(result.getJSONObject("error").getBoolean("dispatched"));
            assertEquals("condition_not_matched", result.getJSONObject("error").getString("code"));
        }
    }

    @Test public void waitStepRequiresItsOwnMatchedStatusBeforeSubmit() throws Exception {
        for (String status : new String[]{"", "action_completed", "unavailable", "timed_out"}) {
            Fake host = new Fake(page("initial", node("n1", "send", "发送")));
            host.execution = (action, args, index, h) -> new JSONObject().put("status", status)
                    .put("afterConditionStatus", "matched").put("observation", h.current);
            JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "wait_for")
                    .put("afterCondition", new JSONObject().put("text", "加载完成")), tap(target("n1")).put("terminal", true))).run(host);
            assertEquals("sequence_stopped", result.getString("status")); assertEquals(1, host.calls.size());
            assertFalse(result.getBoolean("dispatched")); assertFalse(result.getBoolean("outcomeUnknown"));
            assertEquals("condition_not_matched", result.getJSONObject("error").getString("code"));
        }
    }

    @Test public void alreadySelectedTargetStillRequiresAnExplicitPostcondition() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "picture", "照片甲").put("checkable", true).put("checked", true),
                node("n2", "send", "发送")));
        host.execution = (action, args, index, h) -> {
            assertEquals("wait_for", action);
            return new JSONObject().put("status", "unavailable").put("observation", h.current);
        };
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1"))
                .put("selected", true).put("afterCondition", new JSONObject().put("text", "发送(1)")),
                tap(target("n2")).put("terminal", true))).run(host);
        assertEquals("sequence_stopped", result.getString("status")); assertEquals(1, host.calls.size());
        assertFalse(result.getBoolean("dispatched")); assertEquals(0, result.getInt("completedSteps"));
    }

    @Test public void matchedWaitStepContinuesUsingTheReturnedSnapshot() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "send", "发送")));
        host.execution = (action, args, index, h) -> index == 0
                ? new JSONObject().put("status", "matched").put("observation", h.current) : h.receipt();
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "wait_for")
                .put("afterCondition", new JSONObject().put("text", "发送")), tap(target("n1")).put("terminal", true))).run(host);
        assertEquals("sequence_completed", result.getString("status")); assertEquals(2, host.calls.size());
    }

    @Test public void unverifiedInputDoesNotContinueToSubmit() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "input", ""), node("n2", "submit", "搜索")));
        host.execution = (action, args, index, h) -> h.receipt().put("inputTextVerified", false);
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "input_text").put("target", target("n1")).put("text", "面包"), tap(target("n2")))).run(host);
        assertEquals("input_not_verified", result.getJSONObject("error").getString("code")); assertEquals(1, host.calls.size());
    }

    @Test public void checkedTargetsAreNotToggledAndTheOtherTargetIsSelectedOnce() throws Exception {
        JSONObject selected = node("n1", "first", "照片一").put("checkable", true).put("checked", true);
        JSONObject other = node("n2", "second", "照片二").put("checkable", true);
        Fake host = new Fake(page("initial", selected, other));
        host.execution = (action, args, index, h) -> {
            assertEquals("n2", args.getString("nodeId"));
            assertFalse("Selection's local wait must not become a native postcondition without a condition", args.has("afterTimeoutMs"));
            h.current = page("selected", selected, new JSONObject(other.toString()).put("checked", true)); return h.receipt();
        };
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1")).put("selected", true),
                new JSONObject().put("action", "set_selected").put("target", target("n2")).put("selected", true).put("afterTimeoutMs", 1000))).run(host);
        assertEquals("sequence_completed", result.getString("status")); assertEquals(1, host.calls.size());
        assertEquals("already_selected_state", result.getJSONArray("completed").getJSONObject(0).getString("status"));
    }

    @Test public void existingCountDoesNotProveAnOpaqueTargetIsSelected() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "opaque", "选择此媒体"), node("n2", "send", "发送(2)")));
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1"))
                .put("selected", true).put("afterCondition", new JSONObject().put("text", "发送(2)")), tap(target("n2")).put("terminal", true))).run(host);
        assertEquals("selection_unverifiable", result.getJSONObject("error").getString("code")); assertEquals(0, host.calls.size());
    }

    @Test public void stopBetweenStepsPreservesCompletedPrefixWithoutReplay() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲"), node("n2", "second", "乙")));
        host.stopAt = 3;
        JSONObject result = new ActionSequence(sequence(tap(target("n1")), tap(target("n2")))).run(host);
        assertEquals("sequence_stopped", result.getString("status")); assertEquals(1, host.calls.size());
        assertEquals(1, result.getInt("completedSteps")); assertFalse(result.getBoolean("replayedAction"));
    }

    @Test public void interruptedPersistentReceiptIsReturnedAsUnknownWithoutReplay() throws Exception {
        JSONObject result = ActionSequence.resumed(new JSONObject().put("status", "running").put("completedSteps", 1)
                .put("inFlightStep", 1).put("inFlightAction", "tap"));
        assertEquals("sequence_interrupted", result.getString("status")); assertTrue(result.getBoolean("outcomeUnknown"));
        assertTrue(result.getBoolean("needsObservation")); assertFalse(result.getBoolean("replayedAction"));
        JSONObject complete = ActionSequence.resumed(new JSONObject().put("status", "sequence_completed").put("completedSteps", 2));
        assertEquals("sequence_completed", complete.getString("status")); assertTrue(complete.getBoolean("existingReceipt"));
    }

    @Test public void opaqueSelectionRequiresAnExactCounterTransitionAndStableTarget() throws Exception {
        JSONObject picture = node("n1", "picture", "选择此媒体");
        Fake host = new Fake(page("initial", picture, node("n2", "send", "发送")));
        host.execution = (action, args, index, h) -> {
            h.current = page("selected", new JSONObject(picture.toString()).put("description", "1"), node("n2", "send", "发送(1)"));
            return h.receipt().put("afterConditionStatus", "matched");
        };
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1"))
                .put("selected", true).put("afterCondition", new JSONObject().put("text", "发送(1)")),
                tap(new JSONObject().put("text", "发送(1)")).put("terminal", true))).run(host);
        assertEquals("sequence_completed", result.getString("status")); assertEquals(2, host.calls.size());
        JSONObject first = result.getJSONArray("completed").getJSONObject(0);
        assertEquals("count_transition", first.getString("selectionVerification"));
        assertFalse(first.getBoolean("selectionVerified")); assertTrue(first.getBoolean("selectionCountVerified"));
    }

    @Test public void unrelatedConditionOrTwoCountJumpCannotVerifyAnOpaqueSelection() throws Exception {
        for (JSONObject condition : new JSONObject[]{new JSONObject().put("packageName", "com.example.fixture"),
                new JSONObject().put("text", "发送(2)")}) {
            Fake host = new Fake(page("initial", node("n1", "picture", "选择此媒体"), node("n2", "send", "发送")));
            JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1"))
                    .put("selected", true).put("afterCondition", condition), tap(target("n2")))).run(host);
            assertEquals("selection_unverifiable", result.getJSONObject("error").getString("code")); assertEquals(0, host.calls.size());
        }
    }

    @Test public void matchingCountWithAReplacedPictureStopsBeforeSend() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "picture", "选择此媒体"), node("n2", "send", "发送")));
        host.execution = (action, args, index, h) -> {
            h.current = page("selected", node("n1", "replacement", "选择此媒体"), node("n2", "send", "发送(1)"));
            return h.receipt().put("afterConditionStatus", "matched");
        };
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1"))
                .put("selected", true).put("afterCondition", new JSONObject().put("text", "发送(1)")),
                tap(new JSONObject().put("text", "发送(1)")).put("terminal", true))).run(host);
        assertEquals("sequence_target_changed", result.getJSONObject("error").getString("code"));
        assertTrue(result.getBoolean("outcomeUnknown")); assertEquals(1, host.calls.size());
    }

    @Test public void cancellationDuringSelectionVerificationRetainsUnknownDispatchedState() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "picture", "照片一").put("checkable", true), node("n2", "send", "发送")));
        host.stopAt = 3;
        JSONObject result = new ActionSequence(sequence(new JSONObject().put("action", "set_selected").put("target", target("n1"))
                .put("selected", true), tap(target("n2")))).run(host);
        assertEquals("sequence_stopped", result.getString("status")); assertTrue(result.getBoolean("outcomeUnknown"));
        assertTrue(result.getBoolean("dispatched")); assertEquals(1, host.calls.size());
        assertTrue(host.saved.get(host.saved.size() - 1).getBoolean("outcomeUnknown"));
    }

    @Test public void failedReceiptWriteDoesNotReplaceTheDurableInFlightMarker() throws Exception {
        Fake host = new Fake(page("initial", node("n1", "first", "甲"), node("n2", "second", "乙")));
        host.failSaveAt = 3;
        ActionException error = assertThrows(ActionException.class, () ->
                new ActionSequence(sequence(tap(target("n1")), tap(target("n2")))).run(host));
        assertEquals("sequence_receipt_failed", error.getCode()); assertTrue(error.getDetails().getBoolean("outcomeUnknown"));
        assertEquals(1, host.calls.size()); assertEquals(2, host.saved.size());
        assertEquals(0, host.saved.get(1).getInt("inFlightStep"));
    }
}

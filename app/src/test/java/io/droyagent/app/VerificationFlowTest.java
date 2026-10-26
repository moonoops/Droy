package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VerificationFlowTest {
    private final AtomicLong clock = new AtomicLong(1000);
    private final VerificationFlow flow = new VerificationFlow(clock::get);
    private static final String TASK = "fixture-task", APP = "example.validation";

    @Test public void automaticAttemptNeedsImageCapabilityAndCurrentScreenshot() throws Exception {
        observe(TASK, "tree", false, APP);
        assertEquals("manual_required", assess(TASK, "tree", "text", true, true).getString("state"));
        VerificationFlow noImage = new VerificationFlow(clock::get);
        noImage.returned(TASK, "screenshot", evidence("image", APP));
        assertEquals("manual_required", noImage.request(TASK, assessment("image", "text", true, false)).getString("state"));
    }

    @Test public void sensitiveAndUncertainChallengesNeverGetAutomaticActionBudget() throws Exception {
        for (String kind : new String[]{"otp", "password", "identity", "biometric", "blocked", "unknown"}) {
            String task = "fixture-" + kind;
            observe(task, kind, true, APP);
            assertEquals("manual_required", assess(task, kind, kind, true, true).getString("state"));
            expect("verification_requires_user", () -> flow.beforeAction(task, "tap", basis(kind), true));
        }
        observe("uncertain", "unknown-image", true, APP);
        assertEquals("manual_required", assess("uncertain", "unknown-image", "puzzle", false, true).getString("state"));
    }

    @Test public void swipeBudgetCannotBeReplenishedByAssessWithinTheRun() throws Exception {
        observe(TASK, "image", true, APP);
        assertEquals("automatic_allowed", assess(TASK, "image", "slider", true, true).getString("state"));
        flow.beforeAction(TASK, "swipe", basis("image"), true);
        flow.returned(TASK, "swipe", new JSONObject().put("afterObservation", evidence("after", APP)));
        observe(TASK, "same-run", true, APP);
        JSONObject existing = assess(TASK, "same-run", "slider", true, true);
        assertEquals(0, existing.getJSONObject("remainingActions").getInt("swipe"));
        expect("verification_requires_user", () -> flow.beforeAction(TASK, "swipe", basis("same-run"), true));
        assertEquals("manual_required", assess(TASK, "same-run", "slider", true, true).getString("state"));
    }

    @Test public void reportedPassRequiresFreshResultAndDoesNotClaimBusinessCompletion() throws Exception {
        observe(TASK, "image", true, APP);
        assess(TASK, "image", "puzzle", true, true);
        expect("stale_snapshot", () -> complete(TASK, "image", true));
        flow.beforeAction(TASK, "swipe", basis("image"), true);
        expect("stale_snapshot", () -> complete(TASK, "image", true));
        flow.returned(TASK, "swipe", new JSONObject().put("afterObservation", evidence("result", APP)));
        JSONObject result = complete(TASK, "result", true);
        assertEquals("verified", result.getString("state"));
        assertFalse(result.getBoolean("goalVerified"));
        flow.beforeAction(TASK, "open_app", new JSONObject(), true);
    }

    @Test public void failedAttemptMustUseHumanHandoffAndReviewOriginalApp() throws Exception {
        observe(TASK, "image", true, APP);
        assess(TASK, "image", "text", true, true);
        flow.beforeAction(TASK, "input_text", basis("image"), true);
        flow.failed(TASK, true);
        assertEquals("manual_required", complete(TASK, "image", false).getString("state"));
        expect("verification_requires_user", () -> complete(TASK, "image", true));
        flow.request(TASK, new JSONObject().put("operation", "handoff"));
        expect("stale_snapshot", () -> resume("image"));
        observe(TASK, "chat", false, "io.droyagent.app");
        expect("verification_target_changed", () -> resume("chat"));
        observe(TASK, "human-result", false, APP);
        assertEquals("awaiting_review", resume("human-result").getString("state"));
        expect("verification_review_required", () -> flow.beforeAction(TASK, "tap", basis("human-result"), true));
        assertEquals("verified", complete(TASK, "human-result", true).getString("state"));
    }

    @Test public void manualReviewFailureNeedsAnotherExplicitHandoff() throws Exception {
        observe(TASK, "image", true, APP);
        assess(TASK, "image", "identity", false, true);
        flow.request(TASK, new JSONObject().put("operation", "handoff"));
        observe(TASK, "human-result", false, APP);
        resume("human-result");
        complete(TASK, "human-result", false);
        observe(TASK, "again", false, APP);
        expect("invalid_argument", () -> resume("again"));
    }

    @Test public void rootAndAlternativeScreenActionsCannotContinueAnAutomaticAttempt() throws Exception {
        for (String action : new String[]{"back", "open_app", "gesture", "browser_click", "swipe"}) {
            String task = "fixture-" + action;
            observe(task, action, true, APP);
            assess(task, action, "slider", true, true);
            JSONObject args = basis(action);
            if (action.equals("swipe")) args.put("channel", "root");
            expect("verification_requires_user", () -> flow.beforeAction(task, action, args, true));
        }
    }

    @Test public void orderedPointBudgetIsBoundedWithoutBlockingReadOnlyObservation() throws Exception {
        observe(TASK, "image", true, APP);
        assess(TASK, "image", "ordered_points", true, true);
        String snapshot = "image";
        for (int i = 0; i < 8; i++) {
            flow.beforeAction(TASK, "tap", basis(snapshot), true);
            snapshot = "point-" + i;
            flow.returned(TASK, "tap", new JSONObject().put("afterObservation", evidence(snapshot, APP)));
        }
        String last = snapshot;
        expect("verification_requires_user", () -> flow.beforeAction(TASK, "tap", basis(last), true));
        flow.beforeAction(TASK, "observe", new JSONObject(), false);
        flow.beforeAction(TASK, "wait_for", new JSONObject(), false);
    }

    @Test public void snapshotMustBeCurrentForSameTaskAndWithinAgeLimit() throws Exception {
        observe(TASK, "first", true, APP);
        observe(TASK, "second", true, APP);
        expect("stale_snapshot", () -> assess(TASK, "first", "text", true, true));
        expect("stale_snapshot", () -> assess("other", "second", "text", true, true));
        clock.addAndGet(15001);
        expect("stale_snapshot", () -> assess(TASK, "second", "text", true, true));
        observe(TASK, "third", true, APP);
        clock.addAndGet(-1);
        expect("stale_snapshot", () -> assess(TASK, "third", "text", true, true));
    }

    @Test public void capacityRejectsNewChallengesWithoutEvictingExistingGates() throws Exception {
        for (int i = 0; i < 64; i++) {
            String task = "fixture-" + i;
            observe(task, task, false, APP);
            assess(task, task, "unknown", false, false);
        }
        observe("overflow", "overflow", true, APP);
        expect("verification_capacity", () -> assess("overflow", "overflow", "text", true, true));
        expect("verification_requires_user", () -> flow.beforeAction("fixture-0", "tap", basis("fixture-0"), true));
    }

    @Test public void invalidBooleanInputsAndUnknownOperationsAreRejected() throws Exception {
        observe(TASK, "image", true, APP);
        JSONObject args = assessment("image", "text", true, true).put("solvable", "true");
        expect("invalid_argument", () -> flow.request(TASK, args));
        expect("invalid_argument", () -> flow.request(TASK, new JSONObject().put("operation", "skip")));
    }

    @Test public void screenMutationClassificationIncludesSeparateAppAndBrowserChannels() {
        for (String action : new String[]{"open_app", "browser_open", "browser_click", "browser_input", "browser_back"})
            assertTrue(action, VerificationFlow.affectsScreen(action, false, false));
        assertTrue(VerificationFlow.affectsScreen("gesture", true, false));
        assertFalse(VerificationFlow.affectsScreen("browser_observe", false, true));
        assertFalse(VerificationFlow.affectsScreen("verification", true, false));
    }

    @Test public void returningToAppUsesOnlyBoundTargetAndCannotClearGate() throws Exception {
        observe(TASK, "image", true, APP);
        assess(TASK, "image", "identity", false, true);
        expect("invalid_argument", () -> flow.returnTarget(TASK));
        flow.request(TASK, new JSONObject().put("operation", "handoff"));
        observe(TASK, "chat", false, "io.droyagent.app");
        assertEquals(APP, flow.returnTarget(TASK));
        expect("stale_snapshot", () -> resume("chat"));
        expect("verification_requires_user", () -> flow.beforeAction(TASK, "open_app", new JSONObject(), true));
    }

    @Test public void endingRunReleasesGateAndOldEvidenceForLaterUserGoals() throws Exception {
        observe(TASK, "image", true, APP);
        assess(TASK, "image", "identity", false, true);
        flow.end(TASK);
        flow.beforeAction(TASK, "open_app", new JSONObject(), true);
        expect("stale_snapshot", () -> assess(TASK, "image", "text", true, true));
        observe(TASK, "new-request", true, APP);
        assertEquals("automatic_allowed", assess(TASK, "new-request", "text", true, true).getString("state"));
    }

    private void observe(String task, String id, boolean image, String pkg) throws Exception {
        flow.returned(task, image ? "screenshot" : "observe", evidence(id, pkg));
    }
    private JSONObject assess(String task, String id, String kind, boolean solvable, boolean image) throws Exception {
        return flow.request(task, assessment(id, kind, solvable, image));
    }
    private static JSONObject assessment(String id, String kind, boolean solvable, boolean image) throws Exception {
        return basis(id).put("operation", "assess").put("kind", kind).put("solvable", solvable).put("imageSupported", image);
    }
    private JSONObject complete(String task, String id, boolean passed) throws Exception {
        return flow.request(task, basis(id).put("operation", "complete").put("passed", passed));
    }
    private JSONObject resume(String id) throws Exception { return flow.request(TASK, basis(id).put("operation", "resume")); }
    private static JSONObject evidence(String id, String pkg) throws Exception {
        return basis(id).put("packageName", pkg).put("snapshotMaxAgeMs", 15000);
    }
    private static JSONObject basis(String id) throws Exception { return new JSONObject().put("snapshotId", id); }
    private static void expect(String code, Throwing action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ActionException actual) { assertEquals(code, actual.getCode()); }
    }
    private interface Throwing { void run() throws Exception; }
}

package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy;
import io.droyagent.app.nativeui.permissions.NativePendingPermissionRequest;
import io.droyagent.app.nativeui.permissions.NativePermissionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeFirstUseAuthorizationTest {
    @Test public void declinedFirstUseDoesNotReopenForAnotherCallOrReviveTheOldOne() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000);
            NativePendingPermissionRequest row = f.pending();
            f.service.declinePending(row.getId());
            assertTrue(rejection(first) instanceof InterruptedException);
            assertTrue(f.host.pending().isEmpty());
            assertEquals(NativePermissionOutcome.SKIPPED, f.host.outcomes.get("contacts"));
            ActionException denied = (ActionException) rejection(f.start("two", 10000));
            assertEquals("authorization_previously_requested", denied.getReason());
            f.grant();
            assertEquals("authorization_expired", ((ActionException) rejection(f.start("one", 10000))).getReason());
        }
    }

    @Test public void ordinaryApprovalRechecksActualGrantsAndNeverTreatsAProcessedDialogAsGranted() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000);
            NativePendingPermissionRequest row = f.pending();
            assertEquals(Set.of("allowContacts"), f.service.approvePending(row.getId()).getCapabilityKeys());
            f.host.capabilities.add("allowContacts");
            f.service.finishPending(row.getId());
            assertEquals("authorization_not_granted", ((ActionException) rejection(first)).getReason());
            assertEquals(NativePermissionOutcome.GRANTED, f.host.outcomes.get("capability:allowContacts"));
            assertEquals(NativePermissionOutcome.NOT_GRANTED, f.host.outcomes.get("contacts"));
            assertTrue(f.host.pending().isEmpty());
        }
    }

    @Test public void cancellationBeforeRegistrationCreatesNoPendingRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            f.control.cancel("task", "one");
            assertTrue(rejection(f.start("one", 10000)) instanceof IllegalStateException);
            assertTrue(f.host.pending().isEmpty());
        }
    }

    @Test public void cancellationIsBoundToBothTaskAndCallAndEndsTheLiveWait() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000);
            NativePendingPermissionRequest row = f.pending();
            f.control.cancel("other-task", "one"); f.service.cancel("other-task", "one");
            assertTrue(f.service.active(row.getId())); assertFalse(first.isDone());
            f.control.cancel("task", "one"); f.service.cancel("task", "one");
            assertTrue(rejection(first) instanceof InterruptedException);
            assertTrue(f.host.pending().isEmpty());
            assertThrows(IllegalStateException.class, () -> f.control.begin("task", "one"));
        }
    }

    @Test public void stopAndEnableDuringHumanWaitCannotReviveItsOldTicket() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000); f.pending();
            f.control.stop(); f.control.enable();
            assertTrue(rejection(first) instanceof InterruptedException);
            assertEquals(NativePermissionOutcome.SKIPPED, f.host.outcomes.get("contacts"));
            assertTrue(f.host.pending().isEmpty());
        }
    }

    @Test public void allowedCallHasOnlyOneHandoffAndDoesNotStoreItsArguments() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000);
            NativePendingPermissionRequest row = f.pending();
            assertEquals("task", row.getTaskId()); assertEquals("one", row.getCallId());
            assertFalse(row.toString().contains("private fixture text"));
            f.service.approvePending(row.getId()); f.grant(); f.service.finishPending(row.getId());
            first.get(2, TimeUnit.SECONDS);
            ControlPolicy.Ticket actual = f.control.begin("task", "one", 1000);
            try { f.service.checkDispatch("task", "one", actual, f.control); actual.dispatched(); }
            finally { f.control.finish(actual); }
            ControlPolicy.Ticket duplicate = f.control.begin("task", "one", 1000);
            try { assertThrows(ActionException.class, () -> f.service.checkDispatch("task", "one", duplicate, f.control)); assertFalse(duplicate.wasDispatched()); }
            finally { f.control.finish(duplicate); }
        }
    }

    @Test public void stopAfterAuthorizationButBeforeDispatchInvalidatesTheHandoffEpoch() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000);
            NativePendingPermissionRequest row = f.pending();
            f.service.approvePending(row.getId()); f.grant(); f.service.finishPending(row.getId()); first.get(2, TimeUnit.SECONDS);
            f.control.stop(); f.control.enable();
            ControlPolicy.Ticket actual = f.control.begin("task", "one", 1000);
            try { assertThrows(InterruptedException.class, () -> f.service.checkDispatch("task", "one", actual, f.control)); assertFalse(actual.wasDispatched()); }
            finally { f.control.finish(actual); }
        }
    }

    @Test public void authorizationDeadlineRemovesPendingAndSuppressesRepeatedAutomaticRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 1000); f.pending(); f.host.now.addAndGet(1000);
            assertEquals("permission_timeout", ((ActionException) rejection(first)).getCode());
            assertTrue(f.host.pending().isEmpty());
            assertEquals(NativePermissionOutcome.SKIPPED, f.host.outcomes.get("contacts"));
            assertEquals("authorization_previously_requested", ((ActionException) rejection(f.start("two", 10000))).getReason());
        }
    }

    @Test public void aNewBridgeCannotConsumeAnOldHandoffEvenWhenItsEpochNumberMatches() throws Exception {
        try (Fixture f = new Fixture()) {
            Future<JSONObject> first = f.start("one", 10000);
            NativePendingPermissionRequest row = f.pending();
            f.service.approvePending(row.getId()); f.grant(); f.service.finishPending(row.getId()); first.get(2, TimeUnit.SECONDS);
            ControlPolicy replacement = new ControlPolicy(f.host.now::get); replacement.enable();
            ControlPolicy.Ticket late = replacement.begin("task", "one", 1000);
            try { assertThrows(InterruptedException.class, () -> f.service.checkDispatch("task", "one", late, replacement)); assertFalse(late.wasDispatched()); }
            finally { replacement.finish(late); }
        }
    }

    @Test public void independentAccessIsNeverOfferedByAnOrdinaryFirstUseRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            f.host.plan = new NativeFirstUseAuthorization.Requirements(Set.of("allowRoot"), Set.of(), false);
            assertEquals("independent_authorization_required", ((ActionException) rejection(f.start("root", 10000))).getReason());
            for (String id : List.of("accessibility", "root", "shizuku")) {
                f.host.plan = new NativeFirstUseAuthorization.Requirements(Set.of("allowContacts"), Set.of(id), false);
                assertEquals("independent_authorization_required", ((ActionException) rejection(f.start(id, 10000))).getReason());
            }
            assertTrue(f.host.pending().isEmpty()); assertTrue(f.host.capabilities.isEmpty());
        }
    }

    @Test public void recoveredRowsAreSkippedAndCannotAuthorizeTheOldCallAfterManualGrants() throws Exception {
        FakeHost host = new FakeHost();
        host.rows.put("old", new NativePendingPermissionRequest("old", "task", "old-call", Set.of("allowContacts"), Set.of("contacts"), 1));
        NativeFirstUseAuthorization recovered = new NativeFirstUseAuthorization(host);
        assertTrue(host.pending().isEmpty()); assertEquals(NativePermissionOutcome.SKIPPED, host.outcomes.get("contacts"));
        host.capabilities.add("allowContacts"); host.system.add("contacts");
        ControlPolicy policy = new ControlPolicy(host.now::get); policy.enable();
        assertThrows(ActionException.class, () -> recovered.preflight("contacts_query", new JSONObject(), "task", "old-call", 1000, policy));
        ControlPolicy.Ticket late = policy.begin("task", "old-call", 1000);
        try { assertThrows(ActionException.class, () -> recovered.checkDispatch("task", "old-call", late, policy)); assertFalse(late.wasDispatched()); }
        finally { policy.finish(late); }
    }

    @Test public void initialFirstUseResumesOnlyTheProvenNewInstallDefault() {
        assertTrue(NativeFirstUseAuthorization.initialFirstUseMayResume(true, true, true, false, true, true, false));
        for (int denied = 0; denied < 7; denied++) {
            boolean[] facts = {true, true, true, false, true, true, false}; facts[denied] = !facts[denied];
            assertFalse(NativeFirstUseAuthorization.initialFirstUseMayResume(facts[0], facts[1], facts[2], facts[3], facts[4], facts[5], facts[6]));
        }
    }

    private static Throwable rejection(Future<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause();
    }
    private static final class Fixture implements AutoCloseable {
        final FakeHost host = new FakeHost();
        final NativeFirstUseAuthorization service = new NativeFirstUseAuthorization(host);
        final ControlPolicy control = new ControlPolicy(host.now::get);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        Fixture() { control.enable(); }
        Future<JSONObject> start(String call, long budget) {
            return worker.submit(() -> service.preflight("contacts_query", new JSONObject().put("query", "private fixture text"), "task", call, budget, control));
        }
        NativePendingPermissionRequest pending() throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (host.rows.isEmpty()) { if (System.nanoTime() >= until) throw new AssertionError("No pending request"); Thread.sleep(1); }
            return host.pending().get(0);
        }
        void grant() { host.capabilities.add("allowContacts"); host.system.add("contacts"); }
        public void close() throws Exception { control.stop(); service.cancelPending(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS); }
    }
    private static final class FakeHost implements NativeFirstUseAuthorization.Host {
        volatile NativeFirstUseAuthorization.Requirements plan = new NativeFirstUseAuthorization.Requirements(Set.of("allowContacts"), Set.of("contacts"), false);
        final AtomicLong now = new AtomicLong(1000);
        final Set<String> capabilities = ConcurrentHashMap.newKeySet(), system = ConcurrentHashMap.newKeySet(), retired = ConcurrentHashMap.newKeySet();
        final Map<String, NativePermissionOutcome> outcomes = new ConcurrentHashMap<>();
        final Map<String, NativePendingPermissionRequest> rows = new ConcurrentHashMap<>();
        public NativeFirstUseAuthorization.Requirements requirements(String action, JSONObject args) { return plan; }
        public NativeAuthorizationPolicy selected() { return NativeAuthorizationPolicy.ON_FIRST_USE; }
        public boolean paused() { return false; }
        public boolean allowed(String key) { return capabilities.contains(key); }
        public boolean needsSystem(String id) { return !system.contains(id); }
        public boolean automaticAllowed(String id) { return !outcomes.containsKey(id); }
        public void requested(String id) { outcomes.put(id, NativePermissionOutcome.REQUESTING); }
        public void outcome(String id, NativePermissionOutcome outcome) { outcomes.put(id, outcome); }
        public List<NativePendingPermissionRequest> pending() { return new ArrayList<>(rows.values()); }
        public void offer(NativePendingPermissionRequest row) { rows.put(row.getId(), row); }
        public void remove(String id) { rows.remove(id); }
        public boolean retired(String task, String call) { return retired.contains(task + "\0" + call); }
        public void retire(String task, String call) { retired.add(task + "\0" + call); }
        public long elapsed() { return now.get(); }
        public long wallTime() { return now.get(); }
    }
}

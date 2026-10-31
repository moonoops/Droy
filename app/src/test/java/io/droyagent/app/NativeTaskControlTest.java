package io.droyagent.app;

import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTaskControlTest {
    private static NativeTaskControl.State pause(NativeTaskControl control, String task, String operation, long epoch) {
        return control.change(task, operation, "pause", epoch, "pause-token");
    }
    private static void clean(NativeTaskControl control, String task, String operation) { control.cleanup(task, operation).complete(null); }

    @Test public void cancelledWaiterDoesNotRetireAnActualMainCallbackOrGesture() throws Exception {
        NativeTaskControl control = new NativeTaskControl();
        long epoch = control.open("task", "run").epoch();
        NativeTaskControl.Call call = control.admit("task", "run", epoch, "tap", "tap");
        CompletableFuture<Void> actual = new CompletableFuture<>();
        call.dispatched(); call.track("gesture", "gesture-id", actual); call.finish(false);
        NativeTaskControl.State paused = pause(control, "task", "run", epoch);
        assertEquals("pausing", paused.phase()); assertFalse(paused.quiescent());
        assertThrows(InterruptedException.class, call::check);
        assertThrows(NativeTaskControl.Rejected.class, () -> control.admit("task", "run", epoch, "late", "tap"));
        clean(control, "task", "run");
        assertFalse(control.status("task", "run").quiescent());
        assertEquals("gesture-id", control.status("task", "run").pending().get(0).id());
        actual.complete(null);
        assertEquals("paused", control.status("task", "run").phase());
        assertTrue(control.status("task", "run").quiescent());
        assertTrue(control.status("task", "run").needsObservation());
    }

    @Test public void resumeAdmitsOnlyObservationUntilAnExplicitActivation() {
        NativeTaskControl control = new NativeTaskControl();
        long epoch = control.open("task", "run").epoch();
        NativeTaskControl.Call dispatched = control.admit("task", "run", epoch, "old", "tap");
        dispatched.dispatched(); dispatched.finish(false);
        NativeTaskControl.State paused = pause(control, "task", "run", epoch); clean(control, "task", "run");
        NativeTaskControl.State observing = control.change("task", "run", "resume", paused.epoch(), paused.pauseId());
        assertEquals("observing", observing.phase());
        assertThrows(NativeTaskControl.Rejected.class, () -> control.admit("task", "run", observing.epoch(), "write", "input_text"));
        assertThrows(NativeTaskControl.Rejected.class, () -> control.change("task", "run", "activate", observing.epoch(), paused.pauseId()));
        NativeTaskControl.Call observation = control.admit("task", "run", observing.epoch(), "screen", "observe");
        observation.finish(true);
        NativeTaskControl.State active = control.change("task", "run", "activate", observing.epoch(), paused.pauseId());
        assertEquals("active", active.phase());
        assertFalse(control.acceptsCancel("task", "run", epoch));
        assertThrows(NativeTaskControl.Rejected.class, () -> control.admit("task", "run", observing.epoch(), "old-replay", "tap"));
        assertNotNull(control.admit("task", "run", active.epoch(), "fresh", "tap"));
    }

    @Test public void anUndispatchedScopeCanActivateWithoutInventingAnObservation() {
        NativeTaskControl control = new NativeTaskControl();
        NativeTaskControl.State initial = control.open("task", "run");
        NativeTaskControl.State paused = pause(control, "task", "run", initial.epoch()); clean(control, "task", "run");
        assertFalse(control.status("task", "run").needsObservation());
        NativeTaskControl.State resume = control.change("task", "run", "resume", paused.epoch(), paused.pauseId());
        assertEquals("active", control.change("task", "run", "activate", resume.epoch(), paused.pauseId()).phase());
    }

    @Test public void openAndTaskRetirementCannotEraseAnExistingPauseFence() {
        NativeTaskControl control = new NativeTaskControl();
        long epoch = control.open("task", "run").epoch();
        NativeTaskControl.State paused = pause(control, "task", "run", epoch); clean(control, "task", "run");
        assertEquals(paused.epoch(), control.open("task", "run").epoch());
        assertEquals("paused", control.open("task", "run").phase());
        assertThrows(NativeTaskControl.Rejected.class, () -> control.open("task", "new-run"));
        NativeTaskControl.State stopped = control.change("task", "run", "stop", paused.epoch(), paused.pauseId()); clean(control, "task", "run");
        assertThrows(NativeTaskControl.Rejected.class, () -> control.change("task", "run", "resume", stopped.epoch(), stopped.pauseId()));
        assertEquals("active", control.open("task", "new-run").phase());
        assertThrows(NativeTaskControl.Rejected.class, () -> control.admit("task", "run", stopped.epoch(), "late", "tap"));
    }

    @Test public void pausingOneScopeDoesNotCancelAnotherTasksCall() throws Exception {
        NativeTaskControl control = new NativeTaskControl();
        long a = control.open("a", "run-a").epoch(), b = control.open("b", "run-b").epoch();
        NativeTaskControl.Call other = control.admit("b", "run-b", b, "other-call", "tap");
        pause(control, "a", "run-a", a); clean(control, "a", "run-a");
        other.check();
        assertEquals("active", control.status("b", "run-b").phase());
        assertFalse(control.status("b", "run-b").quiescent());
        assertTrue(control.status("a", "run-a").quiescent());
    }

    @Test public void quietRequiresBothRequestFinallyAndActualResourceRetirement() {
        NativeTaskControl control = new NativeTaskControl();
        long epoch = control.open("task", "run").epoch();
        NativeTaskControl.Call call = control.admit("task", "run", epoch, "capture-start", "audio_start");
        CompletableFuture<Void> capture = new CompletableFuture<>(); call.track("audio_capture", "capture-id", capture);
        pause(control, "task", "run", epoch); clean(control, "task", "run");
        capture.complete(null);
        assertFalse(control.status("task", "run").quiescent());
        call.finish(false);
        assertTrue(control.status("task", "run").quiescent());
    }

    @Test public void unconfirmedRootChildSurvivesNativeRegistryRecreationWithoutBlockingAnUnrelatedScope() {
        NativeTaskControl.MemoryRootLedger ledger = new NativeTaskControl.MemoryRootLedger();
        NativeTaskControl old = new NativeTaskControl(ledger);
        long epoch = old.open("task", "run").epoch();
        NativeTaskControl.Call call = old.admit("task", "run", epoch, "root", "tap");
        call.track("root_process", "opaque-root-id", new CompletableFuture<>()); call.finish(false);
        pause(old, "task", "run", epoch); clean(old, "task", "run");
        NativeTaskControl restarted = new NativeTaskControl(ledger);
        assertNotEquals(old.instanceId(), restarted.instanceId());
        assertEquals(java.util.Set.of("opaque-root-id"), restarted.unconfirmedRoot("task"));
        assertEquals("TASK_SCOPE_REQUIRED", assertThrows(NativeTaskControl.Rejected.class, () -> restarted.status("task", "run")).code);
        assertEquals("TASK_NATIVE_STOP_UNCONFIRMED", assertThrows(NativeTaskControl.Rejected.class, () -> restarted.open("task", "fresh-run")).code);
        assertEquals("active", restarted.open("other-task", "other-run").phase());
        assertTrue(restarted.unconfirmedRoot("other-task").isEmpty());
    }

    @Test public void confirmedNormalRootExitClearsTheRecoveryLedger() {
        NativeTaskControl.MemoryRootLedger ledger = new NativeTaskControl.MemoryRootLedger();
        NativeTaskControl control = new NativeTaskControl(ledger);
        long epoch = control.open("task", "run").epoch();
        NativeTaskControl.Call call = control.admit("task", "run", epoch, "root", "tap");
        CompletableFuture<Void> process = new CompletableFuture<>(); call.track("root_process", "opaque-root-id", process); call.finish(false);
        assertFalse(ledger.pending("task").isEmpty());
        process.complete(null);
        assertTrue(ledger.pending("task").isEmpty());
        assertEquals("active", new NativeTaskControl(ledger).open("task", "fresh-run").phase());
    }

    @Test public void ordinaryAppCallbacksDoNotCreatePrivilegedRecoveryClaims() {
        NativeTaskControl.MemoryRootLedger ledger = new NativeTaskControl.MemoryRootLedger();
        NativeTaskControl control = new NativeTaskControl(ledger);
        long epoch = control.open("task", "run").epoch();
        NativeTaskControl.Call call = control.admit("task", "run", epoch, "call", "tap");
        call.track("main_callback", "callback", new CompletableFuture<>()); call.finish(false);
        assertTrue(ledger.pending("task").isEmpty());
    }

    @Test public void onlyAPositivelyChangedKnownBootRetiresUnconfirmedRootWork() {
        NativeTaskControl.MemoryRootLedger ledger = new NativeTaskControl.MemoryRootLedger();
        ledger.boot(5); ledger.add("task", "run", "opaque");
        ledger.boot(5);
        assertFalse(ledger.pending("task").isEmpty());
        ledger.boot(-1); ledger.boot(6);
        assertFalse("An unknown interval cannot prove when the pending command started", ledger.pending("task").isEmpty());
        ledger.boot(7);
        assertTrue(ledger.pending("task").isEmpty());
        assertEquals("active", new NativeTaskControl(ledger).open("task", "new-run").phase());
    }

    @Test public void epochsMustBeExactSafeIntegers() {
        assertEquals(1, NativeTaskControl.epoch(1));
        for (Object invalid : java.util.List.of(1.5, -1, Double.NaN, 9_007_199_254_740_992d, "1"))
            assertThrows(IllegalArgumentException.class, () -> NativeTaskControl.epoch(invalid));
    }
}

package io.droyagent.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Native admission and actual outstanding work; model/UI revisions are not authority here. */
final class NativeTaskControl {
    interface RootLedger {
        void add(String task, String operation, String id);
        void remove(String task, String operation, String id);
        Set<String> pending(String task);
    }
    static final class MemoryRootLedger implements RootLedger {
        private final Map<String, Set<String>> pending = new HashMap<>();
        private int boot = -1;
        synchronized void boot(int current) { if (newerBoot(boot, current)) pending.clear(); boot = current; }
        public synchronized void add(String task, String operation, String id) { pending.computeIfAbsent(task, ignored -> new java.util.HashSet<>()).add(id); }
        public synchronized void remove(String task, String operation, String id) {
            Set<String> values = pending.get(task); if (values != null) { values.remove(id); if (values.isEmpty()) pending.remove(task); }
        }
        public synchronized Set<String> pending(String task) { return Set.copyOf(pending.getOrDefault(task, Set.of())); }
    }
    private static final NativeTaskControl SHARED = new NativeTaskControl();
    static NativeTaskControl shared() { return SHARED; }
    record Pending(String kind, String id, String callId, boolean dispatched) {}
    record State(String taskId, String operationId, long epoch, String pauseId, String phase,
                 boolean paused, boolean quiescent, List<Pending> pending, boolean outcomeUnknown, boolean needsObservation) {}
    static final class Rejected extends IllegalStateException {
        final String code;
        Rejected(String code) { super(code); this.code = code; }
    }
    private static final class Scope {
        final String task, operation;
        final List<Call> calls = new ArrayList<>();
        long epoch = 1;
        String phase = "active", pauseId = "";
        boolean dispatched, observed;
        CompletableFuture<Void> cleanup;
        Scope(String task, String operation) { this.task = task; this.operation = operation; }
    }
    private final Map<String, Scope> current = new HashMap<>();
    private RootLedger rootLedger;
    private final String instanceId = UUID.randomUUID().toString();
    String instanceId() { return instanceId; }
    private final Map<String, Scope> operations = new LinkedHashMap<>();
    NativeTaskControl() { this(new MemoryRootLedger()); }
    NativeTaskControl(RootLedger ledger) { rootLedger = ledger; }
    synchronized void rootLedger(RootLedger ledger) { rootLedger = ledger; }
    synchronized Set<String> unconfirmedRoot(String task) { return rootLedger.pending(task); }

    synchronized State open(String task, String operation) {
        identity(task); identity(operation);
        Scope existing = operations.get(key(task, operation));
        if (existing != null) return state(existing);
        if (!rootLedger.pending(task).isEmpty()) throw new Rejected("TASK_NATIVE_STOP_UNCONFIRMED");
        Scope previous = current.get(task);
        if (previous != null && (!quiet(previous) || !Set.of("active", "stopped").contains(previous.phase)))
            throw new Rejected("TASK_CONTROL_CONFLICT");
        if (operations.size() >= 4096) throw new Rejected("TASK_CONTROL_CAPACITY");
        if (previous != null) { previous.phase = "stopped"; previous.epoch++; }
        Scope scope = new Scope(task, operation);
        operations.put(key(task, operation), scope); current.put(task, scope);
        return state(scope);
    }

    synchronized State change(String task, String operation, String action, long expectedEpoch, String pauseId) {
        if (!pauseId.isEmpty()) identity(pauseId);
        Scope scope = scope(task, operation);
        if (current.get(task) != scope) throw new Rejected("TASK_CONTROL_CONFLICT");
        boolean samePause = !scope.pauseId.isEmpty() && scope.pauseId.equals(pauseId);
        if (action.equals("pause") && Set.of("pausing", "paused").contains(state(scope).phase()) &&
                (expectedEpoch == scope.epoch || samePause && expectedEpoch == scope.epoch - 1)) return state(scope);
        if (action.equals("stop") && scope.phase.equals("stopped") &&
                (expectedEpoch == scope.epoch || expectedEpoch == scope.epoch - 1)) return state(scope);
        if (expectedEpoch != scope.epoch) throw new Rejected("TASK_CONTROL_CONFLICT");
        if (scope.epoch >= 9_007_199_254_740_991L) throw new Rejected("TASK_CONTROL_CAPACITY");
        switch (action) {
            case "pause" -> {
                if (scope.phase.equals("stopped")) throw new Rejected("TASK_CONTROL_CONFLICT");
                scope.phase = "paused"; scope.epoch++; scope.observed = false;
                if (pauseId.isEmpty()) scope.pauseId = UUID.randomUUID().toString();
                else { identity(pauseId); scope.pauseId = pauseId; }
            }
            case "resume" -> {
                if (!scope.phase.equals("paused") || !samePause || !quiet(scope)) throw new Rejected("TASK_CONTROL_CONFLICT");
                scope.phase = "observing"; scope.epoch++;
            }
            case "activate" -> {
                if (!scope.phase.equals("observing") || !samePause || !quiet(scope)) throw new Rejected("TASK_CONTROL_CONFLICT");
                if (scope.dispatched && !scope.observed) throw new Rejected("TASK_OBSERVATION_REQUIRED");
                scope.phase = "active"; scope.epoch++;
            }
            case "stop" -> { scope.phase = "stopped"; scope.epoch++; scope.observed = false; }
            default -> throw new IllegalArgumentException("Invalid task control operation");
        }
        if (action.equals("pause") || action.equals("stop")) {
            scope.cleanup = new CompletableFuture<>();
            trackResource(task, operation, "resource_cleanup", "owner-cleanup", scope.cleanup);
        }
        return state(scope);
    }
    synchronized CompletableFuture<Void> cleanup(String task, String operation) { return scope(task, operation).cleanup; }

    synchronized State status(String task, String operation) { return state(scope(task, operation)); }
    synchronized boolean registered(String task) { return current.containsKey(task); }
    synchronized boolean quietTask(String task) { Scope scope = current.get(task); return scope == null || quiet(scope); }
    synchronized boolean blockedAndQuiet(String task) {
        Scope scope = current.get(task);
        return scope != null && !scope.phase.equals("active") && quiet(scope);
    }
    synchronized boolean acceptsCancel(String task, String operation, long epoch) {
        Scope scope = operations.get(key(task, operation));
        return scope != null && current.get(task) == scope && scope.epoch == epoch;
    }
    synchronized Call admit(String task, String operation, long epoch, String call, String action) {
        identity(call);
        Scope scope = scope(task, operation);
        Call admitted = new Call(scope, epoch, call, action);
        admitted.checkLocked(); scope.calls.add(admitted);
        return admitted;
    }
    synchronized void trackResource(String task, String operation, String kind, String id, CompletableFuture<?> completion) {
        Scope scope = scope(task, operation);
        Call resource = new Call(scope, scope.epoch, id, "cleanup");
        resource.active = false; scope.calls.add(resource); resource.track(kind, id, completion);
    }
    private Scope scope(String task, String operation) {
        identity(task); identity(operation);
        Scope scope = operations.get(key(task, operation));
        if (scope == null) throw new Rejected("TASK_SCOPE_REQUIRED");
        return scope;
    }
    private static String key(String task, String operation) { return task + '\0' + operation; }
    static long epoch(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) ||
                number.doubleValue() != Math.rint(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > 9_007_199_254_740_991d)
            throw new IllegalArgumentException("Invalid native task epoch");
        return number.longValue();
    }
    static boolean newerBoot(int previous, int current) { return previous >= 0 && current > previous; }
    private static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid native task identity");
    }
    private boolean quiet(Scope scope) { return scope.calls.isEmpty(); }
    private State state(Scope scope) {
        List<Pending> pending = new ArrayList<>();
        for (Call call : scope.calls) {
            if (call.active) pending.add(new Pending("request", call.call, call.call, call.dispatched));
            call.leases.forEach((future, work) -> pending.add(new Pending(work.kind(), work.id(), call.call, call.dispatched)));
        }
        boolean quiet = pending.isEmpty();
        String phase = scope.phase.equals("paused") && !quiet ? "pausing" : scope.phase.equals("stopped") && !quiet ? "stopping" : scope.phase;
        boolean needsObservation = scope.dispatched && !scope.observed && !scope.phase.equals("active");
        boolean unconfirmedProcess = !scope.phase.equals("active") && pending.stream().anyMatch(work -> work.kind().equals("root_process"));
        return new State(scope.task, scope.operation, scope.epoch, scope.pauseId, phase, scope.phase.equals("paused"), quiet,
            List.copyOf(pending), needsObservation || unconfirmedProcess, needsObservation);
    }

    final class Call {
        private final Scope scope;
        private final long epoch;
        final String call, action;
        private boolean active = true, dispatched;
        private final Map<CompletableFuture<?>, Pending> leases = new LinkedHashMap<>();
        Call(Scope scope, long epoch, String call, String action) { this.scope = scope; this.epoch = epoch; this.call = call; this.action = action; }
        private void checkLocked() {
            if (current.get(scope.task) != scope || scope.epoch != epoch || !(scope.phase.equals("active") ||
                    scope.phase.equals("observing") && Set.of("observe", "screenshot").contains(action)))
                throw new Rejected("TASK_SCOPE_PAUSED");
        }
        void check() throws InterruptedException {
            synchronized (NativeTaskControl.this) {
                try { checkLocked(); } catch (Rejected stopped) { throw new InterruptedException(stopped.code); }
            }
        }
        void dispatched() { synchronized (NativeTaskControl.this) { dispatched = true; scope.dispatched = true; } }
        void track(String kind, String id, CompletableFuture<?> completion) {
            synchronized (NativeTaskControl.this) {
                if (kind.equals("root_process")) rootLedger.add(scope.task, scope.operation, id);
                if (!scope.calls.contains(this)) scope.calls.add(this);
                leases.put(completion, new Pending(kind, id, call, dispatched));
            }
            completion.whenComplete((value, error) -> {
                synchronized (NativeTaskControl.this) {
                    if (kind.equals("root_process")) rootLedger.remove(scope.task, scope.operation, id);
                    leases.remove(completion); retire();
                }
            });
        }
        void finish(boolean success) {
            synchronized (NativeTaskControl.this) {
                if (success && scope.epoch == epoch && scope.phase.equals("observing") && Set.of("observe", "screenshot").contains(action)) scope.observed = true;
                active = false; retire();
            }
        }
        private void retire() { if (!active && leases.isEmpty()) scope.calls.remove(this); }
    }
}

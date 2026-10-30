package io.droyagent.app;

import android.content.Context;
import android.os.SystemClock;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.ProcessMemoryTools;
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy;
import io.droyagent.app.nativeui.permissions.NativePendingPermissionRequest;
import io.droyagent.app.nativeui.permissions.NativePermissionOutcome;
import io.droyagent.app.nativeui.permissions.NativePermissionPolicyStore;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Waits before dispatch; persisted identities never authorize a resumed or replayed action. */
final class NativeFirstUseAuthorization {
    private static NativeFirstUseAuthorization instance;
    private record Identity(String task, String call) {}
    private record Handoff(ControlPolicy control, long epoch, long deadline) {}
    record Requirements(Set<String> capabilities, Set<String> systemPermissions, boolean localOnly) {}
    interface Host {
        Requirements requirements(String action, JSONObject args) throws Exception;
        NativeAuthorizationPolicy selected();
        boolean paused();
        boolean allowed(String capability);
        boolean needsSystem(String permission) throws Exception;
        boolean automaticAllowed(String id);
        void requested(String id);
        void outcome(String id, NativePermissionOutcome outcome);
        List<NativePendingPermissionRequest> pending();
        void offer(NativePendingPermissionRequest request);
        void remove(String id);
        boolean retired(String task, String call);
        void retire(String task, String call);
        long elapsed();
        long wallTime();
    }
    private enum Decision { WAITING, APPROVING, GRANTED, DENIED, SKIPPED }
    private static final class Waiting {
        final NativePendingPermissionRequest request;
        final Requirements requirements;
        final ControlPolicy control;
        final ControlPolicy.Ticket ticket;
        Decision decision = Decision.WAITING;
        Waiting(NativePendingPermissionRequest request, Requirements requirements, ControlPolicy control, ControlPolicy.Ticket ticket) {
            this.request = request; this.requirements = requirements; this.control = control; this.ticket = ticket;
        }
    }
    private final Host host;
    private final Map<String, Waiting> waiting = new HashMap<>();
    private final Map<Identity, Handoff> handoffs = new HashMap<>();

    static synchronized NativeFirstUseAuthorization get(Context context) {
        if (instance == null) instance = new NativeFirstUseAuthorization(new AndroidHost(context.getApplicationContext()));
        return instance;
    }

    NativeFirstUseAuthorization(Host host) {
        this.host = host;
        // Only this process's live waiter can own a request. Recovered rows are receipts, never executable work.
        for (NativePendingPermissionRequest request : List.copyOf(host.pending())) {
            if (request.getTaskId() != null && request.getCallId() != null) host.retire(request.getTaskId(), request.getCallId());
            for (String id : requestIds(request)) host.outcome(id, NativePermissionOutcome.SKIPPED);
            host.remove(request.getId());
        }
    }

    JSONObject preflight(String action, JSONObject args, String task, String call, long humanBudget, ControlPolicy control) throws Exception {
        if (humanBudget < 1 || humanBudget > 120000) throw new IllegalArgumentException("Invalid authorization budget");
        if (host.retired(task, call)) throw denied("authorization_expired");
        Requirements requirements = host.requirements(action, args);
        if (requirements.localOnly() || host.selected() != NativeAuthorizationPolicy.ON_FIRST_USE) return new JSONObject();
        ControlPolicy.Ticket ticket = control.begin(task, call, humanBudget);
        Waiting live = null;
        try {
            check(ticket);
            Set<String> capabilities = new LinkedHashSet<>(), permissions = new LinkedHashSet<>();
            for (String key : requirements.capabilities()) if (!host.allowed(key)) capabilities.add(key);
            for (String permission : requirements.systemPermissions()) if (host.needsSystem(permission)) permissions.add(permission);
            if (capabilities.contains("allowRoot") || !PermissionSetupPlan.ordinaryPermissionNames().containsAll(permissions))
                throw denied("independent_authorization_required");
            if (capabilities.isEmpty() && permissions.isEmpty()) return new JSONObject();
            Set<String> ids = new LinkedHashSet<>(permissions);
            for (String key : capabilities) ids.add("capability:" + key);
            for (String id : ids) if (!host.automaticAllowed(id)) throw denied("authorization_previously_requested");
            NativePendingPermissionRequest request = new NativePendingPermissionRequest(UUID.randomUUID().toString(), task, call,
                Set.copyOf(capabilities), Set.copyOf(permissions), host.wallTime());
            live = new Waiting(request, requirements, control, ticket);
            synchronized (this) {
                check(ticket);
                host.retire(task, call);
                waiting.put(request.getId(), live);
                for (String id : ids) host.requested(id);
                host.offer(request);
            }
            synchronized (this) {
                while (live.decision == Decision.WAITING || live.decision == Decision.APPROVING) {
                    check(ticket);
                    wait(Math.max(1, ticket.remainingMillis(100)));
                }
                if (live.decision == Decision.SKIPPED) throw new InterruptedException();
                if (live.decision != Decision.GRANTED) throw denied("authorization_not_granted");
                check(ticket);
                handoffs.put(new Identity(task, call), new Handoff(control, ticket.epoch, ticket.deadline));
            }
            return new JSONObject();
        } catch (ControlPolicy.DeadlineExceeded timeout) {
            if (live != null) settle(live, Decision.SKIPPED);
            throw new ActionException("permission_timeout", "authorization_timeout",
                NativeLocale.text("授权等待已超时，请重新发起。", "Permission request expired. Start a new request."));
        } catch (Exception failure) {
            if (live != null) settle(live, failure instanceof InterruptedException ? Decision.SKIPPED : Decision.DENIED);
            throw failure;
        } finally {
            synchronized (this) { if (live != null) waiting.remove(live.request.getId(), live); }
            control.finish(ticket);
        }
    }

    synchronized NativePendingPermissionRequest approvePending(String id) throws Exception {
        Waiting live = waiting.get(id);
        if (live == null || live.decision != Decision.WAITING) throw denied("authorization_expired");
        check(live.ticket);
        live.decision = Decision.APPROVING;
        return live.request;
    }

    synchronized boolean active(String id) {
        Waiting live = waiting.get(id);
        if (live == null || live.decision != Decision.WAITING && live.decision != Decision.APPROVING) return false;
        try { check(live.ticket); return true; }
        catch (InterruptedException stopped) { return false; }
    }

    synchronized void finishPending(String id) throws Exception {
        Waiting live = waiting.get(id);
        if (live == null || live.decision != Decision.APPROVING) return;
        try { check(live.ticket); }
        catch (InterruptedException stopped) { settle(live, Decision.SKIPPED); throw stopped; }
        boolean granted = true;
        for (String key : live.requirements.capabilities()) granted &= host.allowed(key);
        for (String permission : live.requirements.systemPermissions()) granted &= !host.needsSystem(permission);
        settle(live, granted ? Decision.GRANTED : Decision.DENIED);
    }

    synchronized void declinePending(String id) { Waiting live = waiting.get(id); if (live != null) settle(live, Decision.SKIPPED); }
    synchronized void cancelPending() { for (Waiting live : List.copyOf(waiting.values())) settle(live, Decision.SKIPPED); }

    synchronized void cancel(String task, String call) {
        handoffs.remove(new Identity(task, call));
        for (Waiting live : List.copyOf(waiting.values()))
            if (task.equals(live.request.getTaskId()) && call.equals(live.request.getCallId())) settle(live, Decision.SKIPPED);
    }

    synchronized void end(String task) {
        handoffs.keySet().removeIf(identity -> identity.task().equals(task));
        for (Waiting live : List.copyOf(waiting.values())) if (task.equals(live.request.getTaskId())) settle(live, Decision.SKIPPED);
    }

    synchronized void checkDispatch(String task, String call, ControlPolicy.Ticket ticket, ControlPolicy control) throws Exception {
        Handoff handoff = handoffs.remove(new Identity(task, call));
        if (handoff == null) {
            if (host.retired(task, call)) throw denied("authorization_expired");
            return;
        }
        if (host.paused() || handoff.control() != control || handoff.epoch() != ticket.epoch || host.elapsed() >= handoff.deadline()) throw new InterruptedException();
        ticket.check();
    }

    private void check(ControlPolicy.Ticket ticket) throws InterruptedException {
        ticket.check();
        if (host.paused()) throw new InterruptedException();
    }

    private synchronized void settle(Waiting live, Decision decision) {
        if (live.decision == Decision.GRANTED || live.decision == Decision.DENIED || live.decision == Decision.SKIPPED) return;
        if (decision != Decision.GRANTED) live.control.cancel(live.request.getTaskId(), live.request.getCallId());
        try {
            // Final grant receipts use live facts; skipped requests stay skipped if settings change later.
            for (String key : live.request.getCapabilityKeys()) host.outcome("capability:" + key,
                decision == Decision.SKIPPED ? NativePermissionOutcome.SKIPPED : host.allowed(key) ? NativePermissionOutcome.GRANTED : NativePermissionOutcome.NOT_GRANTED);
            for (String permission : live.request.getPermissionIds()) {
                boolean granted = decision != Decision.SKIPPED && !host.needsSystem(permission);
                host.outcome(permission, decision == Decision.SKIPPED ? NativePermissionOutcome.SKIPPED
                    : granted ? NativePermissionOutcome.GRANTED : NativePermissionOutcome.NOT_GRANTED);
            }
            host.remove(live.request.getId());
            live.decision = decision;
        } catch (Exception failure) {
            live.decision = Decision.SKIPPED;
            live.control.cancel(live.request.getTaskId(), live.request.getCallId());
            throw new IllegalStateException("PERMISSION_STATE_NOT_SAVED", failure);
        } finally { notifyAll(); }
    }

    private static Set<String> requestIds(NativePendingPermissionRequest request) {
        Set<String> ids = new LinkedHashSet<>(request.getPermissionIds());
        for (String key : request.getCapabilityKeys()) ids.add("capability:" + key);
        return ids;
    }

    private static ActionException denied(String reason) {
        return new ActionException("PERMISSION_DENIED", reason,
            NativeLocale.text("请在权限设置中授权后重新发起。", "Allow access in Permissions, then start a new request."));
    }

    static boolean initialFirstUseMayResume(boolean initialSetup, boolean eligible, boolean required, boolean legacy,
                                             boolean firstSelection, boolean allCapabilitiesOff, boolean explicitPause) {
        return initialSetup && eligible && required && !legacy && firstSelection && allCapabilitiesOff && !explicitPause;
    }

    private static final class AndroidHost implements Host {
        private final Context context;
        private final CapabilityCatalog catalog;
        private final NativePermissionPolicyStore store;
        AndroidHost(Context context) {
            this.context = context; SetupManager.initialize(context);
            catalog = new CapabilityCatalog(context);
            store = new NativePermissionPolicyStore(ProductPermissions.preferences(context));
        }
        public Requirements requirements(String action, JSONObject args) throws Exception {
            JSONObject tool = CapabilityAuthorization.forArguments(catalog.tool(action), args);
            if (action.equals("capabilities") || action.equals("audio_status") || action.equals("audio_stop")
                    || HardwareRuntime.cleanup(action, args) || ProcessMemoryTools.isLocal(action, args))
                return new Requirements(Set.of(), Set.of(), true);
            if (action.equals("sequence")) new ActionSequence(args);
            Set<String> keys = new LinkedHashSet<>();
            JSONArray groups = tool.getJSONArray("permissions");
            for (int index = 0; index < groups.length(); index++) keys.add(catalog.group(groups.getString(index)).getString("preference"));
            if (action.equals("audio_start")) {
                String source = args.optString("source", "microphone");
                if (!Set.of("microphone", "playback").contains(source)) throw new IllegalArgumentException("Invalid audio source");
                keys.add(source.equals("microphone") ? "allowAudioMicrophone" : "allowAudioPlayback");
            }
            Set<String> systems = new LinkedHashSet<>(PermissionSetupPlan.ordinaryForCapabilities(keys, PermissionSetup.supportedHardware(context), false));
            // Playback capture's projection consent stays in its existing dedicated UI; recording permission is shared.
            if (action.equals("audio_start")) systems.add("microphone");
            return new Requirements(Set.copyOf(keys), Set.copyOf(systems), false);
        }
        public NativeAuthorizationPolicy selected() { return store.selected(); }
        public boolean paused() { return ProductPermissions.stopped(context); }
        public boolean allowed(String key) { return ProductPermissions.allowed(ProductPermissions.preferences(context), key); }
        public boolean needsSystem(String id) throws Exception { return PermissionSetup.needsSystemPermission(context, id); }
        public boolean automaticAllowed(String id) { return store.automaticRequestAllowed(id); }
        public void requested(String id) { store.recordRequested(id, wallTime()); }
        public void outcome(String id, NativePermissionOutcome outcome) { store.recordOutcome(id, outcome, wallTime()); }
        public List<NativePendingPermissionRequest> pending() { return store.pendingRequests(); }
        public void offer(NativePendingPermissionRequest request) { store.offerPending(request); }
        public void remove(String id) { store.removePending(id); }
        public boolean retired(String task, String call) { return store.isRetiredCall(task, call); }
        public void retire(String task, String call) { store.retireCall(task, call); }
        public long elapsed() { return SystemClock.elapsedRealtime(); }
        public long wallTime() { return System.currentTimeMillis(); }
    }
}

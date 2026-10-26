package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Bounds an announced verification attempt; the model still judges the visible result. */
final class VerificationFlow {
    private static final int MAX_PENDING = 64;
    private static final Set<String> GRAPHICAL = Set.of("slider", "puzzle", "text", "ordered_points");
    private static final Set<String> KINDS = Set.of("slider", "puzzle", "text", "ordered_points",
            "otp", "password", "identity", "biometric", "blocked", "unknown");
    private final LongSupplier clock;
    private final Map<String, Flow> pending = new HashMap<>();
    private Evidence latest;
    private long sequence;

    VerificationFlow(LongSupplier clock) { this.clock = clock; }

    static boolean affectsScreen(String action, boolean declaredScreen, boolean readOnly) {
        return !action.equals("verification") && !readOnly
                && (declaredScreen || action.equals("open_app") || action.startsWith("browser_"));
    }

    synchronized String returnTarget(String task) throws Exception {
        Flow flow = pending.get(task);
        if (flow == null || !flow.state.equals("manual_required") || flow.handoffSequence < 0)
            throw invalid(io.droyagent.app.NativeLocale.text("没有等待人工操作的验证", "No verification is waiting for manual action"));
        latest = null;
        return flow.packageName;
    }

    synchronized JSONObject request(String task, JSONObject args) throws Exception {
        String operation = requiredString(args, "operation");
        if (args.has("channel") && !"accessibility".equals(args.optString("channel")))
            throw invalid(io.droyagent.app.NativeLocale.text("验证流程不支持 Root 通道", "The verification flow does not support the Root channel"));
        Flow flow = pending.get(task);
        switch (operation) {
            case "assess": {
                if (flow != null) return summary(flow);
                String kind = requiredString(args, "kind");
                if (!KINDS.contains(kind)) throw invalid(io.droyagent.app.NativeLocale.text("未知的验证类型", "Unknown verification type"));
                boolean solvable = bool(args, "solvable");
                boolean imageSupported = bool(args, "imageSupported");
                Evidence evidence = current(task, args);
                if (pending.size() >= MAX_PENDING)
                    throw new ActionException("verification_capacity", io.droyagent.app.NativeLocale.text("待处理验证过多，请完成已有验证或重启引擎", "Too many pending verifications. Complete existing ones or restart the engine"));
                boolean automatic = GRAPHICAL.contains(kind) && solvable && imageSupported && evidence.screenshot;
                flow = new Flow(kind, evidence.packageName, sequence,
                        automatic ? "automatic_allowed" : "manual_required");
                pending.put(task, flow);
                return summary(flow);
            }
            case "handoff": {
                if (flow == null) throw invalid(io.droyagent.app.NativeLocale.text("请先观察原应用并评估验证类型", "Please observe the original app and assess the verification type first"));
                flow.state = "manual_required";
                flow.handoffSequence = ++sequence;
                return summary(flow);
            }
            case "resume": {
                if (flow == null || !flow.state.equals("manual_required") || flow.handoffSequence < 0)
                    throw invalid(io.droyagent.app.NativeLocale.text("没有等待人工操作的验证", "No verification is waiting for manual action"));
                Evidence evidence = currentTarget(task, args, flow);
                if (evidence.sequence <= flow.handoffSequence) throw stale(io.droyagent.app.NativeLocale.text("请在人工操作后重新观察原应用", "Please observe the original app again after manual action"));
                flow.state = "awaiting_review";
                flow.reviewSequence = evidence.sequence;
                return summary(flow);
            }
            case "complete": {
                if (flow == null) throw invalid(io.droyagent.app.NativeLocale.text("没有待核实的验证", "No verification is awaiting review"));
                boolean passed = bool(args, "passed");
                if (!passed) {
                    flow.state = "manual_required";
                    flow.handoffSequence = -1;
                    return summary(flow);
                }
                if (flow.state.equals("manual_required"))
                    throw manual(io.droyagent.app.NativeLocale.text("需要先由用户完成验证，不能直接标记通过", "The user must complete verification before it can be marked as passed"));
                Evidence evidence = currentTarget(task, args, flow);
                long baseline = flow.state.equals("awaiting_review") ? flow.reviewSequence - 1 : flow.lastActionSequence;
                if (evidence.sequence <= baseline) throw stale(io.droyagent.app.NativeLocale.text("请在验证操作后重新观察并核实结果", "Please observe again and verify the result after the verification action"));
                flow.state = "verified";
                JSONObject result = summary(flow);
                pending.remove(task);
                return result;
            }
            default: throw invalid(io.droyagent.app.NativeLocale.text("未知的验证流程操作", "Unknown verification flow operation"));
        }
    }

    synchronized void beforeAction(String task, String action, JSONObject args, boolean screenMutation) throws Exception {
        if (!screenMutation) return;
        Flow flow = pending.get(task);
        if (flow == null) {
            // All screen actions consume a prior visual basis even outside a verification flow.
            latest = null;
            return;
        }
        if (flow.state.equals("awaiting_review"))
            throw new ActionException("verification_review_required", io.droyagent.app.NativeLocale.text("请先核实人工操作后的页面，再继续任务", "Please verify the page after manual action before continuing the task"));
        if (!flow.state.equals("automatic_allowed")) throw manual(io.droyagent.app.NativeLocale.text("当前验证需要用户操作，请发起人工接管", "This verification needs user action. Please request a manual handoff"));
        boolean allowed = !"root".equals(args.optString("channel")) && switch (action) {
            case "swipe" -> (flow.kind.equals("slider") || flow.kind.equals("puzzle")) && flow.swipes < 1
                    && "precise".equals(args.optString("motion", "precise"));
            case "input_text" -> flow.kind.equals("text") && flow.inputs < 1;
            case "tap" -> flow.taps < (flow.kind.equals("ordered_points") ? 8 : flow.kind.equals("text") ? 2 : 1)
                    && (!(flow.kind.equals("slider") || flow.kind.equals("puzzle")) || flow.swipes == 0);
            default -> false;
        };
        if (!allowed) {
            flow.state = "manual_required";
            throw manual(io.droyagent.app.NativeLocale.text("已达到本次验证的操作上限或操作不适用；请核实结果并交由用户处理", "The operation limit has been reached, or this action is unsuitable. Verify the result and ask the user to continue"));
        }
        currentTarget(task, args, flow);
        if (action.equals("swipe")) flow.swipes++;
        else if (action.equals("input_text")) flow.inputs++;
        else flow.taps++;
        flow.lastActionSequence = ++sequence;
        latest = null;
    }

    synchronized void failed(String task, boolean screenMutation) {
        if (latest != null && latest.task.equals(task)) latest = null;
        Flow flow = pending.get(task);
        if (flow != null && screenMutation) {
            flow.state = "manual_required";
            flow.handoffSequence = -1;
            latest = null;
        }
    }

    synchronized void returned(String task, String action, JSONObject result) throws Exception {
        JSONObject observed = result.optJSONObject("afterObservation");
        if (observed == null && action.equals("wait_for")) observed = result.optJSONObject("observation");
        if (observed == null && (action.equals("observe") || action.equals("screenshot"))) observed = result;
        if (observed == null && action.equals("wait_for")) latest = null;
        if (observed != null) {
            Object id = observed.opt("snapshotId"), pkg = observed.opt("packageName");
            if (id instanceof String && !((String) id).isBlank() && pkg instanceof String && !((String) pkg).isBlank()) {
                long age = Math.min(15000, Math.max(0, observed.optLong("snapshotMaxAgeMs", 15000)));
                latest = new Evidence(task, (String) id, (String) pkg, action.equals("screenshot"),
                        ++sequence, clock.getAsLong(), age);
            } else latest = null;
        }
        Flow flow = pending.get(task);
        if (flow != null) result.put("verification", summary(flow));
    }

    synchronized void end(String task) {
        // Ending the run releases its gate so a later unrelated user request is not trapped.
        pending.remove(task);
        if (latest != null && latest.task.equals(task)) latest = null;
    }

    private Evidence currentTarget(String task, JSONObject args, Flow flow) throws Exception {
        Evidence evidence = current(task, args);
        if (!flow.packageName.equals(evidence.packageName))
            throw new ActionException("verification_target_changed", io.droyagent.app.NativeLocale.text("请回到原验证应用并停留在该页面，再发起 android_verification handoff；Droy 问答页面不能证明验证通过", "Return to the original verification app and stay on that page before requesting android_verification handoff. The Droy question page does not prove verification passed"));
        return evidence;
    }

    private Evidence current(String task, JSONObject args) throws Exception {
        Object id = args.opt("snapshotId");
        if (!(id instanceof String) || latest == null || !latest.task.equals(task) || !latest.id.equals(id))
            throw stale(io.droyagent.app.NativeLocale.text("验证需要当前任务最新观察或截图的 snapshotId", "Verification requires the current task's latest observation or screenshot snapshotId"));
        long now = clock.getAsLong();
        if (now < latest.createdMs || now - latest.createdMs > latest.maxAgeMs) throw stale(io.droyagent.app.NativeLocale.text("验证观察已过期，请重新观察", "The verification observation expired. Please observe again"));
        return latest;
    }

    private static JSONObject summary(Flow flow) throws Exception {
        boolean automatic = flow.state.equals("automatic_allowed");
        return new JSONObject().put("state", flow.state).put("kind", flow.kind)
                .put("targetPackage", flow.packageName).put("goalVerified", false)
                .put("remainingActions", new JSONObject()
                        .put("tap", automatic ? Math.max(0, (flow.kind.equals("ordered_points") ? 8 : flow.kind.equals("text") ? 2 : 1) - flow.taps) : 0)
                        .put("swipe", automatic ? Math.max(0, (flow.kind.equals("slider") || flow.kind.equals("puzzle") ? 1 : 0) - flow.swipes) : 0)
                        .put("input_text", automatic ? Math.max(0, (flow.kind.equals("text") ? 1 : 0) - flow.inputs) : 0))
                .put("meaning", flow.state.equals("verified")
                        ? io.droyagent.app.NativeLocale.text("调用方已核实最新页面；这不是服务端验证凭据或整个任务完成证明", "The caller verified the latest page. This is not server verification or proof that the whole task is complete")
                        : io.droyagent.app.NativeLocale.text("只对已声明的验证限制自动操作；需要检查最新页面，失败或不确定时交由用户处理", "Automatic actions are limited to the declared verification. Check the latest page and hand off to the user when unsuccessful or uncertain"));
    }

    private static String requiredString(JSONObject args, String key) throws Exception {
        Object value = args.opt(key);
        if (!(value instanceof String) || ((String) value).isBlank()) throw invalid(key + io.droyagent.app.NativeLocale.text(" 必须是非空字符串", " must be a non-empty string"));
        return (String) value;
    }
    private static boolean bool(JSONObject args, String key) throws Exception {
        Object value = args.opt(key);
        if (!(value instanceof Boolean)) throw invalid(key + io.droyagent.app.NativeLocale.text(" 必须是布尔值", " must be a boolean"));
        return (Boolean) value;
    }
    private static ActionException invalid(String message) { return new ActionException("invalid_argument", message); }
    private static ActionException stale(String message) { return new ActionException("stale_snapshot", message); }
    private static ActionException manual(String message) { return new ActionException("verification_requires_user", message); }

    private record Evidence(String task, String id, String packageName, boolean screenshot,
                            long sequence, long createdMs, long maxAgeMs) {}
    private static final class Flow {
        final String kind, packageName;
        String state;
        int taps, swipes, inputs;
        long lastActionSequence, handoffSequence = -1, reviewSequence;
        Flow(String kind, String packageName, long sequence, String state) {
            this.kind = kind; this.packageName = packageName; this.lastActionSequence = sequence; this.state = state;
        }
    }
}

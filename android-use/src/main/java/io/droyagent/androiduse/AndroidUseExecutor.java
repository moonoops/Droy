package io.droyagent.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product authorization and serial task ownership must wrap every call to execute. */
public final class AndroidUseExecutor {
    private static final int MAX_NODES = 200;
    private static final int MAX_VISITED = 800;
    private static final int MAX_TEXT_CHARS = 24000;
    private static final int MAX_PNG_BYTES = 16 * 1024 * 1024;
    private static final long UI_TIMEOUT_MS = 5000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeviceInfoTools deviceInfo;
    private final AppTools apps;
    private final ShizukuAccess shizuku;
    private Snapshot snapshot;
    private volatile String rootState = "not_verified";

    public AndroidUseExecutor(Context context) {
        this.context = context.getApplicationContext();
        this.deviceInfo = new DeviceInfoTools(this.context);
        this.apps = new AppTools(this.context);
        this.shizuku = ShizukuAccess.get(this.context);
    }

    public JSONObject capabilities(boolean rootEnabled) {
        try {
            boolean accessibility = DroyAccessibilityService.current(context) != null;
            JSONArray actions = new JSONArray();
            if (accessibility) {
                for (String action : new String[]{"observe", "screenshot", "tap", "swipe", "gesture", "input_text", "node_action", "global_action", "back", "wait_for"}) {
                    actions.put(action);
                }
            }
            JSONArray available = new JSONArray(new String[]{"system_info", "find_apps", "open_app", "open_settings"});
            for (int index = 0; index < actions.length(); index++) available.put(actions.get(index));
            boolean shell = rootEnabled && rootCommandAvailable() || shizuku.available() && (shizuku.uid() != 0 || rootEnabled);
            if (!accessibility && shell) for (String action : new String[]{"screenshot", "tap", "swipe", "back"}) available.put(action);
            return new JSONObject()
                    .put("accessibilityConnected", accessibility)
                    .put("accessibilityRecovery", AccessibilityRecovery.get(context).snapshot())
                    .put("accessibilityActions", actions)
                    .put("availableActions", available)
                    .put("nativeActions", new JSONArray(new String[]{"system_info", "find_apps", "open_app", "open_settings"}))
                    .put("globalActions", globalActions(DroyAccessibilityService.current(context)))
                    .put("settingsPages", settingsPages())
                    .put("nodeActionOperations", new JSONArray(NodeActions.OPERATIONS))
                    .put("automaticChannelSelection", true)
                    .put("channelPreferenceMeaning", "A channel is only preferred when authorized and compatible; modifying actions with unknown outcomes are not replayed")
                    .put("shizuku", shizuku.status())
                    .put("rootEnabled", rootEnabled)
                    .put("rootAuthorization", rootEnabled ? rootState : "disabled")
                    .put("rootActions", new JSONArray(new String[]{"screenshot", "tap", "swipe", "back"}))
                    .put("gestureSupport", new JSONObject().put("channel", "accessibility").put("swipeMotion", new JSONArray(new String[]{"precise", "natural"}))
                            .put("maxPointers", GesturePlan.MAX_POINTERS).put("maxPointsPerPointer", GesturePlan.MAX_POINTS)
                            .put("synchronizedPointers", true).put("durationMinMs", 50).put("durationMaxMs", 2000))
                    .put("rootAuthorizationCheckedOnEachAction", true)
                    .put("nodeActionsRequireSnapshot", true)
                    .put("coordinateActionsRequireSnapshot", true)
                    .put("snapshotMaxAgeMs", SnapshotGuard.MAX_AGE_MS)
                    .put("coordinateSnapshotMaxAgeMs", SnapshotGuard.MAX_AGE_MS).put("nodeBasisPolicy", "refresh_original_node")
                    .put("resultMeaning", "Action receipt is not proof of task completion; observe and verify the result");
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public JSONObject execute(String action, JSONObject args, boolean rootEnabled,
                              Cancellation cancellation) throws Exception {
        if (cancellation == null) throw new IllegalArgumentException("cancellation is required");
        cancellation.check();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new ActionException("background_thread_required", "Android actions must be called on a background thread");
        }
        if (action == null || args == null) throw new ActionException("invalid_argument", "action and args are required");
        if (args.has("afterObserve") && !(args.get("afterObserve") instanceof Boolean)) {
            throw new ActionException("invalid_argument", "afterObserve must be a boolean");
        }
        if (args.has("holdMs")) {
            if (!action.equals("tap") || args.has("nodeId") || args.optBoolean("navigation"))
                throw new ActionException("invalid_argument", "holdMs is supported only for coordinate tap without navigation");
            ActionValidation.holdDuration(args.get("holdMs"));
        }
        if (args.has("afterCondition")) {
            if (!Set.of("tap", "swipe", "gesture", "input_text", "node_action", "global_action", "back").contains(action))
                throw new ActionException("invalid_argument", "afterCondition is supported only for screen actions");
            validateCondition(args.optJSONObject("afterCondition"));
            if (!args.optBoolean("afterObserve", true))
                throw new ActionException("invalid_argument", "afterCondition requires afterObserve");
        }
        if (args.has("afterTimeoutMs")) {
            int timeout = ActionValidation.integer(args.get("afterTimeoutMs"), "afterTimeoutMs");
            if (!args.has("afterCondition") || timeout < 0 || timeout > 60000)
                throw new ActionException("invalid_argument", "afterTimeoutMs requires afterCondition and must be between 0 and 60000");
        }
        if (args.has("navigation") && (!action.equals("tap") || !(args.get("navigation") instanceof Boolean)))
            throw new ActionException("invalid_argument", "navigation is a boolean for tap only");
        if (action.equals("system_info")) return deviceInfo.query(args, cancellation);
        if (action.equals("find_apps")) return apps.find(args, cancellation);
        if (action.equals("open_app")) return openApp(args, cancellation);
        if (action.equals("open_settings")) return openSettings(args, cancellation);
        Object selected = args.has("channel") ? args.get("channel") : "auto";
        if (!(selected instanceof String)) throw new ActionException("invalid_argument", "channel must be a string");
        if (args.has("holdMs") && !selected.equals("auto") && !selected.equals("accessibility"))
            throw new ActionException("invalid_argument", "Coordinate hold supports only the accessibility channel");
        if (args.has("motion") && (!action.equals("swipe") || !(args.get("motion") instanceof String)
                || (!args.getString("motion").equals("precise") && !args.getString("motion").equals("natural"))))
            throw new ActionException("invalid_argument", "motion must be precise or natural and applies only to swipe");
        if ((action.equals("swipe") || action.equals("gesture")) && args.has("nodeId"))
            throw new ActionException("invalid_argument", "Swipe and multi-pointer gestures require coordinates, not nodeId");
        List<String> channels = ActionChannels.candidates(action, args.has("nodeId"), args.optString("motion").equals("natural"),
                (String) selected, DroyAccessibilityService.current(context) != null,
                shizuku.available() && (shizuku.uid() != 0 || rootEnabled), rootEnabled && rootCommandAvailable());
        if (channels.isEmpty()) throw new ActionException("accessibility_unavailable",
                "No authorized backend supports this action; use another available observation or operation, or enable accessibility");
        if (args.has("holdMs")) {
            if (DroyAccessibilityService.current(context) == null)
                throw new ActionException("accessibility_unavailable", "Coordinate hold requires connected accessibility");
            channels = List.of("accessibility");
        }
        if (args.optBoolean("navigation")) {
            if (DroyAccessibilityService.current(context) == null)
                throw new ActionException("accessibility_unavailable", "Navigation relocation requires a current accessibility target");
            channels = List.of("accessibility");
        }
        for (int index = 0; index < channels.size(); index++) {
            String channel = channels.get(index);
            cancellation.usingRoot(channel.equals("root") || channel.equals("shizuku") && shizuku.uid() == 0);
            Attempt attempt = new Attempt(cancellation);
            try {
                GesturePlan plan = channel.equals("accessibility") && (action.equals("swipe") || action.equals("gesture"))
                        ? onMain(attempt, () -> planGesture(action, args)) : null;
                return withOverlayClearance(action, args, channel, plan, attempt,
                        () -> executeChannel(action, args, rootEnabled, channel, plan, attempt));
            } catch (ActionException failure) {
                if (!attempt.dispatched && failure.getCode().equals("stale_snapshot")) {
                    JSONObject details = failure.getDetails() == null ? new JSONObject() : failure.getDetails();
                    try {
                        details.put("currentObservation", onMain(attempt, () -> observe(attempt)));
                        failure.withDetails(details);
                    } catch (ActionException observationFailure) {
                        failure.withDetails(details.put("observationFailure", observationFailure.getCode()));
                    }
                }
                if (index + 1 == channels.size() || !ActionChannels.mayFallback(failure.getCode(), attempt.dispatched)) throw failure;
                cancellation.check();
            }
        }
        throw new IllegalStateException("No channel attempted");
    }

    /** Read the already observed basis for a bounded sequence without replacing its references. */
    public JSONObject sequenceBasis(String snapshotId, Cancellation cancellation) throws Exception {
        return onMain(cancellation, () -> {
            Snapshot saved = snapshot;
            SnapshotGuard.checkNode(snapshotId, saved == null ? null : saved.id,
                    saved == null ? -1 : saved.hardGeneration, DroyAccessibilityService.hardGeneration(),
                    saved == null ? -1 : saved.createdMs, SystemClock.elapsedRealtime());
            if (saved.observation == null) throw stale("snapshot_missing", "A sequence requires an observed node basis");
            JSONObject basis = new JSONObject(saved.observation.toString());
            JSONArray nodes = basis.getJSONArray("nodes");
            for (int index = 0; index < nodes.length(); index++) {
                JSONObject item = nodes.getJSONObject(index);
                SavedNode entry = saved.nodes.get(item.getString("nodeId"));
                item.put("sequenceIdentity", saved.windowId + ":" + entry.node.hashCode());
                if (entry.context != null)
                    item.put("sequenceContextIdentity", saved.windowId + ":" + entry.context.node.hashCode());
            }
            return basis;
        });
    }

    private static boolean rootCommandAvailable() {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(":")) if (!directory.isEmpty() && new java.io.File(directory, "su").canExecute()) return true;
        return false;
    }

    private static final class Attempt implements Cancellation {
        final Cancellation parent;
        boolean dispatched;
        Attempt(Cancellation parent) { this.parent = parent; }
        @Override public void check() throws InterruptedException { parent.check(); }
        @Override public void usingRoot(boolean root) throws InterruptedException { parent.usingRoot(root); }
        @Override public long remainingMillis(long maximum) throws Exception { return parent.remainingMillis(maximum); }
        @Override public void dispatched() throws InterruptedException { parent.dispatched(); dispatched = true; }
        @Override public void track(String kind, String id, CompletableFuture<?> settled) { parent.track(kind, id, settled); }
    }

    private JSONObject executeChannel(String action, JSONObject args, boolean rootEnabled, String channel, GesturePlan plan,
                                      Cancellation cancellation) throws Exception {
        if (!channel.equals("accessibility"))
            return afterObserve(executeShell(action, args, rootEnabled, channel, cancellation), action, args, cancellation);
        switch (action) {
            case "observe": return onMain(cancellation, () -> observe(cancellation));
            case "screenshot": return screenshot(cancellation);
            case "wait_for": return waitFor(args, cancellation);
            case "tap":
                if (args.optBoolean("navigation")) return afterObserve(navigationTap(args, cancellation), action, args, cancellation);
                return afterObserve(args.has("nodeId") ? onMain(cancellation, () -> nodeAction(args, false, cancellation))
                        : gesture(args, false, cancellation), action, args, cancellation);
            case "swipe":
            case "gesture": return afterObserve(plannedGesture(action, args, plan, cancellation), action, args, cancellation);
            case "input_text": return afterObserve(onMain(cancellation, () -> nodeAction(args, true, cancellation)), action, args, cancellation);
            case "node_action": return afterObserve(onMain(cancellation,
                    () -> nodeAction(args, NodeActions.operation(args.opt("operation")), cancellation)), action, args, cancellation);
            case "global_action": return afterObserve(onMain(cancellation, () -> globalAction(args, cancellation)), action, args, cancellation);
            case "back": return afterObserve(onMain(cancellation, () -> {
                DroyAccessibilityService service = requireService(cancellation);
                cancellation.dispatched();
                boolean accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                invalidateSnapshot();
                if (!accepted) throw new ActionException("action_rejected", "Android rejected the back action");
                return receipt(action, channel);
            }), action, args, cancellation);
            default: throw new ActionException("unsupported_action", "Unsupported Android action: " + action);
        }
    }

    private JSONObject withOverlayClearance(String action, JSONObject args, String channel, GesturePlan plan,
                                            Cancellation cancellation, Callable<JSONObject> operation) throws Exception {
        boolean capture = action.equals("screenshot");
        if (!capture && !action.equals("tap") && !action.equals("swipe") && !action.equals("gesture")
                && !action.equals("input_text") && !action.equals("node_action"))
            return operation.call();
        Rect area = capture ? null : onMain(cancellation, () -> {
            if (!channel.equals("accessibility") && args.has("nodeId"))
                throw new ActionException("invalid_argument", "Root input accepts explicit coordinates only");
            if (args.has("nodeId") || action.equals("input_text")) {
                Snapshot saved = snapshot;
                String expected = args.opt("snapshotId") instanceof String ? args.getString("snapshotId") : null;
                boolean navigation = action.equals("tap") && args.optBoolean("navigation");
                SnapshotGuard.checkNode(expected, saved == null ? null : saved.id,
                        navigation ? 0 : saved == null ? -1 : saved.hardGeneration,
                        navigation ? 0 : DroyAccessibilityService.hardGeneration(),
                        saved == null ? -1 : saved.createdMs, SystemClock.elapsedRealtime());
                SavedNode node = saved.nodes.get(args.optString("nodeId"));
                if (node == null) throw new ActionException("invalid_node", "nodeId is not present in this snapshot");
                Rect bounds = new Rect(node.bounds);
                if (navigation && node.node.refresh()) node.node.getBoundsInScreen(bounds);
                return bounds;
            }
            Rect screen = displayBounds();
            if (plan != null) {
                Rect plannedBounds = planBounds(plan);
                checkCoordinateSnapshot(args, screen, true, plannedBounds, cancellation);
                return plannedBounds;
            }
            if (action.equals("tap") && args.optBoolean("navigation")) {
                Snapshot saved = snapshot;
                SnapshotGuard.check(args.optString("snapshotId", null), saved == null ? null : saved.id, 0, 0,
                        saved == null ? -1 : saved.createdMs, SystemClock.elapsedRealtime());
            } else checkCoordinateSnapshot(args, screen, action.equals("swipe"), cancellation);
            boolean swipe = action.equals("swipe");
            int x = coordinate(args, swipe ? "startX" : "x", screen.width());
            int y = coordinate(args, swipe ? "startY" : "y", screen.height());
            int endX = swipe ? coordinate(args, "endX", screen.width()) : x;
            int endY = swipe ? coordinate(args, "endY", screen.height()) : y;
            return new Rect(Math.min(x, endX), Math.min(y, endY), Math.max(x, endX) + 1, Math.max(y, endY) + 1);
        });
        OverlayInteraction.Preparation[] clearance = new OverlayInteraction.Preparation[1];
        try {
            onMain(cancellation, () -> {
                clearance[0] = OverlayInteraction.prepare(area, capture);
                return null;
            });
            // The product completes this after the window update reaches a frame;
            // the original action then revalidates its target before dispatch.
            await(clearance[0].ready, UI_TIMEOUT_MS, cancellation);
            return operation.call();
        } finally {
            // Posting the lookup also covers cancellation while prepare is running
            // on the main thread, before onMain can return its result.
            main.post(() -> { if (clearance[0] != null) clearance[0].finish(); });
        }
    }

    private DroyAccessibilityService requireService(Cancellation cancellation) throws Exception {
        cancellation.check();
        DroyAccessibilityService service = DroyAccessibilityService.current(context);
        if (service == null) throw new ActionException("accessibility_unavailable", AccessibilityRecovery.get(context).enabled()
            ? "DroyAgent accessibility is enabled but the service is not connected"
            : "DroyAgent accessibility is not enabled; check its permission status in DroyAgent");
        return service;
    }

    private JSONArray globalActions(DroyAccessibilityService service) throws org.json.JSONException {
        JSONArray available = new JSONArray();
        if (service == null) return available;
        List<AccessibilityNodeInfo.AccessibilityAction> supported = service.getSystemActions();
        for (String operation : SystemNavigation.GLOBAL_OPERATIONS) {
            try {
                int id = SystemNavigation.globalId(operation);
                for (AccessibilityNodeInfo.AccessibilityAction action : supported)
                    if (action.getId() == id) { available.put(operation); break; }
            } catch (ActionException impossible) { throw new IllegalStateException(impossible); }
        }
        return available;
    }

    private JSONArray settingsPages() throws org.json.JSONException {
        JSONArray pages = new JSONArray();
        for (String page : SystemNavigation.SETTINGS_PAGES) {
            try {
                if (SystemNavigation.settingsIntent(context, page, context.getPackageName()) != null) pages.put(page);
            } catch (ActionException impossible) { throw new IllegalStateException(impossible); }
        }
        return pages;
    }

    private JSONObject globalAction(JSONObject args, Cancellation cancellation) throws Exception {
        int id = SystemNavigation.globalId(args.opt("operation"));
        DroyAccessibilityService service = requireService(cancellation);
        boolean supported = false;
        for (AccessibilityNodeInfo.AccessibilityAction action : service.getSystemActions())
            if (action.getId() == id) { supported = true; break; }
        if (!supported) throw new ActionException("system_action_unavailable", "Android does not currently expose this system action");
        invalidateSnapshot();
        cancellation.dispatched();
        if (!service.performGlobalAction(id)) throw new ActionException("action_rejected", "Android rejected the system action");
        return receipt("global_action", "accessibility").put("operation", args.getString("operation"));
    }

    private JSONObject openSettings(JSONObject args, Cancellation cancellation) throws Exception {
        String page = SystemNavigation.page(args.opt("page"));
        String packageName = page.equals("app_details") ? DiagnosticsValidation.packageName(args.opt("packageName")) : null;
        if (!page.equals("app_details") && args.has("packageName"))
            throw new ActionException("invalid_argument", "packageName applies only to app_details");
        JSONObject result = onMain(cancellation, () -> {
            if (packageName != null) {
                try { context.getPackageManager().getApplicationInfo(packageName, 0); }
                catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                    throw new ActionException("app_not_found", "Android did not expose the requested package");
                }
            }
            Intent intent = SystemNavigation.settingsIntent(context, page, packageName);
            if (intent == null) throw new ActionException("settings_unavailable", "Android has no system handler for this settings page");
            cancellation.check();
            invalidateSnapshot();
            cancellation.dispatched();
            context.startActivity(intent);
            JSONObject opened = new JSONObject().put("status", "requested").put("action", "open_settings")
                    .put("page", page).put("settingsAction", intent.getAction()).put("channel", "android_system")
                    .put("handlerPackage", intent.getComponent().getPackageName()).put("dispatched", true)
                    .put("goalVerified", false).put("verificationRequired", "Inspect the resulting page; requesting Settings does not prove the destination loaded");
            if (packageName != null) opened.put("packageName", packageName);
            return opened;
        });
        return afterObserve(result, "open_settings", args, cancellation);
    }

    private JSONObject openApp(JSONObject args, Cancellation cancellation) throws Exception {
        if (args.has("packageName") == args.has("appName")) {
            throw new ActionException("invalid_argument", "Provide exactly one of packageName or appName");
        }
        int verifyMs = args.has("verifyTimeoutMs") ? ActionValidation.integer(args.get("verifyTimeoutMs"), "verifyTimeoutMs") : 2000;
        if (verifyMs < 0 || verifyMs > 5000) throw new ActionException("invalid_argument", "verifyTimeoutMs must be between 0 and 5000");
        String packageName;
        if (args.has("packageName")) packageName = ActionValidation.packageName(args.get("packageName"));
        else {
            String name = AppTools.query(args.get("appName"), "appName");
            JSONObject found = apps.find(new JSONObject().put("query", name).put("limit", 30), cancellation);
            if (found.getInt("totalMatches") != 1) {
                return found.put("action", "open_app").put("dispatched", false).put("goalVerified", false)
                        .put("nextStep", found.getInt("totalMatches") == 0
                                ? "Ask the user to open the app or provide its package name; visibility may be restricted"
                                : "Ask the user to choose a packageName from the candidates before opening");
            }
            packageName = found.getJSONArray("apps").getJSONObject(0).getString("packageName");
        }
        final String target = packageName;
        JSONObject result = onMain(cancellation, () -> {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(target);
            if (launch == null) throw new ActionException("app_not_launchable", "Package has no visible launcher; this does not prove it is uninstalled");
            cancellation.check();
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            cancellation.dispatched();
            context.startActivity(launch);
            invalidateSnapshot();
            return receipt("open_app", "native").put("packageName", target).put("dispatched", true);
        });
        long deadline = SystemClock.elapsedRealtime() + cancellation.remainingMillis(verifyMs);
        while (true) {
            cancellation.check();
            JSONObject foreground = onMain(cancellation, () -> foreground());
            if (target.equals(foreground.optString("packageName"))) {
                result.put("foregroundVerified", true).put("goalVerified", true)
                        .put("verificationScope", "requested package is in foreground; destination content is not verified")
                        .put("verificationRequired", "Observe the app before any further interaction");
                return afterObserve(result, "open_app", args, cancellation);
            }
            if (!foreground.optBoolean("available") || SystemClock.elapsedRealtime() >= deadline) {
                result.put("foregroundVerified", false).put("verificationStatus",
                                foreground.optBoolean("available") ? "not_confirmed" : "accessibility_unavailable")
                        .put("verificationRequired", "Launch was dispatched; the requested app has not been confirmed in foreground");
                return afterObserve(result, "open_app", args, cancellation);
            }
            pause(Math.min(150, Math.max(1, deadline - SystemClock.elapsedRealtime())), cancellation);
        }
    }

    private JSONObject waitFor(JSONObject args, Cancellation cancellation) throws Exception {
        boolean multiple = args.has("conditions");
        if (multiple == args.has("condition")) throw new ActionException("invalid_argument", "Provide condition or conditions, not both");
        JSONArray conditions = multiple ? args.optJSONArray("conditions") : new JSONArray().put(args.opt("condition"));
        if (conditions == null || conditions.length() == 0 || conditions.length() > 8)
            throw new ActionException("invalid_argument", "conditions must contain 1 to 8 screen conditions");
        String match = args.optString("match", "any");
        if (!match.equals("any") && !match.equals("all")) throw new ActionException("invalid_argument", "match must be any or all");
        for (int index = 0; index < conditions.length(); index++) validateCondition(conditions.optJSONObject(index));
        int timeoutMs = args.has("timeoutMs") ? ActionValidation.integer(args.get("timeoutMs"), "timeoutMs") : 10000;
        if (timeoutMs < 0 || timeoutMs > 60000) throw new ActionException("invalid_argument", "timeoutMs must be between 0 and 60000");
        long start = SystemClock.elapsedRealtime(), deadline = start + cancellation.remainingMillis(timeoutMs);
        String lastProblem = "";
        JSONObject lastObservation = null;
        while (true) {
            cancellation.check();
            try {
                boolean ready = onMain(cancellation, () -> probeConditions(conditions, match, cancellation));
                if (!ready && SystemClock.elapsedRealtime() < deadline) {
                    pause(Math.min(250, Math.max(1, deadline - SystemClock.elapsedRealtime())), cancellation);
                    continue;
                }
                // A cheap condition probe does not create action references. The
                // final full observation is authoritative and must still match.
                JSONObject observation = onMain(cancellation, () -> observe(cancellation));
                lastObservation = observation;
                int matched = 0, matchedIndex = -1;
                boolean unknownAbsence = false;
                for (int index = 0; index < conditions.length(); index++) {
                    JSONObject condition = conditions.getJSONObject(index);
                    boolean present = observationMatches(observation, condition);
                    boolean absent = condition.optString("state", "present").equals("absent");
                    boolean absenceKnown = !hasNodeSelector(condition) || !observation.optBoolean("truncated");
                    boolean inScope = !hasNodeSelector(condition) || !condition.has("packageName")
                            || condition.getString("packageName").equals(observation.optString("packageName"));
                    if (absent && !absenceKnown) unknownAbsence = true;
                    if (absent ? inScope && !present && absenceKnown : present) { matched++; matchedIndex = index; }
                }
                if (match.equals("all") ? matched == conditions.length() : matched > 0) {
                    JSONObject result = new JSONObject().put("status", "matched").put("elapsedMs", SystemClock.elapsedRealtime() - start)
                            .put("observation", observation).put("matchedConditionIndex", matchedIndex)
                            .put("goalVerified", false).put("meaning", "The screen condition matched; task completion requires its own evidence");
                    return result.put(multiple ? "conditions" : "condition", multiple ? conditions : conditions.getJSONObject(0));
                }
                lastProblem = unknownAbsence ? "observation_truncated" : "condition_not_matched";
            } catch (ActionException error) {
                if (!error.getCode().equals("window_unavailable")) throw error;
                lastProblem = error.getCode();
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                JSONObject result = new JSONObject().put("status", "timed_out")
                        .put(multiple ? "conditions" : "condition", multiple ? conditions : conditions.getJSONObject(0))
                        .put("elapsedMs", SystemClock.elapsedRealtime() - start).put("lastObservation", lastProblem)
                        .put("goalVerified", false).put("replayedAction", false)
                        .put("nextStep", "Use the returned observation to identify the current state; no action was repeated");
                if (lastObservation != null) result.put("observation", lastObservation);
                return result;
            }
            pause(Math.min(250, Math.max(1, deadline - SystemClock.elapsedRealtime())), cancellation);
        }
    }

    private boolean probeConditions(JSONArray conditions, String match, Cancellation cancellation) throws Exception {
        DroyAccessibilityService service = requireService(cancellation);
        AccessibilityNodeInfo root = service.getRootInActiveContentWindow();
        if (root == null) throw new ActionException("window_unavailable", "Android did not expose an active window");
        String packageName = string(root.getPackageName());
        boolean[] present = new boolean[conditions.length()];
        for (int index = 0; index < conditions.length(); index++) {
            JSONObject condition = conditions.getJSONObject(index);
            if (!hasNodeSelector(condition)) present[index] = packageName.equals(condition.optString("packageName"));
        }
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        boolean complete = true;
        try {
            while (!pending.isEmpty()) {
                cancellation.check();
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    if (++visited > MAX_VISITED) { complete = false; break; }
                    if (node.isVisibleToUser()) for (int index = 0; index < conditions.length(); index++) {
                        JSONObject condition = conditions.getJSONObject(index);
                        if (condition.has("packageName") && !packageName.equals(condition.getString("packageName"))) continue;
                        boolean matches = !hasNodeSelector(condition)
                                || condition.has("resourceId") && condition.getString("resourceId").equals(node.getViewIdResourceName())
                                || condition.has("text") && !node.isPassword()
                                    && (nodeValue(node).contains(condition.getString("text"))
                                        || string(node.getContentDescription()).contains(condition.getString("text")))
                                || condition.has("editable") && node.isEditable() == condition.getBoolean("editable")
                                    && node.isEnabled() && !node.isPassword()
                                || condition.has("clickable") && node.isClickable() == condition.getBoolean("clickable") && node.isEnabled();
                        present[index] |= matches;
                    }
                    if (conditionsSatisfied(conditions, match, packageName, present, false)) return true;
                    int children = node.getChildCount();
                    if (children > MAX_VISITED - visited - pending.size()) { complete = false; break; }
                    for (int index = 0; index < children; index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            return conditionsSatisfied(conditions, match, packageName, present, complete);
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
    }

    private static boolean conditionsSatisfied(JSONArray conditions, String match, String packageName,
                                                boolean[] present, boolean complete) throws Exception {
        int matched = 0;
        for (int index = 0; index < conditions.length(); index++) {
            JSONObject condition = conditions.getJSONObject(index);
            boolean absent = condition.optString("state", "present").equals("absent");
            boolean inScope = !hasNodeSelector(condition) || !condition.has("packageName")
                    || packageName.equals(condition.getString("packageName"));
            if (absent ? inScope && !present[index] && (!hasNodeSelector(condition) || complete) : present[index]) matched++;
        }
        return match.equals("all") ? matched == conditions.length() : matched > 0;
    }

    private static void validateCondition(JSONObject condition) throws Exception {
        if (condition == null) throw new ActionException("invalid_argument", "Each condition must be an object");
        int selectors = (condition.has("text") ? 1 : 0) + (condition.has("resourceId") ? 1 : 0)
                + (condition.has("editable") ? 1 : 0) + (condition.has("clickable") ? 1 : 0);
        if (selectors > 1 || (selectors == 0 && !condition.has("packageName")))
            throw new ActionException("invalid_argument", "A condition requires packageName and/or one of text, resourceId, editable, clickable");
        if (condition.has("packageName")) ActionValidation.packageName(condition.get("packageName"));
        for (String key : new String[]{"text", "resourceId"}) if (condition.has(key)) AppTools.query(condition.get(key), key);
        for (String key : new String[]{"editable", "clickable"}) if (condition.has(key) && !(condition.get(key) instanceof Boolean))
            throw new ActionException("invalid_argument", key + " must be a boolean");
        Object state = condition.opt("state");
        if (state != null && (!(state instanceof String) || (!state.equals("present") && !state.equals("absent"))))
            throw new ActionException("invalid_argument", "state must be present or absent");
    }

    private static boolean hasNodeSelector(JSONObject condition) {
        return condition.has("text") || condition.has("resourceId") || condition.has("editable") || condition.has("clickable");
    }

    private static boolean observationMatches(JSONObject observation, JSONObject condition) throws Exception {
        if (condition.has("packageName") && !condition.getString("packageName").equals(observation.optString("packageName"))) return false;
        if (!hasNodeSelector(condition)) return true;
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (condition.has("resourceId") && condition.getString("resourceId").equals(node.optString("resourceId"))) return true;
            if (condition.has("text") && (node.optString("text").contains(condition.getString("text"))
                    || node.optString("description").contains(condition.getString("text")))) return true;
            if (condition.has("editable") && node.optBoolean("editable") == condition.getBoolean("editable") && node.optBoolean("enabled") && !node.optBoolean("password")) return true;
            if (condition.has("clickable") && node.optBoolean("clickable") == condition.getBoolean("clickable") && node.optBoolean("enabled")) return true;
        }
        return false;
    }

    private JSONObject afterObserve(JSONObject result, String action, JSONObject args, Cancellation cancellation) throws Exception {
        if (!args.optBoolean("afterObserve", true) || action.equals("screenshot") || action.equals("observe")) return result;
        cancellation.check();
        try {
            if (args.has("afterCondition")) {
                JSONObject waited = waitFor(new JSONObject().put("condition", args.getJSONObject("afterCondition"))
                        .put("timeoutMs", args.optInt("afterTimeoutMs", 2000)), cancellation);
                result.put("afterConditionStatus", waited.getString("status"))
                        .put("afterConditionElapsedMs", waited.getLong("elapsedMs"))
                        .put("actionRepeated", false);
                if (waited.has("observation")) result.put("afterObservation", waited.getJSONObject("observation"));
            } else {
                JSONObject observed = onMain(cancellation, () -> observe(cancellation));
                result.put("afterObservation", observed);
            }
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception error) {
            cancellation.check();
            result.put("afterObservation", new JSONObject().put("status", "unavailable")
                    .put("code", error instanceof ActionException ? ((ActionException) error).getCode() : "observation_failed")
                    .put("message", "The action receipt is preserved; a fresh screen observation is required"))
                    .put("actionRepeated", false);
        }
        cancellation.check();
        return result;
    }

    private static void pause(long millis, Cancellation cancellation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + cancellation.remainingMillis(millis);
        do {
            cancellation.check();
            Thread.sleep(cancellation.remainingMillis(Math.min(50, Math.max(1, deadline - SystemClock.elapsedRealtime()))));
        } while (SystemClock.elapsedRealtime() < deadline);
        cancellation.check();
    }

    private JSONObject foreground() throws Exception {
        DroyAccessibilityService service = DroyAccessibilityService.current(context);
        if (service == null) return new JSONObject().put("available", false);
        AccessibilityNodeInfo root = service.getRootInActiveContentWindow();
        if (root == null) return new JSONObject().put("available", true).put("packageName", JSONObject.NULL);
        try { return new JSONObject().put("available", true).put("packageName", string(root.getPackageName())).put("windowId", root.getWindowId()); }
        finally { root.recycle(); }
    }

    private JSONObject observe(Cancellation cancellation) throws Exception {
        long began = SystemClock.elapsedRealtime();
        DroyAccessibilityService service = requireService(cancellation);
        invalidateSnapshot();
        AccessibilityNodeInfo root = service.getRootInActiveContentWindow();
        if (root == null) throw new ActionException("window_unavailable", "Android did not expose an active window; unlock the screen or open an app");
        service.syncActiveWindow(root);
        Snapshot next = new Snapshot(UUID.randomUUID().toString(), DroyAccessibilityService.generation(), DroyAccessibilityService.hardGeneration(),
                SystemClock.elapsedRealtime(), root.getWindowId(), string(root.getPackageName()), displayBounds(), false,
                service.coordinateContentBounds());
        ObservationTree tree = new ObservationTree(root, cancellation);
        JSONArray nodes = new JSONArray();
        StringBuilder pageText = new StringBuilder();
        Set<String> seenText = new HashSet<>();
        Map<ReadNode, SavedNode> contexts = new HashMap<>();
        boolean truncated = tree.truncated;
        try {
            next.coordinateContextSignature = tree.capture(tree.nodes.get(0), false).signature;
            for (ReadNode read : tree.nodes) {
                cancellation.check();
                if (read.depth > 40) { truncated = true; continue; }
                if (nodes.length() >= MAX_NODES) { truncated = true; break; }
                AccessibilityNodeInfo node = read.node;
                SavedNode childContext = contexts.get(read.parent);
                if (node.isVisibleToUser()) {
                    Rect bounds = read.bounds;
                    boolean password = node.isPassword();
                    int remaining = Math.max(0, MAX_TEXT_CHARS - pageText.length());
                    CharSequence rawText = password ? null : nodeValue(node);
                    String text = bounded(rawText, Math.min(1000, remaining));
                    truncated |= rawText != null && rawText.length() > text.length();
                    remaining -= text.length();
                    CharSequence rawDescription = password ? null : node.getContentDescription();
                    String description = bounded(rawDescription, Math.min(1000, Math.max(0, remaining)));
                    truncated |= rawDescription != null && rawDescription.length() > description.length();
                    CharSequence rawHint = password ? null : node.getHintText();
                    String hint = bounded(rawHint, 1000);
                    truncated |= rawHint != null && rawHint.length() > hint.length();
                    String resource = string(node.getViewIdResourceName());
                    JSONArray supportedActions = supportedNodeActions(node);
                    boolean meaningful = node.isClickable() || node.isEditable() || node.isLongClickable() || node.isScrollable()
                            || node.isCheckable() || password || node.getCollectionItemInfo() != null
                            || supportedActions.length() > 0 || !text.isEmpty() || !description.isEmpty() || !hint.isEmpty() || !resource.isEmpty();
                    if (meaningful) {
                        String id = "n" + nodes.length();
                        SavedNode savedNode = new SavedNode(read, childContext, tree);
                        JSONObject item = new JSONObject().put("nodeId", id)
                                .put("text", text).put("description", description)
                                .put("className", string(node.getClassName())).put("resourceId", resource)
                                .put("clickable", node.isClickable()).put("editable", node.isEditable())
                                .put("enabled", node.isEnabled()).put("password", password).put("bounds", bounds(bounds))
                                .put("scrollable", node.isScrollable()).put("longClickable", node.isLongClickable())
                                .put("checkable", node.isCheckable()).put("checked", node.isChecked()).put("selected", node.isSelected())
                                .put("supportedActions", supportedActions);
                        if (!hint.isEmpty()) item.put("hint", hint);
                        if (node.isEditable()) item.put("showingHint", node.isShowingHintText());
                        if (savedNode.functionAnchor != null) item.put("role", "search_entry");
                        nodes.put(item);
                        if (!text.isEmpty() && seenText.add(text)) pageText.append(text).append('\n');
                        if (!description.isEmpty() && seenText.add(description)) pageText.append(description).append('\n');
                        next.nodes.put(id, savedNode);
                        if (node.getCollectionItemInfo() != null) childContext = savedNode;
                    }
                }
                contexts.put(read, childContext);
            }
            truncated |= pageText.length() >= MAX_TEXT_CHARS;
            requireService(cancellation);
            Rect display = displayBounds();
            JSONObject result = new JSONObject().put("status", "observed").put("channel", "accessibility")
                    .put("packageName", next.packageName).put("snapshotId", next.id)
                    .put("snapshotMaxAgeMs", SnapshotGuard.MAX_AGE_MS)
                    .put("coordinateSnapshotMaxAgeMs", SnapshotGuard.MAX_AGE_MS).put("nodeBasisPolicy", "refresh_original_node")
                    .put("width", display.width()).put("height", display.height())
                    .put("nodes", nodes).put("text", bounded(pageText, MAX_TEXT_CHARS)).put("truncated", truncated)
                    .put("capturedAt", System.currentTimeMillis()).put("observationMs", SystemClock.elapsedRealtime() - began)
                    .put("visitedNodes", tree.nodes.size());
            next.observation = new JSONObject(result.toString());
            snapshot = next;
            java.util.ArrayList<AccessibilityNodeInfo> pageNodes = new java.util.ArrayList<>();
            for (SavedNode node : next.nodes.values()) pageNodes.add(node.node);
            next.navigationPage = navigationPage(pageNodes, next.screen);
            for (AccessibilityNodeInfo header : navigationHeaders(pageNodes, next.screen))
                next.navigationHeaders.put(header, navigationHeaderLabel(header));
            return result;
        } catch (Exception error) {
            next.recycle();
            throw error;
        } finally { tree.close(); }
    }

    private JSONObject nodeAction(JSONObject args, boolean input, Cancellation cancellation) throws Exception {
        return nodeAction(args, input ? "set_text" : "click", cancellation);
    }

    private static String navigationKey(AccessibilityNodeInfo node) {
        if (node.isPassword() || node.isEditable()) return "";
        String direct = NavigationTarget.key(nodeValue(node), string(node.getContentDescription()), node.getViewIdResourceName());
        if (direct.startsWith("label:") || node.getChildCount() == 0) return direct;
        // Resource IDs often identify recycled card containers, not their product.
        // Keep the small card's content, not a leading badge or the reusable resource ID.
        Rect objectBounds = new Rect(); node.getBoundsInScreen(objectBounds);
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child != null) pending.addLast(child);
        }
        int visited = 0;
        try {
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo child = pending.removeFirst();
                try {
                    if (++visited > 64 || !child.refresh()) return "";
                    if (!child.isVisibleToUser() || child.isPassword() || child.isEditable()) continue;
                    Rect childBounds = new Rect(); child.getBoundsInScreen(childBounds);
                    if (child.isClickable() && !childBounds.equals(objectBounds)) continue;
                    String label = NavigationTarget.key(nodeValue(child), string(child.getContentDescription()), "");
                    if (label.startsWith("label:")) labels.add(label.substring(6));
                    if (child.getChildCount() > 64 - visited - pending.size()) return "";
                    for (int i = 0; i < child.getChildCount(); i++) {
                        AccessibilityNodeInfo next = child.getChild(i); if (next != null) pending.addLast(next);
                    }
                } finally { child.recycle(); }
            }
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
        return labels.isEmpty() ? direct : "label:" + String.join("\u001f", labels);
    }

    private static List<AccessibilityNodeInfo> navigationHeaders(List<AccessibilityNodeInfo> nodes, Rect screen) {
        java.util.ArrayList<AccessibilityNodeInfo> eligible = new java.util.ArrayList<>();
        int firstRow = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!node.isVisibleToUser() || node.isPassword() || node.isClickable() && !node.isEditable()
                    || bounds.bottom > screen.top + screen.height() / 4 || bounds.isEmpty()
                    || navigationHeaderLabel(node).isEmpty()) continue;
            firstRow = Math.min(firstRow, bounds.top); eligible.add(node);
        }
        java.util.ArrayList<AccessibilityNodeInfo> headers = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo node : eligible) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (bounds.top <= firstRow + 32) headers.add(node);
        }
        return headers;
    }

    private static String navigationHeaderLabel(AccessibilityNodeInfo node) {
        return NavigationTarget.label(nodeValue(node) + " " + string(node.getContentDescription()));
    }

    private static String navigationPage(List<AccessibilityNodeInfo> nodes, Rect screen) {
        java.util.TreeSet<String> labels = new java.util.TreeSet<>();
        for (AccessibilityNodeInfo node : navigationHeaders(nodes, screen)) labels.add(navigationHeaderLabel(node));
        return String.join("\n", labels);
    }

    private static boolean originalPageAvailable(Snapshot saved) {
        for (Map.Entry<AccessibilityNodeInfo, String> header : saved.navigationHeaders.entrySet()) {
            if (!header.getKey().refresh() || !header.getKey().isVisibleToUser()
                    || !header.getValue().equals(navigationHeaderLabel(header.getKey()))) return false;
        }
        return true;
    }

    private static boolean ancestorOf(AccessibilityNodeInfo ancestor, AccessibilityNodeInfo target) {
        AccessibilityNodeInfo parent = target.getParent();
        try {
            for (int depth = 0; parent != null && depth < 40; depth++) {
                if (parent.equals(ancestor)) return true;
                AccessibilityNodeInfo next = parent.getParent(); parent.recycle(); parent = next;
            }
            return false;
        } finally { if (parent != null) parent.recycle(); }
    }

    private SavedNode navigationBasis(Snapshot saved, JSONObject args) throws Exception {
        if (args.has("nodeId")) {
            SavedNode explicit = saved.nodes.get(args.optString("nodeId"));
            if (explicit == null) throw new ActionException("invalid_node", "nodeId is not present in this snapshot");
            SavedNode selected = explicit;
            for (SavedNode node : saved.nodes.values())
                if (node.clickable && node.bounds.equals(explicit.bounds) && node.navigationClarity > selected.navigationClarity) selected = node;
            return selected;
        }
        int x = coordinate(args, "x", saved.screen.width()), y = coordinate(args, "y", saved.screen.height());
        SavedNode selected = null;
        long smallest = Long.MAX_VALUE;
        for (SavedNode node : saved.nodes.values()) {
            if (!node.clickable || node.navigationKey.isEmpty() || !node.bounds.contains(x, y)) continue;
            long area = (long) node.bounds.width() * node.bounds.height();
            if (area < smallest || area == smallest && selected != null
                    && node.navigationClarity > selected.navigationClarity) {
                smallest = area; selected = node;
            }
        }
        return selected;
    }

    /** One current-page lookup and one dispatch; it never repeats an attempted action. */
    private JSONObject navigationTap(JSONObject args, Cancellation cancellation) throws Exception {
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        boolean coordinates = !args.has("nodeId");
        boolean[] relocated = {false};
        try {
            if (coordinates && onMain(cancellation, () -> {
                Snapshot saved = snapshot;
                if (saved == null || !saved.id.equals(args.optString("snapshotId"))) return false;
                SavedNode basis = navigationBasis(saved, args);
                return basis == null || basis.navigationKey.isEmpty();
            })) return gesture(args, false, cancellation);
            boolean submittedGesture = onMain(cancellation, () -> {
                DroyAccessibilityService service = requireService(cancellation);
                Snapshot saved = snapshot;
                SnapshotGuard.checkNode(args.optString("snapshotId", null), saved == null ? null : saved.id,
                        0, 0, saved == null ? -1 : saved.createdMs, SystemClock.elapsedRealtime());
                if (coordinates) SnapshotGuard.check(args.optString("snapshotId"), saved.id, 0, 0,
                        saved.createdMs, SystemClock.elapsedRealtime());
                SavedNode original = navigationBasis(saved, args);
                if (original == null || original.navigationKey.isEmpty())
                    throw stale("navigation_target_missing", "No semantic navigation target at this point; use a current coordinate basis without navigation");
                String key = original.navigationKey;
                AccessibilityNodeInfo root = service.getRootInActiveContentWindow();
                if (root == null) throw stale("window_changed", "The target application is no longer available");
                java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
                java.util.ArrayList<AccessibilityNodeInfo> live = new java.util.ArrayList<>();
                ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
                pending.add(root);
                try {
                    if (root.getWindowId() != saved.windowId || !saved.packageName.equals(string(root.getPackageName()))
                            || saved.hardGeneration != DroyAccessibilityService.hardGeneration()
                            || !saved.screen.equals(displayBounds()))
                        throw stale("window_changed", "The target application/window changed");
                    AccessibilityNodeInfo target;
                    long lookupDeadline = Long.MAX_VALUE;
                    boolean originalAvailable = original.node.refresh() && original.node.isVisibleToUser()
                            && original.node.isEnabled() && original.node.isClickable()
                            && key.equals(navigationKey(original.node)) && originalPageAvailable(saved);
                    if (originalAvailable) {
                        target = AccessibilityNodeInfo.obtain(original.node); nodes.add(target);
                    } else {
                        relocated[0] = true;
                        lookupDeadline = SystemClock.elapsedRealtime() + cancellation.remainingMillis(1000);
                        while (!pending.isEmpty()) {
                            cancellation.check();
                            if (SystemClock.elapsedRealtime() >= lookupDeadline)
                                throw stale("navigation_lookup_incomplete", "Current target lookup did not finish within its local budget");
                            AccessibilityNodeInfo node = pending.removeFirst(); nodes.add(node);
                            if (nodes.size() > MAX_VISITED) throw stale("navigation_ambiguous", "Current navigation targets could not be fully read");
                            if (!node.refresh() || !node.isVisibleToUser()) continue;
                            live.add(node);
                            if (node.getChildCount() > MAX_VISITED - nodes.size() - pending.size())
                                throw stale("navigation_ambiguous", "Current navigation targets exceed the bounded page read");
                            for (int i = 0; i < node.getChildCount(); i++) {
                                AccessibilityNodeInfo child = node.getChild(i); if (child != null) pending.addLast(child);
                            }
                        }
                        if (saved.navigationPage.isEmpty() || !saved.navigationPage.equals(navigationPage(live, saved.screen)))
                            throw stale("page_changed", "The page's navigation context changed; select a target from the current observation");
                        java.util.ArrayList<AccessibilityNodeInfo> actionable = new java.util.ArrayList<>();
                        java.util.ArrayList<NavigationTarget.Candidate> candidates = new java.util.ArrayList<>();
                        for (AccessibilityNodeInfo node : live) {
                            cancellation.check();
                            if (SystemClock.elapsedRealtime() >= lookupDeadline)
                                throw stale("navigation_lookup_incomplete", "Current target lookup did not finish within its local budget");
                            if (!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable() || node.isPassword() || node.isEditable()) continue;
                            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                            if (bounds.isEmpty()) continue;
                            actionable.add(node);
                            candidates.add(new NavigationTarget.Candidate(navigationKey(node), bounds.toShortString(),
                                    !string(node.getContentDescription()).isEmpty() ? 2 : !nodeValue(node).isEmpty() ? 1 : 0));
                        }
                        // An unlabeled ancestor container can summarize its child's object plus badges.
                        // It is not a second business target; independent siblings still remain conflicts.
                        for (int i = 0; i < candidates.size(); i++) {
                            NavigationTarget.Candidate item = candidates.get(i);
                            if (item.clarity() != 0 || item.key().equals(key)) continue;
                            for (int j = 0; j < candidates.size(); j++) {
                                if (candidates.get(j).key().equals(key) && candidates.get(j).bounds().equals(item.bounds())
                                        && ancestorOf(actionable.get(i), actionable.get(j))) {
                                    candidates.set(i, new NavigationTarget.Candidate("", item.bounds(), 0)); break;
                                }
                            }
                        }
                        int selected = NavigationTarget.select(candidates, key);
                        if (selected < 0) throw stale("navigation_ambiguous", "The original navigation object is missing or has more than one current match");
                        target = actionable.get(selected);
                        for (int i = 0; i < actionable.size(); i++) {
                            if (i == selected || !candidates.get(i).bounds().equals(candidates.get(selected).bounds())
                                    || candidates.get(i).key().equals(key)) continue;
                            if (!ancestorOf(actionable.get(i), target))
                                throw stale("navigation_ambiguous", "An independent control shares this object's bounds");
                        }
                    }
                    Rect bounds = new Rect(); target.getBoundsInScreen(bounds);
                    int x = coordinates ? coordinate(args, "x", saved.screen.width()) : bounds.centerX();
                    int y = coordinates ? coordinate(args, "y", saved.screen.height()) : bounds.centerY();
                    if (coordinates && !bounds.contains(x, y))
                        throw stale("target_moved", "The navigation object moved away from the explicit touch point");
                    if (coordinates) checkNavigationPoint(service, target, bounds, x, y, key, cancellation);
                    else {
                        // A card's small independent buttons do not hide its whole body.
                        // This only finds a clear body; ACTION_CLICK still addresses the selected semantic node.
                        ActionException blocked = null;
                        int[][] points = {{x, y}, {bounds.centerX(), bounds.top + bounds.height() / 4},
                                {bounds.left + bounds.width() / 4, bounds.top + bounds.height() / 4}};
                        for (int[] point : points) {
                            try { checkNavigationPoint(service, target, bounds, point[0], point[1], key, cancellation); blocked = null; break; }
                            catch (ActionException failure) { if (!"target_obscured".equals(failure.getReason())) throw failure; blocked = failure; }
                        }
                        if (blocked != null) throw blocked;
                    }
                    requireService(cancellation);
                    if (!target.refresh() || !target.isVisibleToUser() || !target.isEnabled() || !target.isClickable()
                            || !key.equals(navigationKey(target)))
                        throw stale("target_changed", "The navigation target changed during its current-page check");
                    Rect finalBounds = new Rect(); target.getBoundsInScreen(finalBounds);
                    if (!bounds.equals(finalBounds)) throw stale("target_changed", "The navigation target moved during its current-page check");
                    if (SystemClock.elapsedRealtime() >= lookupDeadline)
                        throw stale("navigation_lookup_incomplete", "Current target lookup did not finish within its local budget");
                    // Navigation selection runs on the main thread; no model round trip or automatic replay intervenes.
                    if (coordinates) {
                        Path path = new Path(); path.moveTo(x, y);
                        GestureDescription gesture = new GestureDescription.Builder()
                                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70)).build();
                        cancellation.dispatched();
                        boolean accepted = service.dispatchGesture(gesture, gestureCallback(completed), main);
                        invalidateSnapshot();
                        if (!accepted) throw new ActionException("action_rejected", "Android rejected the navigation gesture");
                        return true;
                    }
                    cancellation.dispatched();
                    boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    invalidateSnapshot();
                    if (!accepted) throw new ActionException("action_rejected", "The app rejected the navigation action");
                    return false;
                } finally {
                    for (AccessibilityNodeInfo node : nodes) node.recycle();
                    while (!pending.isEmpty()) pending.removeFirst().recycle();
                }
            });
            if (submittedGesture && !await(completed, UI_TIMEOUT_MS, cancellation))
                throw new ActionException("gesture_cancelled", "Navigation gesture was cancelled; check the resulting screen before continuing");
            return receipt("tap", "accessibility").put("navigation", true)
                    .put("navigationResolution", relocated[0] ? "relocated" : "original");
        } finally { completed.cancel(false); }
    }

    private void checkNavigationPoint(DroyAccessibilityService service, AccessibilityNodeInfo target, Rect bounds,
                                      int x, int y, String key, Cancellation cancellation) throws Exception {
        Rect point = new Rect(x, y, x + 1, y + 1);
        checkNodeUnobscured(service, target, point, false, cancellation, bounds, key);
        checkCoveringControls(AccessibilityNodeInfo.obtain(target), point, cancellation, bounds, key);
    }

    private static JSONArray supportedNodeActions(AccessibilityNodeInfo node) {
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) ids.add(action.getId());
        return new JSONArray(NodeActions.supported(ids));
    }

    private JSONObject nodeAction(JSONObject args, String operation, Cancellation cancellation) throws Exception {
        boolean input = operation.equals("set_text"), click = operation.equals("click");
        int requestedAction = input ? AccessibilityNodeInfo.ACTION_SET_TEXT : click ? AccessibilityNodeInfo.ACTION_CLICK : NodeActions.id(operation);
        DroyAccessibilityService service = requireService(cancellation);
        String expected = args.opt("snapshotId") instanceof String ? args.getString("snapshotId") : null;
        String nodeId = args.opt("nodeId") instanceof String ? args.getString("nodeId") : null;
        Snapshot saved = snapshot;
        if (saved != null && saved.hardGeneration != DroyAccessibilityService.hardGeneration()) {
            throw new ActionException("stale_snapshot", "window_changed", "UI window basis changed (" + DroyAccessibilityService.lastHardChange()
                    + "); observe again before a node action");
        }
        SnapshotGuard.checkNode(expected, saved == null ? null : saved.id,
                saved == null ? -1 : saved.hardGeneration, DroyAccessibilityService.hardGeneration(),
                saved == null ? -1 : saved.createdMs, SystemClock.elapsedRealtime());
        SavedNode entry = saved.nodes.get(nodeId);
        if (entry == null) throw new ActionException("invalid_node", "nodeId is not present in this snapshot");
        AccessibilityNodeInfo currentRoot = service.getRootInActiveContentWindow();
        try {
            service.syncActiveWindow(currentRoot);
            if (currentRoot == null || currentRoot.getWindowId() != saved.windowId || !saved.screen.equals(displayBounds())
                    || !saved.packageName.equals(string(currentRoot.getPackageName()))) {
                throw stale("window_changed", "Foreground window changed; observe again");
            }
        } finally {
            if (currentRoot != null) currentRoot.recycle();
        }
        AccessibilityNodeInfo node = entry.node;
        Rect currentBounds = refreshSavedNode(saved, entry, false);
        Rect actionBounds = currentBounds;
        if (click || operation.equals("long_click") || operation.startsWith("scroll_")) {
            // Check the exposed center of this exact target. A separate corner
            // button does not cover its body or change its semantic click.
            Rect viewport = new Rect(currentBounds);
            if (!viewport.intersect(service.coordinateContentBounds()))
                throw stale("target_obscured", "The target has no visible content area");
            actionBounds = new Rect(viewport.centerX(), viewport.centerY(), viewport.centerX() + 1, viewport.centerY() + 1);
        }
        checkNodeUnobscured(service, node, actionBounds, input, cancellation);
        if (node.isPassword()) throw new ActionException("protected_field", "Password fields require user takeover");
        String text = input ? ActionValidation.text(args.opt("text")) : null;
        if (input) {
            if (!node.isEditable()) throw new ActionException("not_editable", "The selected node is not editable");
        } else if (click && !node.isClickable()) {
            throw new ActionException("not_clickable", "The selected node is not clickable; choose a clickable node or explicit coordinates");
        }
        requireService(cancellation);
        // Traversing covering windows/controls can cross an app UI update.
        refreshOriginalNode(saved, entry);
        SnapshotGuard.checkNode(expected, snapshot == null ? null : snapshot.id, saved.hardGeneration,
                DroyAccessibilityService.hardGeneration(), saved.createdMs, SystemClock.elapsedRealtime());
        if (!input && !click) {
            boolean supported = false;
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList())
                if (action.getId() == requestedAction) { supported = true; break; }
            if (!supported) throw new ActionException("node_action_unavailable", "The current node does not expose this accessibility action");
        }
        // Consume before dispatch: a timeout, app exception or uncertain result
        // must never leave an old basis usable for a repeated action.
        snapshot = null;
        DroyAccessibilityService.invalidateSnapshots();
        try {
            boolean accepted;
            cancellation.dispatched();
            if (input) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            } else {
                accepted = node.performAction(requestedAction);
            }
            if (!accepted) throw new ActionException("action_rejected", "The app rejected the accessibility action");
            JSONObject result = receipt(input ? "input_text" : click ? "tap" : "node_action", "accessibility");
            if (!input && !click) result.put("operation", operation);
            if (input) {
                // ACTION_SET_TEXT acceptance alone does not establish that the
                // app retained the exact Unicode text or submitted a search.
                boolean available = node.refresh() && node.isEditable() && !node.isPassword()
                        && node.getWindowId() == saved.windowId && saved.packageName.equals(string(node.getPackageName()));
                boolean verified = available && text.equals(nodeValue(node));
                result.put("inputTextVerified", verified).put("inputVerification", verified ? "matched" : "not_confirmed")
                        .put("submitActionRequested", false).put("verificationRequired", verified
                                ? "Text matched in the editable node; inspect the resulting screen and submit separately if needed"
                                : "The action was accepted but exact text was not confirmed; observe before retrying or submitting");
            }
            return result;
        } finally {
            saved.recycle();
        }
    }

    private Rect refreshOriginalNode(Snapshot saved, SavedNode entry) throws ActionException {
        Rect bounds = refreshSavedNode(saved, entry);
        SavedNode child = entry;
        for (SavedNode parent = entry.context; parent != null; parent = parent.context) {
            refreshSavedNode(saved, parent);
            AccessibilityNodeInfo ancestor = child.node.getParent();
            boolean found = false;
            try {
                for (int depth = 0; ancestor != null && depth < 40; depth++) {
                    if (ancestor.equals(parent.node)) { found = true; break; }
                    AccessibilityNodeInfo next = ancestor.getParent();
                    ancestor.recycle();
                    ancestor = next;
                }
            } finally { if (ancestor != null) ancestor.recycle(); }
            if (!found) throw stale("target_changed", "Target moved to another content context; observe again");
            child = parent;
        }
        return bounds;
    }

    private Rect refreshSavedNode(Snapshot saved, SavedNode entry) throws ActionException {
        return refreshSavedNode(saved, entry, true);
    }

    private Rect refreshSavedNode(Snapshot saved, SavedNode entry, boolean checkContent) throws ActionException {
        AccessibilityNodeInfo node = entry.node;
        if (!node.refresh()) throw stale("target_changed", "Node is no longer available; observe again");
        Rect currentBounds = new Rect();
        node.getBoundsInScreen(currentBounds);
        if (!entry.bounds.equals(currentBounds) || currentBounds.isEmpty()) throw stale("target_changed",
                "Target bounds changed; saved=" + entry.bounds.toShortString() + "; current="
                        + currentBounds.toShortString() + "; observe again");
        if (!entry.signature.equals(signature(node))) throw stale("target_changed", "Target properties changed; observe again");
        if (node.getWindowId() != saved.windowId || !saved.packageName.equals(string(node.getPackageName()))) {
            throw stale("target_changed", "Target window identity changed; observe again");
        }
        if (!node.isVisibleToUser()) throw stale("target_changed", "Target is no longer visible; observe again");
        if (!node.isEnabled()) throw stale("target_changed", "Target is disabled; observe again");
        if (checkContent && entry.functionAnchor != null && !entry.functionAnchor.equals(functionAnchor(node)))
            throw stale("target_changed", "The functional entry changed; observe again");
        if (checkContent && (entry.contentSignature == null || (entry.capturesContent && entry.functionAnchor == null
                && !entry.contentSignature.equals(contentSignature(node))))) {
            throw stale("target_changed", "Target content or its containing item changed; observe again");
        }
        return currentBounds;
    }

    private void checkNodeUnobscured(DroyAccessibilityService service, AccessibilityNodeInfo target,
                                     Rect bounds, Cancellation cancellation) throws Exception {
        checkNodeUnobscured(service, target, bounds, false, cancellation);
    }

    private void checkNodeUnobscured(DroyAccessibilityService service, AccessibilityNodeInfo target,
                                     Rect bounds, boolean textInput, Cancellation cancellation) throws Exception {
        checkNodeUnobscured(service, target, bounds, textInput, cancellation, null, null);
    }

    private void checkNodeUnobscured(DroyAccessibilityService service, AccessibilityNodeInfo target,
                                     Rect bounds, boolean textInput, Cancellation cancellation,
                                     Rect navigationBounds, String navigationKey) throws Exception {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        int targetLayer = Integer.MIN_VALUE;
        Rect screen = displayBounds();
        Rect content = service.coordinateContentBounds();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == target.getWindowId()) targetLayer = window.getLayer();
            }
            if (targetLayer != Integer.MIN_VALUE) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getId() == target.getWindowId() || window.getLayer() <= targetLayer
                            || OverlayInteraction.ownsWindow(window.getId())) continue;
                    Rect covering = new Rect();
                    window.getBoundsInScreen(covering);
                    if (Rect.intersects(covering, bounds)) {
                        boolean systemWindow = window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM;
                        boolean clearEdge = textInput
                                ? InputNodeOcclusion.mayIgnoreSystemBar(true, target.isEditable(), systemWindow,
                                    window.isFocused(), window.isActive(), inputBounds(screen), inputBounds(content), inputBounds(bounds), inputBounds(covering))
                                : InputNodeOcclusion.mayIgnorePassiveSystemBar(systemWindow, window.isFocused(), window.isActive(),
                                    inputBounds(screen), inputBounds(content), inputBounds(bounds), inputBounds(covering));
                        if (clearEdge) continue;
                        throw stale("target_obscured", "Another window covers the target; observe again")
                                .withDetails(new JSONObject().put("occlusionKind", "other_window")
                                        .put("targetBounds", bounds(bounds)).put("blockerBounds", bounds(covering))
                                        .put("blockerWindowId", window.getId()));
                    }
                }
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        AccessibilityNodeInfo branch = AccessibilityNodeInfo.obtain(target);
        try {
            for (int depth = 0; depth < 40; depth++) {
                cancellation.check();
                AccessibilityNodeInfo parent = branch.getParent();
                if (parent == null) return;
                try {
                    if (parent.getChildCount() > MAX_VISITED) {
                        throw new ActionException("stale_snapshot", "Target hierarchy could not be fully validated; observe again");
                    }
                    int branchIndex = -1;
                    for (int index = 0; index < parent.getChildCount(); index++) {
                        AccessibilityNodeInfo child = parent.getChild(index);
                        if (child == null) continue;
                        try { if (branch.equals(child)) { branchIndex = index; break; } }
                        finally { child.recycle(); }
                    }
                    if (branchIndex < 0) throw new ActionException("stale_snapshot", "Target hierarchy changed; observe again");
                    for (int index = 0; index < parent.getChildCount(); index++) {
                        if (index == branchIndex) continue;
                        AccessibilityNodeInfo sibling = parent.getChild(index);
                        if (sibling == null) continue;
                        int order = sibling.getDrawingOrder(), branchOrder = branch.getDrawingOrder();
                        boolean above = order > branchOrder || (order == branchOrder && index > branchIndex);
                        if (!above) { sibling.recycle(); continue; }
                        // The walk owns sibling and all child handles it obtains.
                        checkCoveringControls(sibling, bounds, cancellation, navigationBounds, navigationKey);
                    }
                    branch.recycle();
                    branch = parent;
                    parent = null;
                } finally { if (parent != null) parent.recycle(); }
            }
            throw new ActionException("stale_snapshot", "Target hierarchy could not be fully validated; observe again");
        } finally { branch.recycle(); }
    }

    private static InputNodeOcclusion.Bounds inputBounds(Rect bounds) {
        return new InputNodeOcclusion.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    private void checkCoveringControls(AccessibilityNodeInfo root, Rect target, Cancellation cancellation,
                                      Rect navigationBounds, String key) throws Exception {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        try {
            while (!pending.isEmpty()) {
                cancellation.check();
                AccessibilityNodeInfo current = pending.removeFirst();
                try {
                    if (++visited > MAX_VISITED) throw new ActionException("stale_snapshot", "Covering content could not be fully validated; observe again");
                    Rect region = new Rect();
                    current.getBoundsInScreen(region);
                    if (!current.isVisibleToUser() || !Rect.intersects(region, target)) continue;
                    boolean duplicate = navigationBounds != null && navigationBounds.equals(region)
                            && !current.isEditable() && !current.isPassword()
                            && navigationKey(current).equals(key);
                    if (!duplicate && (current.isClickable() || current.isEditable() || current.isLongClickable())) {
                        JSONObject details = new JSONObject().put("occlusionKind", "sibling_control")
                                .put("targetBounds", bounds(navigationBounds == null ? target : navigationBounds))
                                .put("blockerBounds", bounds(region)).put("blockerClass", string(current.getClassName()))
                                .put("blockerDrawingOrder", current.getDrawingOrder());
                        if (navigationBounds != null) details.put("touchBounds", bounds(target));
                        throw stale("target_obscured", "Another control covers the target; observe again").withDetails(details);
                    }
                    if (current.getChildCount() > MAX_VISITED - visited - pending.size()) {
                        throw new ActionException("stale_snapshot", "Covering content could not be fully validated; observe again");
                    }
                    for (int index = 0; index < current.getChildCount(); index++) {
                        AccessibilityNodeInfo child = current.getChild(index);
                        if (child != null) pending.addLast(child);
                    }
                } finally { current.recycle(); }
            }
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
    }

    private GesturePlan planGesture(String action, JSONObject args) throws Exception {
        Rect screen = displayBounds();
        Integer duration = args.has("durationMs") ? ActionValidation.duration(args.get("durationMs")) : null;
        if (action.equals("gesture")) {
            Object raw = args.opt("pointers");
            if (!(raw instanceof JSONArray)) throw new ActionException("invalid_argument", "pointers must be an array");
            JSONArray pointers = (JSONArray) raw;
            if (pointers.length() < 2 || pointers.length() > GesturePlan.MAX_POINTERS)
                throw new ActionException("invalid_argument", "gesture requires 2 to 5 pointers");
            int[][][] points = new int[pointers.length()][][];
            for (int p = 0; p < points.length; p++) {
                JSONObject pointer = pointers.optJSONObject(p);
                JSONArray path = pointer == null ? null : pointer.optJSONArray("points");
                if (path == null || path.length() < 2 || path.length() > GesturePlan.MAX_POINTS)
                    throw new ActionException("invalid_argument", "Each pointer needs 2 to 16 points");
                points[p] = new int[path.length()][2];
                for (int i = 0; i < path.length(); i++) {
                    JSONObject point = path.optJSONObject(i);
                    if (point == null) throw new ActionException("invalid_argument", "Each point needs x and y");
                    points[p][i][0] = coordinate(point, "x", screen.width());
                    points[p][i][1] = coordinate(point, "y", screen.height());
                }
            }
            return GesturePlan.multiple(points, duration == null ? 400 : duration, screen.width(), screen.height());
        }
        int x = coordinate(args, "startX", screen.width()), y = coordinate(args, "startY", screen.height());
        int endX = coordinate(args, "endX", screen.width()), endY = coordinate(args, "endY", screen.height());
        Rect allowed = screen;
        if (snapshot != null && snapshot.contentBounds.contains(x, y) && snapshot.contentBounds.contains(endX, endY)) {
            allowed = new Rect(snapshot.contentBounds);
            allowed.intersect(screen);
        }
        return GesturePlan.swipe(x, y, endX, endY, duration, args.optString("motion", "precise").equals("natural"),
                context.getResources().getDisplayMetrics().density, allowed.left, allowed.top, allowed.right, allowed.bottom,
                java.util.concurrent.ThreadLocalRandom.current());
    }

    private static Rect planBounds(GesturePlan plan) {
        return new Rect(plan.left, plan.top, plan.right, plan.bottom);
    }

    private JSONObject plannedGesture(String action, JSONObject args, GesturePlan plan, Cancellation cancellation) throws Exception {
        GestureRun run = new GestureRun();
        Rect screen = displayBounds();
        String[] foreground = new String[1];
        int[] window = new int[1];
        try {
            onMain(cancellation, () -> {
                Snapshot basis = checkCoordinateSnapshot(args, screen, true, planBounds(plan), cancellation);
                foreground[0] = basis.packageName; window[0] = basis.windowId;
                return null;
            });
            for (int segment = 0; segment < plan.durations.length; segment++) {
                final int index = segment;
                onMain(cancellation, () -> {
                    DroyAccessibilityService service = requireService(cancellation);
                    if (!screen.equals(displayBounds())) throw stale("display_changed", "Display changed during the gesture");
                    AccessibilityNodeInfo root = service.getRootInActiveContentWindow();
                    try {
                        if (root == null || !foreground[0].equals(string(root.getPackageName())) || window[0] != root.getWindowId())
                            throw stale("window_changed", "Foreground changed during the gesture; observe before continuing");
                    } finally { if (root != null) root.recycle(); }
                    GestureDescription.Builder builder = new GestureDescription.Builder();
                    GestureDescription.StrokeDescription[] next = new GestureDescription.StrokeDescription[plan.paths.length];
                    int first = index * plan.samplesPerSegment(), last = first + plan.samplesPerSegment();
                    boolean more = index + 1 < plan.durations.length;
                    for (int p = 0; p < next.length; p++) {
                        Path path = new Path();
                        path.moveTo(plan.paths[p][first][0], plan.paths[p][first][1]);
                        for (int i = first + 1; i <= last; i++) path.lineTo(plan.paths[p][i][0], plan.paths[p][i][1]);
                        next[p] = run.strokes == null ? new GestureDescription.StrokeDescription(path, 0, plan.durations[index], more)
                                : run.strokes[p].continueStroke(path, 0, plan.durations[index], more);
                        builder.addStroke(next[p]);
                    }
                    cancellation.dispatched();
                    CompletableFuture<Boolean> completion = new CompletableFuture<>();
                    if (!service.dispatchGesture(builder.build(), gestureCallback(completion), main))
                        throw new ActionException("action_rejected", "Android rejected the gesture; observe before retrying");
                    cancellation.track("gesture", UUID.randomUUID().toString(), completion);
                    if (index == 0 && more) cancellation.track("held_pointers", UUID.randomUUID().toString(), run.lifted);
                    if (!more) completion.whenComplete((value, failure) -> run.lifted.complete(null));
                    run.service = service; run.strokes = next; run.completion = completion;
                    run.held = more; run.endpoints = new float[next.length][2];
                    for (int p = 0; p < next.length; p++) run.endpoints[p] = plan.paths[p][last];
                    if (index == 0) invalidateSnapshot();
                    return null;
                });
                if (!await(run.completion, UI_TIMEOUT_MS, cancellation)) {
                    run.held = false;
                    run.lifted.complete(null);
                    throw new ActionException("gesture_cancelled", "Android cancelled the gesture; observe before retrying");
                }
            }
            cancellation.check();
            return receipt(action, "accessibility").put("motion", plan.motion).put("pointerCount", plan.paths.length)
                    .put("plannedDurationMs", plan.durationMs).put("segments", plan.durations.length);
        } catch (Exception error) {
            try { releaseHeldGesture(run, cancellation); }
            catch (Exception releaseError) {
                ActionException failure = new ActionException("gesture_release_failed",
                        "Gesture stopped but pointer release could not be confirmed; observe before continuing", releaseError);
                failure.addSuppressed(error);
                throw failure;
            }
            throw error;
        }
    }

    private static AccessibilityService.GestureResultCallback gestureCallback(CompletableFuture<Boolean> completion) {
        return new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { completion.complete(true); }
            @Override public void onCancelled(GestureDescription description) { completion.complete(false); }
        };
    }

    private void releaseHeldGesture(GestureRun run, Cancellation original) throws Exception {
        // Cancellation revokes further movement. Let the bounded in-flight segment
        // finish, then lift only the existing pointers at their final positions.
        boolean interrupted = Thread.interrupted();
        Cancellation cleanup = () -> {};
        try {
            // onMain can stop waiting while its callback is already dispatching.
            // Queue behind that callback before inspecting its recorded pointers.
            onMain(cleanup, () -> null);
            if (run.completion == null || !run.held) return;
            if (!await(run.completion, 1000, cleanup)) { run.held = false; run.lifted.complete(null); return; }
            CompletableFuture<Boolean> released = new CompletableFuture<>();
            original.track("gesture_release", UUID.randomUUID().toString(), released);
            onMain(cleanup, () -> {
                GestureDescription.Builder builder = new GestureDescription.Builder();
                for (int p = 0; p < run.strokes.length; p++) {
                    Path path = new Path(); path.moveTo(run.endpoints[p][0], run.endpoints[p][1]);
                    builder.addStroke(run.strokes[p].continueStroke(path, 0, 1, false));
                }
                if (!run.service.dispatchGesture(builder.build(), gestureCallback(released), main))
                    throw new ActionException("gesture_release_failed", "Unable to release held gesture; task outcome is unknown");
                return null;
            });
            if (!await(released, 1000, cleanup))
                throw new ActionException("gesture_release_failed", "Gesture release was cancelled; task outcome is unknown");
            run.held = false;
            run.lifted.complete(null);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static final class GestureRun {
        final CompletableFuture<Void> lifted = new CompletableFuture<>();
        DroyAccessibilityService service;
        GestureDescription.StrokeDescription[] strokes;
        CompletableFuture<Boolean> completion;
        float[][] endpoints;
        volatile boolean held;
    }

    private JSONObject gesture(JSONObject args, boolean swipe, Cancellation cancellation) throws Exception {
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        boolean hold = !swipe && args.has("holdMs");
        boolean[] holdSubmitted = {false};
        long[] holdEndsAt = {0};
        try {
            onMain(cancellation, () -> {
                DroyAccessibilityService service = requireService(cancellation);
                Rect screen = displayBounds();
                checkCoordinateSnapshot(args, screen, swipe, cancellation);
                int x = coordinate(args, swipe ? "startX" : "x", screen.width());
                int y = coordinate(args, swipe ? "startY" : "y", screen.height());
                int endX = swipe ? coordinate(args, "endX", screen.width()) : x;
                int endY = swipe ? coordinate(args, "endY", screen.height()) : y;
                int duration = swipe ? ActionValidation.duration(args.has("durationMs") ? args.get("durationMs") : 300)
                        : args.has("holdMs") ? ActionValidation.holdDuration(args.get("holdMs")) : 70;
                Path path = new Path();
                path.moveTo(x, y);
                if (swipe) path.lineTo(endX, endY);
                GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
                requireService(cancellation);
                cancellation.dispatched();
                boolean submitted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) { completed.complete(true); }
                    @Override public void onCancelled(GestureDescription description) { completed.complete(false); }
                }, main);
                if (submitted) cancellation.track("gesture", UUID.randomUUID().toString(), completed);
                if (hold && submitted) {
                    holdSubmitted[0] = true;
                    holdEndsAt[0] = SystemClock.elapsedRealtime() + duration;
                }
                invalidateSnapshot();
                if (!submitted) throw new ActionException("action_rejected", "Android rejected the gesture");
                return null;
            });
            if (!await(completed, UI_TIMEOUT_MS, cancellation)) {
                throw new ActionException("gesture_cancelled", "Android cancelled the gesture; observe before trying again");
            }
            requireService(cancellation);
            JSONObject result = receipt(swipe ? "swipe" : "tap", "accessibility");
            if (args.has("holdMs")) result.put("holdMs", args.getInt("holdMs")).put("pointerCount", 1);
            return result;
        } finally {
            if (hold && !completed.isDone()) finishSubmittedHold(completed, holdSubmitted, holdEndsAt);
        }
    }

    private void finishSubmittedHold(CompletableFuture<Boolean> completed, boolean[] submitted, long[] endsAt) throws Exception {
        boolean interrupted = Thread.interrupted();
        Cancellation cleanup = () -> {};
        try {
            // A cancelled onMain waiter can leave its callback already running.
            // Drain that callback before inspecting whether a finger is down.
            onMain(cleanup, () -> null);
            if (submitted[0] && !completed.isDone())
                await(completed, Math.max(1, endsAt[0] - SystemClock.elapsedRealtime()) + 1000, cleanup);
        } catch (Exception error) {
            throw new ActionException("gesture_release_failed", "The coordinate hold may still be in progress; verify state before continuing", error)
                    .withDetails(new JSONObject().put("dispatched", submitted[0]).put("outcomeUnknown", true));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private JSONObject screenshot(Cancellation cancellation) throws Exception {
        CompletableFuture<AccessibilityService.ScreenshotResult> completed = new CompletableFuture<>();
        CompletableFuture<Void> settled = new CompletableFuture<>();
        AccessibilityService.ScreenshotResult captured = null;
        CaptureStamp basis = onMain(cancellation, () -> prepareScreenshotStamp(null, cancellation));
        try {
            onMain(cancellation, () -> {
                DroyAccessibilityService service = requireService(cancellation);
                cancellation.track("screenshot_callback", UUID.randomUUID().toString(), settled);
                try { service.takeScreenshot(Display.DEFAULT_DISPLAY, main::post, new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        try { if (!completed.complete(result)) result.getHardwareBuffer().close(); }
                        finally { settled.complete(null); }
                    }
                    @Override public void onFailure(int errorCode) {
                        String code = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                                ? "secure_screen" : errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                                ? "accessibility_unavailable" : errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                ? "screenshot_rate_limited" : "screenshot_failed";
                        completed.completeExceptionally(new ActionException(code,
                                "Android screenshot failed (code " + errorCode + "); protected content requires user takeover"));
                        settled.complete(null);
                    }
                }); } catch (RuntimeException failure) { settled.complete(null); throw failure; }
                return null;
            });
            captured = await(completed, UI_TIMEOUT_MS, cancellation);
            requireService(cancellation);
            Bitmap hardware = Bitmap.wrapHardwareBuffer(captured.getHardwareBuffer(), captured.getColorSpace());
            if (hardware == null) throw new ActionException("screenshot_failed", "Android returned an unusable screenshot buffer");
            Bitmap bitmap = null;
            try {
                bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                if (bitmap == null) throw new ActionException("screenshot_failed", "Screenshot conversion failed");
                return attachScreenshotBasis(encodeScreenshot(bitmap, "accessibility", cancellation),
                        bitmap.getWidth(), bitmap.getHeight(), basis, cancellation);
            } finally {
                if (bitmap != null) bitmap.recycle();
                hardware.recycle();
            }
        } finally {
            completed.cancel(false);
            if (captured != null) {
                captured.getHardwareBuffer().close();
            } else if (completed.isDone() && !completed.isCompletedExceptionally() && !completed.isCancelled()) {
                AccessibilityService.ScreenshotResult unused = completed.getNow(null);
                if (unused != null) unused.getHardwareBuffer().close();
            }
        }
    }

    private JSONObject executeShell(String action, JSONObject args, boolean rootEnabled, String channel,
                                   Cancellation cancellation) throws Exception {
        ActionValidation.rootAllowed(channel.equals("shizuku") || rootEnabled, action);
        if (channel.equals("shizuku")) cancellation.usingRoot(shizuku.connect(cancellation).uid() == 0);
        if (args.has("nodeId")) throw new ActionException("invalid_argument", "Root input accepts explicit coordinates only");
        Rect screen = displayBounds();
        String rootForeground = null;
        if (action.equals("tap") || action.equals("swipe")) {
            Snapshot basis = onMain(cancellation, () -> checkCoordinateSnapshot(args, screen, action.equals("swipe"), cancellation));
            if (basis.rootBasis) {
                rootForeground = shellForeground(channel, cancellation);
                if (!basis.packageName.equals(rootForeground)) throw new ActionException("stale_snapshot", "Foreground app changed; capture or observe again");
            }
        } else if (action.equals("screenshot") && DroyAccessibilityService.current(context) == null) {
            rootForeground = shellForeground(channel, cancellation);
        }
        final String foregroundBeforeCapture = rootForeground;
        CaptureStamp captureBasis = action.equals("screenshot")
                ? onMain(cancellation, () -> prepareScreenshotStamp(foregroundBeforeCapture, cancellation)) : null;
        String command;
        switch (action) {
            case "tap": command = "/system/bin/input tap " + coordinate(args, "x", screen.width()) + " " + coordinate(args, "y", screen.height()); break;
            case "swipe": command = "/system/bin/input swipe " + coordinate(args, "startX", screen.width()) + " "
                    + coordinate(args, "startY", screen.height()) + " " + coordinate(args, "endX", screen.width()) + " "
                    + coordinate(args, "endY", screen.height()) + " "
                    + ActionValidation.duration(args.has("durationMs") ? args.get("durationMs") : 300); break;
            case "back": command = "/system/bin/input keyevent 4"; break;
            case "screenshot": command = "/system/bin/screencap -p"; break;
            default: throw new ActionException("unsupported_root_action", "Unsupported root action");
        }
        cancellation.check();
        if (action.equals("tap") || action.equals("swipe")) {
            // The root foreground read can take time. Recheck generation and geometry
            // immediately before invalidating the basis and dispatching input.
            onMain(cancellation, () -> checkCoordinateSnapshot(args, displayBounds(), action.equals("swipe"), cancellation));
        }
        // Once a process starts, input may take effect even if its result is lost.
        // A failed process start leaves the validated basis available to another backend.
        if (channel.equals("shizuku") && !action.equals("screenshot")) DroyAccessibilityService.invalidateSnapshots();
        CommandRunner.Result result;
        try {
            // Authorization is checked in the same su invocation that performs the fixed operation.
            if (!action.equals("screenshot")) cancellation.dispatched();
            result = channel.equals("shizuku") ? shizuku.run(action, args, cancellation)
                    : CommandRunner.run(List.of("su", "-c", "[ \"$(/system/bin/id -u)\" = 0 ] || exit 77; exec " + command),
                        action.equals("screenshot") ? MAX_PNG_BYTES : 8192, 15000, cancellation,
                        () -> { if (!action.equals("screenshot")) DroyAccessibilityService.invalidateSnapshots(); });
        } catch (IOException error) {
            if (!channel.equals("root")) throw error;
            rootState = "unavailable";
            throw new ActionException("root_unavailable", "The su command is unavailable or cannot be started", error);
        } catch (Exception error) {
            if (channel.equals("root")) rootState = "not_verified";
            throw error;
        }
        if (result.exitCode != 0) {
            if (channel.equals("root")) rootState = "denied_or_failed";
            throw new ActionException(channel + "_denied_or_failed", "The " + channel + " operation was denied or failed (exit " + result.exitCode + ")");
        }
        if (channel.equals("root")) rootState = "last_command_succeeded";
        cancellation.check();
        if (action.equals("screenshot")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(result.stdout, 0, result.stdout.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 8192 || options.outHeight > 8192
                    || (long) options.outWidth * options.outHeight > 16000000) {
                throw new ActionException("screenshot_failed", "Root did not return a valid bounded image");
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(result.stdout, 0, result.stdout.length);
            if (bitmap == null) throw new ActionException("screenshot_failed", "Root screenshot could not be decoded");
            try {
                JSONObject captured = encodeScreenshot(bitmap, channel, cancellation)
                        .put("secureContent", "Protected surfaces may be blank; root capture cannot confirm whether Android masked a surface");
                return attachScreenshotBasis(captured, bitmap.getWidth(), bitmap.getHeight(), captureBasis, cancellation);
            } finally {
                bitmap.recycle();
            }
        }
        onMain(cancellation, () -> { invalidateSnapshot(); return null; });
        return receipt(action, channel);
    }

    private JSONObject encodeScreenshot(Bitmap bitmap, String channel, Cancellation cancellation) throws Exception {
        cancellation.check();
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width > 8192 || height > 8192 || (long) width * height > 16000000) {
            throw new ActionException("screenshot_too_large", "Screenshot exceeds the mobile PNG decoder's dimension limit");
        }
        // Wide-gamut screenshots retain their color space when copied. Render into sRGB
        // before PNG encoding because the mobile decoder intentionally does not apply ICC profiles.
        Bitmap srgb = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true,
                ColorSpace.get(ColorSpace.Named.SRGB));
        try {
            new Canvas(srgb).drawBitmap(bitmap, 0, 0, null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!srgb.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new ActionException("screenshot_failed", "Screenshot encoding failed");
            }
            if (output.size() > MAX_PNG_BYTES) throw new ActionException("screenshot_too_large", "Screenshot exceeds 16 MiB");
            byte[] png = output.toByteArray();
            if (png.length < 29 || png[24] != 8 || (png[25] != 2 && png[25] != 6)
                    || png[26] != 0 || png[27] != 0 || png[28] != 0) {
                throw new ActionException("screenshot_encoding_unsupported", "Android did not encode an 8-bit RGB/RGBA non-interlaced PNG");
            }
            cancellation.check();
            JSONObject result = new JSONObject().put("status", "captured").put("channel", channel)
                    .put("mimeType", "image/png").put("width", width).put("height", height)
                    .put("colorSpace", "sRGB").put("bitDepth", 8)
                    .put("base64", Base64.encodeToString(png, Base64.NO_WRAP));
            return result;
        } finally {
            srgb.recycle();
        }
    }

    private JSONObject receipt(String action, String channel) throws Exception {
        return new JSONObject().put("status", "action_completed").put("action", action).put("channel", channel)
                .put("goalVerified", false).put("verificationRequired", "Observe the resulting screen before reporting task success");
    }

    private Snapshot checkCoordinateSnapshot(JSONObject args, Rect screen, boolean swipe, Cancellation cancellation) throws Exception {
        return checkCoordinateSnapshot(args, screen, swipe, null, cancellation);
    }

    private Snapshot checkCoordinateSnapshot(JSONObject args, Rect screen, boolean swipe, Rect plannedBounds, Cancellation cancellation) throws Exception {
        cancellation.check();
        Snapshot saved = snapshot;
        String expected = args.opt("snapshotId") instanceof String ? args.getString("snapshotId") : null;
        DroyAccessibilityService service = DroyAccessibilityService.current(context);
        boolean contentOnly = false;
        boolean outsideRestrictedBasis = saved != null && saved.contentBasisOnly;
        SavedNode target = null;
        if (saved != null && !saved.rootBasis && service != null
                && saved.contentBounds.equals(service.coordinateContentBounds())) {
            int x = 0, y = 0;
            Rect path = plannedBounds;
            if (path == null) {
                x = coordinate(args, swipe ? "startX" : "x", screen.width());
                y = coordinate(args, swipe ? "startY" : "y", screen.height());
                int endX = swipe ? coordinate(args, "endX", screen.width()) : x;
                int endY = swipe ? coordinate(args, "endY", screen.height()) : y;
                path = new Rect(Math.min(x, endX), Math.min(y, endY), Math.max(x, endX) + 1, Math.max(y, endY) + 1);
            }
            contentOnly = saved.contentBounds.contains(path);
            outsideRestrictedBasis &= !contentOnly;
            if (contentOnly && !swipe && saved.coordinateContextSignature != null) target = coordinateTarget(saved, x, y);
        }
        SnapshotGuard.check(expected, saved == null ? null : saved.id,
                saved == null ? -1 : target != null ? saved.hardGeneration : contentOnly ? saved.contentGeneration : saved.generation,
                target != null ? DroyAccessibilityService.hardGeneration()
                        : contentOnly ? DroyAccessibilityService.contentGeneration() : DroyAccessibilityService.generation(),
                saved == null ? -1 : saved.createdMs, SystemClock.elapsedRealtime());
        if (!saved.screen.equals(screen)) throw new ActionException("stale_snapshot", "Display geometry changed; capture or observe again");
        if (outsideRestrictedBasis)
            throw stale("coordinate_outside_basis", "This screenshot only supports the unchanged app content region; capture again before targeting system bars or crossing its bounds");
        if (service == null) {
            if (!saved.rootBasis) throw new ActionException("stale_snapshot", "Accessibility was disconnected; capture again before root input");
            return saved;
        }
        AccessibilityNodeInfo currentRoot = service.getRootInActiveContentWindow();
        try {
            service.syncActiveWindow(currentRoot);
            if (currentRoot == null || !saved.packageName.equals(string(currentRoot.getPackageName()))
                    || saved.windowId != currentRoot.getWindowId()) {
                throw new ActionException("stale_snapshot", "Foreground window changed; capture or observe again");
            }
            if (target != null && !saved.coordinateContextSignature.equals(contentSignature(currentRoot)))
                throw stale("page_context_changed", "Page text, controls or layout changed; observe again before coordinate input");
        } finally { if (currentRoot != null) currentRoot.recycle(); }
        if (target != null) {
            // Animation events alone do not revoke a known button. Page semantics
            // (including amounts outside it) and the original button must match.
            Rect bounds = refreshOriginalNode(saved, target);
            if (target.node.isPassword()) throw new ActionException("protected_field", "Password fields require user takeover");
            int x = coordinate(args, "x", screen.width()), y = coordinate(args, "y", screen.height());
            // The explicit touch is bound to this exact point. A large parent
            // extending under a system bar does not cover an interior touch.
            checkNodeUnobscured(service, target.node, new Rect(x, y, x + 1, y + 1), cancellation);
            refreshOriginalNode(saved, target);
            SnapshotGuard.check(expected, snapshot == null ? null : snapshot.id, saved.hardGeneration,
                    DroyAccessibilityService.hardGeneration(), saved.createdMs, SystemClock.elapsedRealtime());
        }
        return saved;
    }

    private static SavedNode coordinateTarget(Snapshot saved, int x, int y) {
        SavedNode selected = null;
        long smallest = Long.MAX_VALUE;
        boolean ambiguous = false;
        for (SavedNode candidate : saved.nodes.values()) {
            if (!candidate.clickable || !candidate.bounds.contains(x, y)) continue;
            long area = (long) candidate.bounds.width() * candidate.bounds.height();
            if (area < smallest) { selected = candidate; smallest = area; ambiguous = false; }
            else if (area == smallest) ambiguous = true;
        }
        return ambiguous ? null : selected;
    }

    private CaptureStamp prepareScreenshotStamp(String rootPackage, Cancellation cancellation) throws Exception {
        CaptureStamp available = captureStamp(rootPackage);
        if (available.rootBasis) return available;
        JSONObject observed = observe(cancellation);
        CaptureStamp aligned = captureStamp(rootPackage);
        aligned.observationId = observed.getString("snapshotId");
        return aligned;
    }

    private CaptureStamp captureStamp(String rootPackage) throws Exception {
        DroyAccessibilityService service = DroyAccessibilityService.current(context);
        AccessibilityNodeInfo root = service == null ? null : service.getRootInActiveContentWindow();
        try {
            return new CaptureStamp(DroyAccessibilityService.generation(), DroyAccessibilityService.contentGeneration(),
                    DroyAccessibilityService.hardGeneration(), SystemClock.elapsedRealtime(), displayBounds(),
                    root == null ? new Rect() : service.coordinateContentBounds(),
                    root == null ? rootPackage : string(root.getPackageName()), root == null ? -1 : root.getWindowId(), root == null);
        } finally { if (root != null) root.recycle(); }
    }

    private JSONObject attachScreenshotBasis(JSONObject result, int width, int height, CaptureStamp stamp,
                                             Cancellation cancellation) throws Exception {
        String shellPackage = stamp.rootBasis && stamp.packageName != null
                ? shellForeground(result.optString("channel", "root"), cancellation) : null;
        return onMain(cancellation, () -> {
            Rect screen = displayBounds();
            DroyAccessibilityService service = DroyAccessibilityService.current(context);
            AccessibilityNodeInfo root = service == null ? null : service.getRootInActiveContentWindow();
            try {
                String packageName = root == null ? stamp.packageName : string(root.getPackageName());
                Rect contentBounds = root == null ? new Rect() : service.coordinateContentBounds();
                Snapshot observed = stamp.observationId == null ? null : snapshot;
                boolean observationChanged = stamp.observationId != null && (observed == null
                        || !stamp.observationId.equals(observed.id) || observed.observation == null
                        || observed.contentGeneration != stamp.contentGeneration || observed.hardGeneration != stamp.hardGeneration
                        || observed.windowId != stamp.windowId || !java.util.Objects.equals(observed.packageName, stamp.packageName)
                        || !observed.screen.equals(stamp.screen) || !observed.contentBounds.equals(stamp.contentBounds));
                long basisCreatedMs = observed != null && !observationChanged ? Math.min(stamp.createdMs, observed.createdMs) : stamp.createdMs;
                boolean expired = SystemClock.elapsedRealtime() - basisCreatedMs > SnapshotGuard.MAX_AGE_MS;
                boolean geometryChanged = screen.width() != width || screen.height() != height || !stamp.screen.equals(screen);
                boolean fullChanged = stamp.generation != DroyAccessibilityService.generation();
                boolean contentChanged = stamp.contentGeneration != DroyAccessibilityService.contentGeneration();
                boolean hardChanged = stamp.hardGeneration != DroyAccessibilityService.hardGeneration();
                boolean packageChanged = !java.util.Objects.equals(packageName, stamp.packageName)
                        || shellPackage != null && !shellPackage.equals(stamp.packageName);
                boolean windowChanged = stamp.windowId != (root == null ? -1 : root.getWindowId());
                boolean basisChanged = stamp.rootBasis != (root == null);
                boolean regionChanged = !stamp.contentBounds.equals(contentBounds);
                boolean foregroundUnknown = packageName == null || packageName.isEmpty();
                result.put("coordinateBasisChanges", new JSONObject().put("fullGeneration", fullChanged)
                        .put("contentGeneration", contentChanged).put("hardGeneration", hardChanged)
                        .put("package", packageChanged).put("window", windowChanged).put("accessibilityBasis", basisChanged)
                        .put("contentBounds", regionChanged).put("displayGeometry", geometryChanged)
                        .put("expired", expired).put("foregroundUnknown", foregroundUnknown).put("observationBasis", observationChanged));
                String reason = expired ? "capture_basis_expired" : geometryChanged ? "display_geometry_mismatch"
                        : foregroundUnknown ? "foreground_unknown"
                        : shellPackage != null && packageChanged ? "foreground_changed_during_capture"
                        : packageChanged || windowChanged || basisChanged || hardChanged || contentChanged || regionChanged
                            ? "screen_changed_during_capture" : observationChanged ? "observation_basis_changed_during_capture" : null;
                if (reason != null) return result.put("coordinateBasisStatus", "unavailable").put("coordinateBasisReason", reason);
                // Only a proven unchanged app region can survive a full-frame
                // change. The resulting basis must never authorize bar input.
                boolean contentBasisOnly = fullChanged && root != null && !contentBounds.isEmpty();
                if (fullChanged && !contentBasisOnly)
                    return result.put("coordinateBasisStatus", "unavailable").put("coordinateBasisReason", "screen_changed_during_capture");
                Snapshot captured = observed;
                if (captured == null) {
                    invalidateSnapshot();
                    captured = new Snapshot(UUID.randomUUID().toString(), DroyAccessibilityService.generation(), DroyAccessibilityService.hardGeneration(),
                            stamp.createdMs, root == null ? -1 : root.getWindowId(), packageName, screen, root == null, contentBounds);
                    snapshot = captured;
                }
                captured.contentBasisOnly = contentBasisOnly;
                result.put("snapshotId", captured.id).put("snapshotMaxAgeMs", SnapshotGuard.MAX_AGE_MS)
                        .put("coordinateSnapshotMaxAgeMs", SnapshotGuard.MAX_AGE_MS).put("nodeBasisPolicy", "refresh_original_node")
                        .put("packageName", packageName).put("coordinateBasisStatus", "available")
                        .put("coordinateBasisScope", contentBasisOnly ? "app_content" : "screen")
                        .put("coordinateBounds", bounds(contentBasisOnly ? contentBounds : screen))
                        .put("coordinateBasis", root == null ? result.optString("channel") + "_foreground_geometry_age"
                                : contentBasisOnly ? "accessibility_content_region_generation_geometry_age"
                                : "accessibility_window_generation_geometry_age");
                if (stamp.observationId != null) {
                    // Keep the very same SavedNode handles and original age.
                    // No second observation may silently rebind these node IDs.
                    JSONObject observation = new JSONObject(captured.observation.toString())
                            .put("coordinateBasisScope", contentBasisOnly ? "app_content" : "screen")
                            .put("coordinateBounds", bounds(contentBasisOnly ? contentBounds : screen));
                    captured.observation = new JSONObject(observation.toString());
                    result.put("afterObservation", observation);
                }
                if (root == null) result.put("coordinateBasisLimit", "Without accessibility, same-app content changes cannot be detected; use the image immediately");
                return result;
            } finally { if (root != null) root.recycle(); }
        });
    }

    private String shellForeground(String channel, Cancellation cancellation) throws Exception {
        // Only this fixed read command is accepted. No app- or model-supplied shell text is interpolated.
        CommandRunner.Result result = channel.equals("shizuku") ? shizuku.run("foreground", new JSONObject(), cancellation)
                : CommandRunner.run(List.of("su", "-c",
                "[ \"$(/system/bin/id -u)\" = 0 ] || exit 77; /system/bin/dumpsys window | /system/bin/grep -m 1 mCurrentFocus"),
                8192, 5000, cancellation);
        if (result.exitCode != 0) throw new ActionException("foreground_unavailable", "The selected backend could not verify the current foreground window");
        String focus = new String(result.stdout, java.nio.charset.StandardCharsets.UTF_8);
        Matcher match = Pattern.compile("\\s([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/").matcher(focus);
        if (!match.find()) throw new ActionException("foreground_unavailable", "The foreground package could not be read; unlock or open an app first");
        return match.group(1);
    }

    private Rect displayBounds() throws ActionException {
        WindowManager manager = context.getSystemService(WindowManager.class);
        if (manager == null) throw new ActionException("display_unavailable", "Display is unavailable");
        return manager.getMaximumWindowMetrics().getBounds();
    }

    private int coordinate(JSONObject args, String name, int limit) throws Exception {
        return ActionValidation.coordinate(args.opt(name), name, limit);
    }

    private void invalidateSnapshot() {
        if (snapshot != null) snapshot.recycle();
        snapshot = null;
        DroyAccessibilityService.invalidateSnapshots();
    }

    private <T> T onMain(Cancellation cancellation, Callable<T> action) throws Exception {
        cancellation.check();
        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture<Void> settled = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger dispatch = new java.util.concurrent.atomic.AtomicInteger();
        cancellation.track("main_callback", UUID.randomUUID().toString(), settled);
        Runnable operation = () -> {
            if (!dispatch.compareAndSet(0, 1)) return;
            try {
                if (future.isDone()) return;
                cancellation.check();
                future.complete(action.call());
            } catch (Exception error) {
                future.completeExceptionally(error);
            } finally { dispatch.set(2); settled.complete(null); }
        };
        if (!main.post(operation)) { settled.complete(null); throw new ActionException("main_thread_unavailable", "Android main thread is unavailable"); }
        try {
            return await(future, UI_TIMEOUT_MS, cancellation);
        } finally {
            future.cancel(false);
            main.removeCallbacks(operation);
            if (dispatch.compareAndSet(0, 2)) settled.complete(null);
        }
    }

    private static <T> T await(CompletableFuture<T> future, long timeoutMs, Cancellation cancellation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + cancellation.remainingMillis(timeoutMs);
        while (true) {
            cancellation.check();
            long remaining = cancellation.remainingMillis(Math.max(0, deadline - SystemClock.elapsedRealtime()));
            if (remaining <= 0) throw new ActionException("action_timeout", "Android action timed out; observe to determine whether it took effect");
            try {
                return future.get(Math.min(remaining, 50), TimeUnit.MILLISECONDS);
            } catch (TimeoutException retry) {
                // A bounded wait also polls cancellation while Android delivers a callback.
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IllegalStateException(cause);
            }
        }
    }

    private static JSONObject bounds(Rect rect) throws Exception {
        return new JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom);
    }

    private static ActionException stale(String reason, String message) { return new ActionException("stale_snapshot", reason, message); }

    private static String nodeValue(AccessibilityNodeInfo node) {
        return node.isEditable() && node.isShowingHintText() ? "" : string(node.getText());
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String signature(AccessibilityNodeInfo node) {
        return string(node.getClassName()) + '\0' + node.getViewIdResourceName() + '\0'
                + (node.isPassword() ? "" : stableLabel(node, nodeValue(node))) + '\0'
                + (node.isPassword() ? "" : stableLabel(node, string(node.getContentDescription()))) + '\0'
                + node.isClickable() + ':' + node.isEditable() + ':' + node.isPassword() + ':'
                + node.isChecked() + ':' + node.isSelected() + ':' + node.isEnabled() + ':'
                + node.isLongClickable() + ':' + node.isScrollable() + ':' + node.isCheckable();
    }

    private static String stableLabel(AccessibilityNodeInfo node, String text) {
        return node.isEditable() ? text : NodeSemantics.stableCountdownText(text);
    }

    private static boolean countdownLabel(AccessibilityNodeInfo node) {
        return !node.isEditable() && !node.isClickable() && !node.isPassword()
                && (!nodeValue(node).equals(stableLabel(node, nodeValue(node)))
                    || !string(node.getContentDescription()).equals(stableLabel(node, string(node.getContentDescription()))));
    }

    private static String contentSignature(AccessibilityNodeInfo root) {
        return captureContent(root, false).signature;
    }

    private static ContentCapture captureContent(AccessibilityNodeInfo root, boolean allowFunction) {
        if (allowFunction) {
            String direct = wrappingFunctionAnchor(root);
            if (direct != null) return new ContentCapture("", direct);
        }
        Rect rootBounds = new Rect(); root.getBoundsInScreen(rootBounds);
        long rootArea = (long) rootBounds.width() * rootBounds.height();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        StringBuilder result = new StringBuilder();
        int visited = 0;
        try {
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    if (++visited > MAX_VISITED) return new ContentCapture(null, null);
                    // Parent refresh does not refresh cached descendants. This
                    // single traversal supplies both item content and role anchors.
                    if (!node.refresh()) return new ContentCapture(null, null);
                    if (!node.isVisibleToUser()) continue;
                    Rect region = new Rect(); node.getBoundsInScreen(region);
                    if (allowFunction && (long) region.width() * region.height() * 2 >= rootArea) {
                        String anchor = semanticAnchor(node);
                        if (anchor != null) return new ContentCapture("", anchor);
                    }
                    // Countdown glyph widths can change inside a fixed button.
                    // Keep the button geometry and every non-timer child exact.
                    result.append(signature(node)).append('\0')
                            .append(countdownLabel(node) && rootBounds.contains(region) ? "countdown-label" : region.toShortString())
                            .append('\n');
                    if (result.length() > MAX_TEXT_CHARS || node.getChildCount() > MAX_VISITED - visited - pending.size())
                        return new ContentCapture(null, null);
                    for (int index = 0; index < node.getChildCount(); index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            return new ContentCapture(result.toString(), null);
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
    }

    private static final class ContentCapture {
        final String signature, functionAnchor;
        ContentCapture(String signature, String functionAnchor) { this.signature = signature; this.functionAnchor = functionAnchor; }
    }

    private static String bounded(CharSequence value, int limit) {
        if (value == null || limit <= 0) return "";
        return value.subSequence(0, Math.min(value.length(), limit)).toString();
    }

    /** One fresh tree read supplies the visible result and every saved subtree signature. */
    private static final class ObservationTree {
        final java.util.ArrayList<ReadNode> nodes = new java.util.ArrayList<>();
        boolean truncated;
        ObservationTree(AccessibilityNodeInfo root, Cancellation cancellation) throws Exception {
            nodes.add(new ReadNode(root, null, 0));
            try {
                for (int index = 0; index < nodes.size(); index++) {
                    cancellation.check();
                    ReadNode current = nodes.get(index);
                    current.refreshed = current.node.refresh();
                    current.node.getBoundsInScreen(current.bounds);
                    current.signature = signature(current.node);
                    current.anchor = semanticAnchor(current.node);
                    int children = current.node.getChildCount();
                    int remaining = MAX_VISITED - nodes.size();
                    current.complete = children <= remaining;
                    truncated |= !current.complete;
                    for (int childIndex = 0; childIndex < Math.min(children, remaining); childIndex++) {
                        AccessibilityNodeInfo child = current.node.getChild(childIndex);
                        if (child == null) continue;
                        ReadNode next = new ReadNode(child, current, current.depth + 1);
                        current.children.add(next); nodes.add(next);
                    }
                }
            } catch (Exception error) { close(); throw error; }
        }
        ContentCapture capture(ReadNode root, boolean allowFunction) {
            if (allowFunction) {
                String anchor = root.anchor;
                if (anchor == null && root.parent != null && root.parent.node.isVisibleToUser()) {
                    Rect parentBounds = root.parent.bounds;
                    if (parentBounds.contains(root.bounds) && (long) parentBounds.width() * parentBounds.height()
                            <= (long) root.bounds.width() * root.bounds.height() * 2) anchor = root.parent.anchor;
                }
                if (anchor != null) return new ContentCapture("", anchor);
            }
            ArrayDeque<ReadNode> pending = new ArrayDeque<>(); pending.add(root);
            StringBuilder result = new StringBuilder();
            long rootArea = (long) root.bounds.width() * root.bounds.height();
            while (!pending.isEmpty()) {
                ReadNode current = pending.removeFirst();
                if (!current.refreshed) return new ContentCapture(null, null);
                if (!current.node.isVisibleToUser()) continue;
                if (allowFunction && (long) current.bounds.width() * current.bounds.height() * 2 >= rootArea
                        && current.anchor != null) return new ContentCapture("", current.anchor);
                result.append(current.signature).append('\0')
                        .append(countdownLabel(current.node) && root.bounds.contains(current.bounds)
                                ? "countdown-label" : current.bounds.toShortString()).append('\n');
                if (!current.complete || result.length() > MAX_TEXT_CHARS) return new ContentCapture(null, null);
                pending.addAll(current.children);
            }
            return new ContentCapture(result.toString(), null);
        }
        String navigationKey(ReadNode root) {
            AccessibilityNodeInfo node = root.node;
            if (node.isPassword() || node.isEditable()) return "";
            String direct = NavigationTarget.key(nodeValue(node), string(node.getContentDescription()), node.getViewIdResourceName());
            if (direct.startsWith("label:") || node.getChildCount() == 0) return direct;
            java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
            ArrayDeque<ReadNode> pending = new ArrayDeque<>(root.children);
            int visited = 0;
            while (!pending.isEmpty()) {
                ReadNode current = pending.removeFirst(); AccessibilityNodeInfo child = current.node;
                if (++visited > 64 || !current.refreshed) return "";
                if (!child.isVisibleToUser() || child.isPassword() || child.isEditable()) continue;
                if (child.isClickable() && !current.bounds.equals(root.bounds)) continue;
                String label = NavigationTarget.key(nodeValue(child), string(child.getContentDescription()), "");
                if (label.startsWith("label:")) labels.add(label.substring(6));
                if (!current.complete || current.children.size() > 64 - visited - pending.size()) return "";
                pending.addAll(current.children);
            }
            return labels.isEmpty() ? direct : "label:" + String.join("\u001f", labels);
        }
        void close() { for (ReadNode node : nodes) node.node.recycle(); }
    }
    private static final class ReadNode {
        final AccessibilityNodeInfo node;
        final ReadNode parent;
        final int depth;
        final Rect bounds = new Rect();
        final java.util.ArrayList<ReadNode> children = new java.util.ArrayList<>();
        boolean refreshed, complete;
        String signature, anchor;
        ReadNode(AccessibilityNodeInfo node, ReadNode parent, int depth) {
            this.node = node; this.parent = parent; this.depth = depth;
        }
    }
    private static final class CaptureStamp {
        final long generation, contentGeneration, hardGeneration, createdMs;
        final Rect screen, contentBounds;
        final String packageName;
        final int windowId;
        final boolean rootBasis;
        String observationId;
        CaptureStamp(long generation, long contentGeneration, long hardGeneration, long createdMs, Rect screen, Rect contentBounds,
                     String packageName, int windowId, boolean rootBasis) {
            this.generation = generation; this.contentGeneration = contentGeneration; this.hardGeneration = hardGeneration;
            this.createdMs = createdMs; this.screen = new Rect(screen); this.contentBounds = new Rect(contentBounds);
            this.packageName = packageName; this.windowId = windowId; this.rootBasis = rootBasis;
        }
    }
    private static String functionAnchor(AccessibilityNodeInfo target) {
        return captureContent(target, true).functionAnchor;
    }

    private static String wrappingFunctionAnchor(AccessibilityNodeInfo target) {
        String own = semanticAnchor(target);
        if (own != null) return own;
        Rect bounds = new Rect(); target.getBoundsInScreen(bounds);
        // The wrapper may label the region while its inner clickable surface
        // has no description. It must describe the same small region.
        AccessibilityNodeInfo parent = target.getParent();
        try {
            if (parent != null && parent.isVisibleToUser()) {
                Rect region = new Rect(); parent.getBoundsInScreen(region);
                long area = (long) region.width() * region.height();
                long targetArea = (long) bounds.width() * bounds.height();
                if (region.contains(bounds) && area <= targetArea * 2) return semanticAnchor(parent);
            }
            return null;
        } finally { if (parent != null) parent.recycle(); }
    }

    private static String semanticAnchor(AccessibilityNodeInfo node) {
        if (!NodeSemantics.searchEntry(string(node.getClassName()), node.getViewIdResourceName(), string(node.getContentDescription()))) return null;
        Rect region = new Rect(); node.getBoundsInScreen(region);
        return string(node.getClassName()) + '\0' + node.getViewIdResourceName() + '\0'
                + string(node.getContentDescription()) + '\0' + region.toShortString();
    }

    private static final class SavedNode {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final String signature;
        final SavedNode context;
        final boolean capturesContent;
        final String contentSignature;
        final String functionAnchor;
        final boolean clickable;
        final String navigationKey;
        final int navigationClarity;
        SavedNode(ReadNode read, SavedNode context, ObservationTree tree) {
            AccessibilityNodeInfo node = read.node;
            this.node = AccessibilityNodeInfo.obtain(node);
            this.bounds = new Rect(read.bounds);
            this.signature = signature(node);
            this.clickable = node.isClickable();
            String key = tree.navigationKey(read);
            this.navigationKey = key.isEmpty() && tree.truncated ? navigationKey(node) : key;
            this.navigationClarity = !string(node.getContentDescription()).isEmpty() ? 2 : !nodeValue(node).isEmpty() ? 1 : 0;
            this.context = context;
            // Capture the target's visible meaning, including descendants of a
            // clickable card, and any explicitly associated collection item.
            this.capturesContent = node.getChildCount() > 0 && (node.isClickable() || node.isLongClickable()
                    || node.isEditable() || node.getCollectionItemInfo() != null);
            boolean allowFunction = context == null && node.getCollectionItemInfo() == null;
            ContentCapture captured = capturesContent ? tree.capture(read, allowFunction)
                    : new ContentCapture("", null);
            // The full-page read has its own bound. A small target subtree may
            // still be complete even when unrelated page content exhausted it.
            if (capturesContent && captured.signature == null && tree.truncated)
                captured = captureContent(node, allowFunction);
            this.functionAnchor = captured.functionAnchor;
            this.contentSignature = captured.signature;
        }
    }
    private static final class Snapshot {
        final String id;
        final long generation;
        final long contentGeneration;
        final long hardGeneration;
        final long createdMs;
        final int windowId;
        final String packageName;
        final Rect screen;
        final Rect contentBounds;
        final boolean rootBasis;
        boolean contentBasisOnly;
        final Map<String, SavedNode> nodes = new HashMap<>();
        String coordinateContextSignature;
        JSONObject observation;
        String navigationPage = "";
        final Map<AccessibilityNodeInfo, String> navigationHeaders = new HashMap<>();
        Snapshot(String id, long generation, long hardGeneration, long createdMs, int windowId, String packageName, Rect screen, boolean rootBasis, Rect contentBounds) {
            this.id = id; this.generation = generation; this.hardGeneration = hardGeneration; this.createdMs = createdMs;
            this.windowId = windowId; this.packageName = packageName;
            this.screen = new Rect(screen); this.rootBasis = rootBasis;
            this.contentGeneration = DroyAccessibilityService.contentGeneration(); this.contentBounds = new Rect(contentBounds);
        }
        void recycle() { for (SavedNode node : nodes.values()) node.node.recycle(); nodes.clear(); navigationHeaders.clear(); }
    }
}

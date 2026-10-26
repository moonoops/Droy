package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes a short declared plan; every device action still passes through ProductBridge. */
final class ActionSequence {
    interface Host {
        void check() throws Exception;
        long now();
        JSONObject basis(String snapshotId) throws Exception;
        JSONObject observe() throws Exception;
        JSONObject execute(String action, JSONObject args, int step) throws Exception;
        JSONObject failure(Exception error) throws Exception;
        void save(JSONObject metadata) throws Exception;
    }

    private static final Set<String> ACTIONS = Set.of("tap", "input_text", "set_selected", "back", "wait_for");
    private static final Set<String> TARGET_KEYS = Set.of("nodeId", "text", "description", "resourceId", "role");
    private static final Set<String> STEP_KEYS = Set.of("action", "target", "text", "selected", "navigation",
            "afterCondition", "afterTimeoutMs", "terminal");
    private final JSONObject request;
    private final List<JSONObject> steps = new ArrayList<>();
    private static final class ReceiptPersistenceFailure extends Exception {
        ReceiptPersistenceFailure(Exception cause) { super("Sequence receipt was not persisted; inspect the device before continuing", cause); }
    }
    private static void persist(Host host, JSONObject metadata) throws ReceiptPersistenceFailure {
        try { host.save(metadata); }
        catch (Exception failure) { throw new ReceiptPersistenceFailure(failure); }
    }

    ActionSequence(JSONObject request) throws Exception {
        this.request = new JSONObject(request.toString());
        keys(request, Set.of("snapshotId", "packageName", "steps", "timeoutMs"));
        requiredText(request, "snapshotId"); requiredText(request, "packageName");
        JSONArray raw = request.getJSONArray("steps");
        if (raw.length() < 2 || raw.length() > 6) throw invalid("A sequence requires 2 to 6 steps");
        if (request.has("timeoutMs")) integer(request, "timeoutMs", 1, 60000);
        for (int index = 0; index < raw.length(); index++) {
            JSONObject step = raw.getJSONObject(index); keys(step, STEP_KEYS);
            String action = requiredText(step, "action");
            if (!ACTIONS.contains(action)) throw invalid("Unsupported sequence action: " + action);
            if (step.has("terminal") && !(step.get("terminal") instanceof Boolean)) throw invalid("terminal must be boolean");
            if (step.optBoolean("terminal") && index != raw.length() - 1) throw invalid("A terminal action must be last");
            if (step.has("navigation") && !(step.get("navigation") instanceof Boolean)) throw invalid("navigation must be boolean");
            if (Set.of("tap", "input_text", "set_selected").contains(action)) validateTarget(step.getJSONObject("target"));
            else if (step.has("target")) throw invalid("This action does not use a target");
            if (action.equals("input_text") && !(step.get("text") instanceof String)) throw invalid("text must be a string");
            if (action.equals("set_selected") && !(step.get("selected") instanceof Boolean)) throw invalid("selected must be boolean");
            if (step.has("afterTimeoutMs")) integer(step, "afterTimeoutMs", 0, 60000);
            if (action.equals("wait_for") && !step.has("afterCondition")) throw invalid("wait_for needs afterCondition");
            if (step.has("afterCondition")) validateCondition(step.getJSONObject("afterCondition"));
            steps.add(new JSONObject(step.toString()));
        }
    }

    JSONObject run(Host host) throws Exception {
        long started = host.now();
        JSONArray completed = new JSONArray();
        JSONObject journal = new JSONObject().put("status", "running").put("completed", completed)
                .put("completedSteps", 0).put("dispatched", false).put("outcomeUnknown", false);
        persist(host, journal);
        JSONObject current = null;
        boolean dispatched = false;
        boolean resultNotPersisted = false;
        int index = 0;
        try {
            host.check();
            JSONObject initial = host.basis(request.getString("snapshotId"));
            checkPackage(initial); current = initial;
            // Resolve all initial node references before any action consumes the snapshot.
            List<JSONObject> originals = new ArrayList<>();
            for (JSONObject step : steps) {
                JSONObject target = step.optJSONObject("target");
                originals.add(target != null && target.has("nodeId") ? nodeById(initial, target.getString("nodeId")) : null);
            }
            for (; index < steps.size(); index++) {
                host.check(); checkPackage(current);
                JSONObject step = steps.get(index);
                String action = step.getString("action");
                JSONObject target = step.optJSONObject("target");
                JSONObject node = target == null ? null : resolve(current, target, originals.get(index));
                JSONObject args = new JSONObject().put("afterObserve", true).put("channel", "accessibility");
                if (node != null) args.put("snapshotId", current.getString("snapshotId")).put("nodeId", node.getString("nodeId"));
                for (String key : new String[]{"text", "navigation", "afterCondition", "afterTimeoutMs"})
                    if (step.has(key) && (!key.equals("afterTimeoutMs") || step.has("afterCondition"))) args.put(key, step.get(key));
                boolean selection = action.equals("set_selected");
                boolean selectionReadable = false;
                if (selection) {
                    Boolean state = selectionState(node);
                    selectionReadable = state != null;
                    boolean desired = step.getBoolean("selected");
                    if (state != null && state == desired && !step.has("afterCondition")) {
                        completed.put(new JSONObject().put("step", index).put("action", action).put("status", "already_selected_state")
                                .put("selected", desired).put("dispatched", false));
                        journal.put("completedSteps", completed.length()); persist(host, journal); continue;
                    }
                    if (state == null && (!desired || !step.has("afterCondition")))
                        throw new ActionException("selection_unverifiable", "The target does not expose its selection state; a known unselected target and afterCondition are required");
                    if (state == null && conditionMatches(current, step.getJSONObject("afterCondition")))
                        throw new ActionException("selection_unverifiable", "The count condition already holds but this target's selection is unknown; do not toggle it");
                    if (state == null && !oneMoreSelected(current, step.getJSONObject("afterCondition")))
                        throw new ActionException("selection_unverifiable", "An opaque selection requires the declared selection counter to increase by exactly one");
                    action = state != null && state == desired ? "wait_for" : "tap";
                }
                if (action.equals("wait_for")) {
                    args = new JSONObject().put("channel", "accessibility").put("condition", step.getJSONObject("afterCondition"))
                            .put("timeoutMs", step.optInt("afterTimeoutMs", 2000));
                }
                journal.put("inFlightStep", index).put("inFlightAction", step.getString("action"));
                persist(host, journal);
                JSONObject receipt = host.execute(action, args, index);
                boolean stepDispatched = wasDispatched(receipt); dispatched |= stepDispatched;
                resultNotPersisted = stepDispatched;
                JSONObject after = observation(receipt);
                if (after != null) current = after;
                String status = receipt.optString("status");
                boolean conditionMatched = action.equals("wait_for") ? status.equals("matched")
                        : !step.has("afterCondition") || receipt.optString("afterConditionStatus").equals("matched");
                if (!conditionMatched || status.equals("timed_out"))
                    throw new ActionException("condition_not_matched", "The action was not repeated; inspect the returned state")
                            .withDetails(new JSONObject().put("dispatched", stepDispatched).put("outcomeUnknown", stepDispatched)
                                    .put("currentObservation", current));
                if (action.equals("input_text") && !receipt.optBoolean("inputTextVerified"))
                    throw new ActionException("input_not_verified", "Text was not verified; no following step was dispatched")
                            .withDetails(new JSONObject().put("dispatched", stepDispatched).put("outcomeUnknown", stepDispatched));
                if (selection && selectionReadable) {
                    // Observe-only verification never retries the selection click.
                    current = awaitSelection(host, current, node, step.getBoolean("selected"), step.optInt("afterTimeoutMs", 2000));
                } else if (selection) {
                    current = host.basis(current.getString("snapshotId"));
                    resolveIdentity(current, node, false);
                }
                JSONObject item = compactReceipt(index, step.getString("action"), receipt);
                if (selection) item.put("selectionVerified", selectionReadable).put("selected", step.getBoolean("selected"))
                        .put("selectionCountVerified", !selectionReadable)
                        .put("selectionVerification", selectionReadable ? "target_state" : "count_transition");
                completed.put(item);
                journal.remove("inFlightStep"); journal.remove("inFlightAction");
                journal.put("completedSteps", completed.length()).put("dispatched", dispatched);
                persist(host, journal); resultNotPersisted = false;
                if (index + 1 < steps.size()) {
                    if (current == null || !current.has("snapshotId")) current = host.observe();
                    current = host.basis(current.getString("snapshotId"));
                }
            }
            journal.put("status", "sequence_completed").put("goalVerified", false);
            persist(host, journal);
            return result(journal, current, host.now() - started);
        } catch (ReceiptPersistenceFailure failure) {
            JSONObject details = result(journal, current, host.now() - started)
                    .put("dispatched", dispatched).put("outcomeUnknown", resultNotPersisted);
            // Preserve the last durable in-flight marker. A failed receipt write
            // must never be replaced by a success-shaped catch result.
            throw new ActionException("sequence_receipt_failed", failure.getMessage(), failure).withDetails(details);
        } catch (Exception error) {
            JSONObject failure = host.failure(error);
            JSONObject fresh = failure.optJSONObject("currentObservation");
            if (fresh != null) { current = fresh; failure.put("currentObservation", publicObservation(fresh)); }
            boolean unknown = failure.optBoolean("outcomeUnknown") || resultNotPersisted;
            failure.put("outcomeUnknown", unknown);
            if (resultNotPersisted) failure.put("dispatched", true);
            dispatched |= failure.optBoolean("dispatched");
            journal.put("status", "sequence_stopped").put("failedStep", index).put("dispatched", dispatched)
                    .put("outcomeUnknown", unknown).put("goalVerified", false)
                    .put("error", new JSONObject().put("code", failure.optString("code", "sequence_failure"))
                            .put("reason", failure.optString("reason")));
            journal.remove("inFlightStep"); journal.remove("inFlightAction");
            persist(host, journal);
            return result(journal, current, host.now() - started).put("error", failure);
        }
    }

    private JSONObject awaitSelection(Host host, JSONObject current, JSONObject target, boolean desired, long timeout) throws Exception {
        long deadline = host.now() + timeout;
        while (true) {
            host.check(); checkPackage(current);
            JSONObject enriched = host.basis(current.getString("snapshotId"));
            JSONObject found = resolveIdentity(enriched, target, true);
            Boolean selected = found.optBoolean("checkable") ? found.optBoolean("checked")
                    : target.optBoolean("selected") || found.optBoolean("selected") ? found.optBoolean("selected") : null;
            if (selected != null && selected == desired) return enriched;
            if (host.now() >= deadline) throw new ActionException("selection_not_confirmed", "Selection did not reach the requested state; do not repeat the click")
                    .withDetails(new JSONObject().put("dispatched", true).put("outcomeUnknown", true).put("currentObservation", current));
            Thread.sleep(Math.min(80, Math.max(1, deadline - host.now())));
            current = host.observe();
        }
    }

    private void checkPackage(JSONObject observation) throws Exception {
        if (observation == null || !request.getString("packageName").equals(observation.optString("packageName")))
            throw new ActionException("sequence_page_changed", "The sequence left its declared app; inspect the new page before continuing");
    }

    static JSONObject resumed(JSONObject saved) throws Exception {
        JSONObject result = new JSONObject(saved.toString());
        boolean interrupted = result.optString("status").equals("running");
        if (interrupted) result.put("status", "sequence_interrupted").put("outcomeUnknown", result.has("inFlightStep"));
        return result.put("replayedAction", false).put("needsObservation", true).put("existingReceipt", true).put("goalVerified", false);
    }

    private static JSONObject result(JSONObject journal, JSONObject current, long elapsed) throws Exception {
        JSONObject result = new JSONObject(journal.toString()).put("elapsedMs", elapsed).put("replayedAction", false);
        if (current != null) result.put("afterObservation", publicObservation(current));
        return result;
    }

    static JSONObject publicObservation(JSONObject observation) throws Exception {
        JSONObject value = new JSONObject(observation.toString());
        JSONArray nodes = value.optJSONArray("nodes");
        if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i); node.remove("sequenceIdentity"); node.remove("sequenceContextIdentity");
        }
        return value;
    }

    private static JSONObject compactReceipt(int step, String action, JSONObject receipt) throws Exception {
        JSONObject out = new JSONObject().put("step", step).put("action", action).put("status", receipt.optString("status"))
                .put("dispatched", wasDispatched(receipt));
        for (String key : new String[]{"inputTextVerified", "afterConditionStatus", "afterConditionElapsedMs", "nativeTiming"})
            if (receipt.has(key)) out.put(key, receipt.get(key));
        return out;
    }

    private static boolean wasDispatched(JSONObject result) {
        JSONObject timing = result.optJSONObject("nativeTiming");
        return result.optBoolean("dispatched") || timing != null && timing.has("dispatchedAtMs");
    }

    private static JSONObject observation(JSONObject result) {
        JSONObject after = result.optJSONObject("afterObservation");
        return after == null ? result.optJSONObject("observation") : after;
    }

    private static Boolean selectionState(JSONObject node) {
        if (node.optBoolean("checkable")) return node.optBoolean("checked");
        return node.optBoolean("selected") ? Boolean.TRUE : null;
    }

    static JSONObject resolve(JSONObject observation, JSONObject target, JSONObject original) throws Exception {
        if (original != null) return resolveIdentity(observation, original, true);
        JSONObject found = null; JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index); boolean matches = true;
            for (String key : TARGET_KEYS) if (!key.equals("nodeId") && target.has(key))
                matches &= target.getString(key).equals(node.optString(key));
            if (!matches) continue;
            if (found != null) throw new ActionException("sequence_target_ambiguous", "The target matches more than one current node");
            found = node;
        }
        if (found == null) throw new ActionException("sequence_target_missing", "The declared target is not present; no following action was dispatched");
        return found;
    }

    private static JSONObject resolveIdentity(JSONObject observation, JSONObject original, boolean semantics) throws Exception {
        String identity = original.optString("sequenceIdentity");
        if (identity.isEmpty()) throw new ActionException("sequence_basis_unavailable", "Initial node identity is unavailable");
        JSONObject found = null; JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (!identity.equals(node.optString("sequenceIdentity"))) continue;
            boolean match = true;
            for (String key : new String[]{"className", "resourceId", "sequenceContextIdentity"})
                match &= original.optString(key).equals(node.optString(key));
            JSONObject before = original.optJSONObject("bounds"), after = node.optJSONObject("bounds");
            match &= before != null && after != null;
            if (before != null && after != null) for (String edge : new String[]{"left", "top", "right", "bottom"})
                match &= before.optInt(edge, Integer.MIN_VALUE) == after.optInt(edge, Integer.MAX_VALUE);
            if (semantics) for (String key : new String[]{"text", "description", "role"})
                match &= original.optString(key).equals(node.optString(key));
            if (!match) throw new ActionException("sequence_target_changed", "The original target moved or changed meaning; inspect the current page");
            if (found != null) throw new ActionException("sequence_target_ambiguous", "The original target has multiple current matches");
            found = node;
        }
        if (found == null) throw new ActionException("sequence_target_changed", "The original target is no longer present");
        return found;
    }

    private static JSONObject nodeById(JSONObject observation, String nodeId) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (nodeId.equals(node.optString("nodeId"))) return new JSONObject(node.toString());
        }
        throw new ActionException("invalid_node", "nodeId is not present in the initial snapshot");
    }

    private static void validateTarget(JSONObject target) throws Exception {
        keys(target, TARGET_KEYS);
        if (target.length() == 0) throw invalid("A target is required");
        if (target.has("nodeId") && target.length() != 1) throw invalid("Use nodeId or semantic fields, not both");
        for (java.util.Iterator<String> keys = target.keys(); keys.hasNext();) requiredText(target, keys.next());
    }

    private static void validateCondition(JSONObject condition) throws Exception {
        keys(condition, Set.of("packageName", "text", "resourceId", "editable", "clickable", "state"));
        int count = 0;
        for (String key : new String[]{"text", "resourceId", "editable", "clickable"}) if (condition.has(key)) count++;
        if (count > 1 || count == 0 && !condition.has("packageName")) throw invalid("A condition needs packageName and/or one selector");
        for (String key : new String[]{"packageName", "text", "resourceId"}) if (condition.has(key)) requiredText(condition, key);
        for (String key : new String[]{"editable", "clickable"}) if (condition.has(key) && !(condition.get(key) instanceof Boolean))
            throw invalid(key + " must be boolean");
        if (condition.has("state") && !Set.of("present", "absent").contains(condition.getString("state"))) throw invalid("Invalid condition state");
    }

    private static boolean conditionMatches(JSONObject observation, JSONObject condition) throws Exception {
        if (condition.has("packageName") && !condition.getString("packageName").equals(observation.optString("packageName"))) return false;
        boolean found = !condition.has("text") && !condition.has("resourceId") && !condition.has("editable") && !condition.has("clickable");
        JSONArray nodes = observation.optJSONArray("nodes");
        if (nodes != null) for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index); boolean matches = true;
            if (condition.has("text")) matches &= (node.optString("text") + "\n" + node.optString("description")).contains(condition.getString("text"));
            if (condition.has("resourceId")) matches &= condition.getString("resourceId").equals(node.optString("resourceId"));
            for (String key : new String[]{"editable", "clickable"}) if (condition.has(key)) matches &= condition.getBoolean(key) == node.optBoolean(key);
            found |= matches;
        }
        return condition.optString("state", "present").equals("absent") ? !found && !observation.optBoolean("truncated") : found;
    }

    private static boolean oneMoreSelected(JSONObject observation, JSONObject condition) throws Exception {
        if (!condition.has("text") || condition.optString("state", "present").equals("absent")) return false;
        String expected = condition.getString("text").replaceAll("\\s+", "");
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("^([^0-9]*)([0-9]+)([^0-9]*)$").matcher(expected);
        if (!number.matches()) return false;
        int after;
        try { after = Integer.parseInt(number.group(2)); } catch (NumberFormatException tooLarge) { return false; }
        if (after < 1 || after > 1000) return false;
        String prefix = number.group(1), suffix = number.group(3);
        String zeroLabel = (prefix + suffix).replaceAll("[()（）\\[\\]]", "");
        if (zeroLabel.isBlank()) return false;
        Integer before = null;
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            for (String field : new String[]{"text", "description"}) {
                String text = node.optString(field).replaceAll("\\s+", "");
                Integer value = null;
                if (after == 1 && !zeroLabel.isEmpty() && zeroLabel.equals(text)) value = 0;
                else if (text.startsWith(prefix) && text.endsWith(suffix) && text.length() > prefix.length() + suffix.length()) {
                    String raw = text.substring(prefix.length(), text.length() - suffix.length());
                    if (raw.matches("[0-9]+")) try { value = Integer.parseInt(raw); } catch (NumberFormatException ignored) { }
                }
                if (value != null) {
                    if (before != null && !before.equals(value)) return false;
                    before = value;
                }
            }
        }
        return before != null && after == before + 1;
    }

    private static void keys(JSONObject value, Set<String> allowed) {
        for (java.util.Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!allowed.contains(key)) throw invalid("Unknown sequence field: " + key);
        }
    }
    private static String requiredText(JSONObject value, String key) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.isBlank()) throw invalid(key + " must be nonempty text");
        return (String) raw;
    }
    private static void integer(JSONObject value, String key, long min, long max) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < min || n.longValue() > max)
            throw invalid(key + " is outside its allowed integer range");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

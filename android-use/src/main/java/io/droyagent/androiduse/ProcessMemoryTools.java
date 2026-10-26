package io.droyagent.androiduse;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Scoped process data operations. Product policy supplies the trusted owner and cancellation. */
public final class ProcessMemoryTools {
    static final int MAX_CANDIDATES = 20000;
    private static volatile ProcessMemoryTools instance;
    private final MemoryBackend backend;
    private final TargetResolver resolver;
    private final MemoryReceiptStore receipts;
    private final AtomicLong epoch = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> ownerEpochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProcessReference> processes = new ConcurrentHashMap<>();
    private volatile boolean appsAllowed = true, readAllowed = true, writeAllowed = true, rootAllowed = true;

    public static ProcessMemoryTools get(Context context) {
        if (instance == null) synchronized (ProcessMemoryTools.class) {
            if (instance == null) {
                Context app = context.getApplicationContext();
                instance = new ProcessMemoryTools(new RootMemoryBackend(app), new AndroidTargets(app),
                    new File(app.getFilesDir(), "memory-receipts/state.json"));
            }
        }
        return instance;
    }

    ProcessMemoryTools(MemoryBackend backend, TargetResolver resolver, File receiptFile) {
        this.backend = backend; this.resolver = resolver; this.receipts = new MemoryReceiptStore(receiptFile);
    }

    public static boolean isLocal(String action, JSONObject args) {
        return action.equals("process_memory") && Set.of("status", "close").contains(args.optString("operation"))
            || action.equals("memory_scan") && args.optString("operation").equals("discard");
    }

    public JSONObject capabilities() throws Exception {
        JSONObject result = backend.capabilities();
        return result.put("supported", result.optBoolean("packaged", result.optBoolean("supported", false)))
            .put("reason", result.optBoolean("packaged", result.optBoolean("supported", false)) ? JSONObject.NULL : "memory_backend_missing")
            .put("rootRequired", true).put("targetAccess", "checked_on_open")
            .put("supportedTargetBits", 64).put("maxCandidates", MAX_CANDIDATES)
            .put("maxScalarWriteBytes", 8).put("atomicWrite", false).put("persistentFreeze", false)
            .put("dataPolicy", "Scan collections stay on the device; bounded returned values enter the current model context");
    }

    public JSONObject execute(String action, JSONObject args, String owner, String call, Cancellation cancellation) throws Exception {
        identity(owner, "task"); identity(call, "call");
        if (args == null || cancellation == null) throw invalid("Arguments and cancellation are required");
        for (String forbidden : Set.of("taskId", "callId", "uid", "pid", "startTime", "root", "command"))
            if (args.has(forbidden)) throw invalid("Process identity and privilege are not model parameters");
        if (!Set.of("process_memory", "memory_scan", "memory_read", "memory_write").contains(action))
            throw new ActionException("unsupported_action", "Unsupported process memory operation");
        Guard guard = new Guard(owner, cancellation);
        if (isLocal(action, args)) return action.equals("process_memory")
            ? process(action, args, owner, call, guard) : scan(args, owner, guard);
        checkGrants(action.equals("memory_write") || action.equals("process_memory")
            && args.optString("operation").equals("open") && args.optString("mode").equals("read_write"));
        guard.usingRoot(true);
        try {
            JSONObject result = switch (action) {
                case "process_memory" -> process(action, args, owner, call, guard);
                case "memory_scan" -> scan(args, owner, guard);
                case "memory_read" -> read(args, owner, guard);
                case "memory_write" -> write(args, owner, call, guard);
                default -> throw new AssertionError();
            };
            if (!action.equals("memory_write")) guard.check();
            return result;
        } catch (ActionException failure) {
            if (Set.of("memory_target_changed", "target_changed", "process_exited", "target_not_found").contains(failure.getCode())) {
                removeOwner(owner);
                throw new ActionException("memory_target_changed", "The target process instance changed; open a new session and locate the data again");
            }
            throw failure;
        }
    }

    private JSONObject process(String action, JSONObject args, String owner, String call, Guard guard) throws Exception {
        String operation = required(args, "operation");
        return switch (operation) {
            case "query" -> query(args, owner, guard);
            case "open" -> open(args, owner, guard);
            case "regions" -> regions(args, owner, guard);
            case "status" -> status(args, owner);
            case "close" -> close(args, owner);
            default -> throw invalid("Unsupported process memory operation");
        };
    }

    private JSONObject query(JSONObject args, String owner, Guard guard) throws Exception {
        fields(args, "operation", "packageName");
        Target target = resolver.resolve(required(args, "packageName"));
        JSONObject result = backend.request(new JSONObject().put("op", "query").put("uid", target.uid), guard, null);
        guard.check();
        processes.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
        JSONArray nativeProcesses = result.getJSONArray("processes"), items = new JSONArray();
        if (nativeProcesses.length() > 64) throw new ActionException("memory_process_limit", "Too many target processes");
        Map<String, ProcessReference> created = new LinkedHashMap<>();
        for (int i = 0; i < nativeProcesses.length(); i++) {
            JSONObject p = nativeProcesses.getJSONObject(i);
            if (p.getInt("uid") != target.uid) throw new ActionException("memory_backend_invalid", "Process ownership did not match the target");
            String ref = id();
            ProcessReference reference = new ProcessReference(owner, target, copy(p));
            created.put(ref, reference);
            items.put(publicProcess(p).put("processRef", ref));
        }
        guard.check();
        processes.putAll(created);
        try { guard.check(); }
        catch (Exception stopped) { for (Map.Entry<String, ProcessReference> entry : created.entrySet()) processes.remove(entry.getKey(), entry.getValue()); throw stopped; }
        return target.json().put("processes", items).put("scope", "specified_app_current_user");
    }

    private JSONObject open(JSONObject args, String owner, Guard guard) throws Exception {
        fields(args, "operation", "packageName", "processRef", "mode");
        String mode = args.has("mode") ? required(args, "mode") : "read_only";
        if (!Set.of("read_only", "read_write").contains(mode)) throw invalid("mode must be read_only or read_write");
        if (sessionForOwner(owner) != null) throw new ActionException("memory_session_exists", "Close the current target session before opening another");
        if (sessions.size() >= 8) throw new ActionException("memory_session_limit", "End an existing memory task before opening another");
        Target target = resolver.resolve(required(args, "packageName"));
        JSONObject process;
        if (args.has("processRef")) {
            ProcessReference ref = processes.get(required(args, "processRef"));
            if (ref == null) throw new ActionException("memory_reference_invalid", "Query the target processes again");
            if (!ref.owner.equals(owner)) throw ownerMismatch();
            if (!ref.target.same(target)) throw new ActionException("memory_target_changed", "The installed target changed");
            process = ref.process;
        } else {
            JSONObject found = backend.request(new JSONObject().put("op", "query").put("uid", target.uid), guard, null);
            JSONArray available = found.getJSONArray("processes");
            if (available.length() == 0) throw new ActionException("memory_target_not_running", "The specified application has no running process");
            process = null;
            for (int i = 0; i < available.length(); i++) {
                JSONObject candidate = available.getJSONObject(i);
                if (target.packageName.equals(candidate.optString("name"))) {
                    if (process != null) throw new ActionException("memory_process_ambiguous", "Query and select a specific target process");
                    process = candidate;
                }
            }
            if (process == null && available.length() == 1) process = available.getJSONObject(0);
            if (process == null) throw new ActionException("memory_process_ambiguous", "Query and select a specific target process");
        }
        if (process.getInt("uid") != target.uid) throw new ActionException("memory_target_changed", "Process UID did not match the specified application");
        if (process.optInt("bitness", 0) != 64) throw new ActionException("memory_target_unsupported", "The first release supports only 64-bit target processes");
        JSONObject request = new JSONObject().put("op", "inspect").put("uid", target.uid)
            .put("pid", process.getInt("pid")).put("startTime", process.getString("startTime")).put("mode", mode);
        JSONObject inspected = backend.request(request, guard, null);
        guard.check();
        if (inspected.getInt("uid") != target.uid || inspected.getInt("pid") != process.getInt("pid")
            || !inspected.getString("startTime").equals(process.getString("startTime")))
            throw new ActionException("memory_target_changed", "Target identity changed while opening");
        if (inspected.optInt("bitness") != 64 || inspected.optInt("pageSize") < 1)
            throw new ActionException("memory_target_unsupported", "The target ABI or page size is unsupported");
        Session session = new Session(owner, target, process, mode, inspected.getInt("pageSize"));
        session.setRegions(inspected.getJSONArray("regions"));
        sessions.put(session.id, session);
        try { guard.check(); }
        catch (Exception stopped) { session.closed = true; sessions.remove(session.id, session); throw stopped; }
        return target.json().put("sessionId", session.id).put("mode", mode).put("target", publicProcess(process))
            .put("readAccess", inspected.optBoolean("readAccess"))
            .put("writeEligibility", inspected.optString("writeEligibility", "not_requested"))
            .put("pageSize", session.pageSize).put("regionCount", session.regions.size()).put("atomicWrite", false);
    }

    private JSONObject regions(JSONObject args, String owner, Guard guard) throws Exception {
        fields(args, "operation", "sessionId", "cursor", "limit");
        Session session = session(required(args, "sessionId"), owner);
        guard.bind(session); validateTarget(session);
        if (!args.has("cursor")) {
            JSONObject result = backend.request(common(session, "regions"), guard, null);
            guard.check(); session.setRegions(result.getJSONArray("regions"));
        }
        int offset = cursor(args, session.regionGeneration), limit = limit(args);
        if (offset > session.regions.size()) throw invalid("Region cursor is outside this result");
        JSONArray items = new JSONArray();
        List<Region> values = new ArrayList<>(session.regions.values());
        for (int i = offset; i < Math.min(values.size(), offset + limit); i++) items.put(values.get(i).json());
        return new JSONObject().put("sessionId", session.id).put("items", items).put("total", values.size())
            .put("nextCursor", nextCursor(offset + items.length(), values.size(), session.regionGeneration));
    }

    private JSONObject status(JSONObject args, String owner) throws Exception {
        fields(args, "operation", "sessionId", "writeReceiptId");
        if (args.has("writeReceiptId")) {
            JSONObject receipt = receipts.find(owner, required(args, "writeReceiptId"));
            if (receipt == null) throw new ActionException("memory_reference_invalid", "No write receipt belongs to this task");
            return MemoryReceiptStore.publicReceipt(receipt);
        }
        JSONObject result = new JSONObject().put("receipts", receipts.forOwner(owner));
        if (args.has("sessionId")) {
            Session session = session(required(args, "sessionId"), owner);
            result.put("sessionId", session.id).put("mode", session.mode).put("target", session.target.json())
                .put("closed", session.closed).put("scanState", session.scan == null ? "none" : session.scan.state);
        }
        return result;
    }

    private JSONObject close(JSONObject args, String owner) throws Exception {
        fields(args, "operation", "sessionId");
        String sid = required(args, "sessionId");
        Session existing = sessions.get(sid);
        if (existing != null && !existing.owner.equals(owner)) throw ownerMismatch();
        if (existing != null) { existing.closed = true; sessions.remove(sid, existing); }
        return new JSONObject().put("status", "closed").put("sessionId", sid).put("alreadyClosed", existing == null);
    }

    private JSONObject scan(JSONObject args, String owner, Guard guard) throws Exception {
        String op = required(args, "operation");
        if (op.equals("discard")) {
            fields(args, "operation", "scanId");
            Session session = scanSession(required(args, "scanId"), owner);
            session.scan.cancelled = true; session.scan = null;
            return new JSONObject().put("status", "discarded");
        }
        if (op.equals("start")) {
            fields(args, "operation", "sessionId", "regionIds", "type", "value", "comparison", "absoluteTolerance");
            Session session = session(required(args, "sessionId"), owner);
            guard.bind(session); validateTarget(session);
            if (session.scan != null) throw new ActionException("memory_scan_exists", "Discard the existing scan before starting another");
            String type = MemoryValue.type(args.opt("type"));
            if (args.has("comparison") && !required(args, "comparison").equals("eq")) throw invalid("Initial scans require an exact known value");
            if (args.has("regionIds") && !(args.opt("regionIds") instanceof JSONArray))
                throw invalid("regionIds must be an array of region references");
            String hex = MemoryValue.hex(MemoryValue.encode(type, args.opt("value")));
            Double requestedTolerance = tolerance(args, type);
            if (!args.has("regionIds")) {
                JSONObject observed = backend.request(common(session, "regions"), guard, null);
                guard.check(); session.setRegions(observed.getJSONArray("regions"));
            }
            List<Region> selected = selectRegions(session, args.optJSONArray("regionIds"));
            Scan scan = new Scan(type, hex, selected, requestedTolerance);
            session.scan = scan; guard.bindScan(scan);
            return scanStep(session, scan, guard);
        }
        fields(args, "operation", "scanId", "comparison", "value", "absoluteTolerance", "cursor", "limit");
        Session session = scanSession(required(args, "scanId"), owner);
        guard.bind(session); validateTarget(session);
        Scan scan = session.scan; guard.bindScan(scan);
        if (op.equals("results")) {
            fields(args, "operation", "scanId", "cursor", "limit");
            return scanResults(session, scan, args);
        }
        if (op.equals("continue")) {
            fields(args, "operation", "scanId");
            if (!scan.state.equals("paused")) throw new ActionException("memory_scan_state", "Only a paused scan can continue");
            return scanStep(session, scan, guard);
        }
        if (!op.equals("refine")) throw invalid("Unsupported scan operation");
        fields(args, "operation", "scanId", "comparison", "value", "absoluteTolerance");
        if (!scan.state.equals("ready")) throw new ActionException("memory_scan_state", "Complete the current scan generation before refining");
        String comparison = required(args, "comparison");
        if (!Set.of("eq", "changed", "unchanged", "increased", "decreased").contains(comparison)) throw invalid("Unsupported scan comparison");
        if (scan.type.equals("bytes") && Set.of("increased", "decreased").contains(comparison)) throw invalid("Byte patterns have no numeric order");
        String hex = comparison.equals("eq") ? MemoryValue.hex(MemoryValue.encode(scan.type, args.opt("value"))) : "";
        if (!comparison.equals("eq") && args.has("value")) throw invalid("This comparison uses the previous scan values");
        if (scan.type.equals("bytes") && comparison.equals("eq") && hex.length() != scan.patternSize * 2)
            throw invalid("Refinement cannot change the byte pattern length");
        scan.beginRefine(comparison, hex, tolerance(args, scan.type));
        return scanStep(session, scan, guard);
    }

    private JSONObject scanStep(Session session, Scan scan, Guard guard) throws Exception {
        JSONObject request = common(session, scan.refining ? "refine" : "scan");
        request.put("regions", scan.regionArray()).put("type", scan.type).put("comparison", scan.comparison)
            .put("hex", scan.hex).put("maxCandidates", Math.max(1, MAX_CANDIDATES - scan.staging.size()));
        if (scan.tolerance != null) request.put("absoluteTolerance", scan.tolerance);
        if (scan.refining) {
            request.put("candidates", new JSONArray(scan.baseline)).put("cursorIndex", scan.nextIndex);
        } else if (scan.nativeCursor != null) request.put("cursor", scan.nativeCursor);
        try {
            JSONObject result = backend.request(request, guard, null);
            guard.check();
            JSONArray candidates = result.getJSONArray("candidates");
            JSONArray changed = result.optJSONArray("changedRegionIndices");
            if (changed != null) for (int i = 0; i < changed.length(); i++) {
                int index = changed.getInt(i);
                if (index < 0 || index >= scan.regions.size()) throw new ActionException("memory_backend_invalid", "Invalid changed region index");
                scan.changedRegionIndices.add(index);
            }
            Set<String> invalidRegions = new java.util.HashSet<>();
            for (int index : scan.changedRegionIndices) invalidRegions.add(scan.regions.get(index).id);
            session.reads.entrySet().removeIf(entry -> invalidRegions.contains(entry.getValue().region.id));
            int previouslyCollected = scan.staging.size();
            scan.staging.removeIf(candidate -> scan.changedRegionIndices.contains(candidate.optInt("regionIndex", -1)));
            int removed = previouslyCollected - scan.staging.size();
            if (removed > 0) {
                scan.invalidatedCandidates += removed;
                // Index-based references must not silently point at a different surviving candidate.
                scan.referenceGeneration++;
            }
            if (result.optBoolean("candidateOverflow")) {
                scan.fail();
                throw new ActionException("memory_candidate_limit", "Too many candidates; discard this scan and select a smaller region or more specific value");
            }
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = copy(candidates.getJSONObject(i));
                validateCandidate(scan, candidate);
                if (scan.changedRegionIndices.contains(candidate.getInt("regionIndex"))) {
                    scan.invalidatedCandidates++;
                    continue;
                }
                if (scan.staging.size() >= MAX_CANDIDATES) {
                    scan.fail();
                    throw new ActionException("memory_candidate_limit", "Too many candidates; select a smaller region or more specific value");
                }
                scan.staging.add(candidate);
            }
            scan.scannedBytes += result.optLong("scannedBytes");
            scan.unreadableBytes += result.optLong("unreadableBytes");
            scan.skippedBytes += result.optLong("skippedBytes");
            scan.skippedCandidates += result.optLong("skippedCandidates");
            scan.coverageIncomplete |= result.optBoolean("coverageIncomplete") || !scan.changedRegionIndices.isEmpty();
            boolean finished = result.getBoolean("finished");
            if (scan.refining) scan.nextIndex = result.optInt("nextIndex");
            else scan.nativeCursor = result.optJSONObject("cursor");
            if (finished) {
                scan.baseline = List.copyOf(scan.staging); scan.staging = new ArrayList<>(); scan.state = "ready";
                scan.coverageComplete = scan.sourceCoverageComplete && scan.unreadableBytes == 0 && !scan.coverageIncomplete;
                scan.refining = false;
            } else scan.state = "paused";
            return scanSummary(session, scan);
        } catch (Exception failure) {
            if (!scan.state.equals("failed")) scan.fail();
            throw failure;
        }
    }

    private JSONObject scanResults(Session session, Scan scan, JSONObject args) throws Exception {
        if (!Set.of("ready", "paused").contains(scan.state)) throw new ActionException("memory_scan_state", "This scan has no usable current result");
        List<JSONObject> values = scan.visible();
        int offset = cursor(args, scan.referenceGeneration), limit = limit(args);
        if (offset > values.size()) throw invalid("Candidate cursor is outside this result");
        JSONArray items = new JSONArray();
        for (int i = offset; i < Math.min(values.size(), offset + limit); i++) {
            JSONObject candidate = values.get(i);
            String hex = candidate.getString("hex");
            items.put(new JSONObject().put("candidateId", scan.id + "." + scan.referenceGeneration + "." + i)
                .put("address", candidate.getString("address"))
                .put("regionId", scan.regions.get(candidate.getInt("regionIndex")).id)
                .put("type", scan.type).put("value", MemoryValue.decode(scan.type, MemoryValue.unhex(hex), 0)));
        }
        return scanSummary(session, scan).put("items", items)
            .put("nextCursor", nextCursor(offset + items.length(), values.size(), scan.referenceGeneration));
    }

    private JSONObject read(JSONObject args, String owner, Guard guard) throws Exception {
        fields(args, "sessionId", "candidateId", "regionId", "offset", "type", "count");
        Session session = session(required(args, "sessionId"), owner);
        guard.bind(session); validateTarget(session);
        Region region;
        long address;
        String type;
        int defaultCount = 1;
        if (args.has("candidateId")) {
            if (args.has("regionId") || args.has("offset")) throw invalid("Use either a candidate or an explicit region location");
            Scan scan = session.scan;
            if (scan == null || !Set.of("ready", "paused").contains(scan.state)) throw new ActionException("memory_reference_invalid", "The candidate scan is no longer usable");
            String prefix = scan.id + "." + scan.referenceGeneration + ".";
            String ref = required(args, "candidateId");
            if (!ref.startsWith(prefix)) throw new ActionException("memory_reference_invalid", "Candidate belongs to another scan generation");
            int index = decimalIndex(ref.substring(prefix.length()));
            List<JSONObject> values = scan.visible();
            if (index >= values.size()) throw new ActionException("memory_reference_invalid", "Candidate no longer exists");
            JSONObject candidate = values.get(index);
            region = scan.regions.get(candidate.getInt("regionIndex"));
            address = MemoryValue.address(candidate.get("address")); type = scan.type;
            if (args.has("type")) {
                String requestedType = MemoryValue.type(args.opt("type"));
                if (!scan.type.equals("bytes") && !requestedType.equals(scan.type))
                    throw invalid("Only byte-pattern candidates may be explicitly re-read as a different scalar type");
                type = requestedType;
            }
            if (type.equals("bytes")) defaultCount = scan.patternSize;
        } else {
            region = session.region(required(args, "regionId"));
            long offset = MemoryValue.address(args.opt("offset"));
            try { address = Math.addExact(region.start, offset); }
            catch (ArithmeticException overflow) { throw invalid("Read offset overflow"); }
            type = MemoryValue.type(args.opt("type"));
        }
        int count = integer(args, "count", defaultCount, 1, type.equals("bytes") ? 256 : 64);
        int length = type.equals("bytes") ? count : Math.multiplyExact(count, MemoryValue.size(type));
        region.checkRange(address, length, false);
        JSONObject result = backend.request(common(session, "read").put("region", region.nativeRegion)
            .put("address", MemoryValue.address(address)).put("length", length), guard, null);
        guard.check();
        byte[] bytes = MemoryValue.unhex(result.getString("hex"));
        if (bytes.length != length || result.optInt("bytesRead", -1) != length) throw new ActionException("memory_read_incomplete", "The requested memory could not be completely read");
        if (session.reads.size() >= 128) throw new ActionException("memory_read_limit", "Close this session before creating more write references");
        Read read = new Read(region, address, type, bytes, count);
        session.reads.put(read.id, read);
        JSONArray values = decoded(type, bytes, count);
        return new JSONObject().put("sessionId", session.id).put("readId", read.id).put("type", type)
            .put("address", MemoryValue.address(address)).put("regionId", region.id).put("values", values)
            .put("bytesRead", bytes.length).put("sampledAtMs", System.currentTimeMillis())
            .put("writeEligible", session.mode.equals("read_write") && count == 1 && !type.equals("bytes") && region.writable());
    }

    private JSONObject write(JSONObject args, String owner, String call, Guard guard) throws Exception {
        fields(args, "sessionId", "readId", "value");
        String sessionId = required(args, "sessionId"), readId = required(args, "readId");
        String rawValue = stringValue(args.opt("value"));
        JSONObject previous = receipts.duplicate(owner, call, readId);
        if (previous != null) {
            String fp = fingerprint(sessionId, readId, previous.getString("valueType"), rawValue);
            if (!fp.equals(previous.getString("fingerprint"))) throw new ActionException("memory_request_conflict", "This call or read reference was already used for a different write request");
            return MemoryReceiptStore.publicReceipt(previous).put("duplicate", true);
        }
        Session session = session(sessionId, owner);
        guard.bind(session); validateTarget(session);
        if (!session.mode.equals("read_write")) throw new ActionException("memory_read_only", "This process memory session is read-only");
        Read read = session.reads.get(readId);
        if (read == null) throw new ActionException("memory_reference_invalid", "Read the target scalar again before writing");
        if (read.consumed) throw new ActionException("memory_reference_consumed", "This read reference has already been consumed");
        if (read.count != 1 || read.type.equals("bytes")) throw invalid("Only a single previously read scalar may be written");
        byte[] value = MemoryValue.encode(read.type, rawValue);
        read.region.checkRange(read.address, value.length, true);
        if (read.address % value.length != 0 || read.address / session.pageSize != (read.address + value.length - 1) / session.pageSize)
            throw invalid("Scalar writes must be naturally aligned and cannot cross a target page");
        resolvePriorHelpers(guard);
        String receiptId = id(), fp = fingerprint(sessionId, readId, read.type, rawValue);
        JSONObject[] receipt = {null};
        JSONObject request = common(session, "write").put("region", read.region.nativeRegion)
            .put("address", MemoryValue.address(read.address)).put("expectedHex", MemoryValue.hex(read.bytes))
            .put("valueHex", MemoryValue.hex(value));
        try {
            JSONObject result = backend.request(request, guard, helper -> {
                guard.check(); checkGrants(true);
                JSONObject pending = new JSONObject().put("writeReceiptId", receiptId).put("owner", owner).put("callId", call)
                    .put("readId", readId).put("fingerprint", fp).put("valueType", read.type).put("sessionId", sessionId)
                    .put("target", publicProcess(session.process)).put("helper", copy(helper))
                    .put("status", "pending").put("helperExited", false).put("writeResultKnown", false)
                    .put("bytesWritten", JSONObject.NULL).put("dispatched", false).put("createdAtMs", System.currentTimeMillis());
                receipts.save(pending); receipt[0] = pending; read.consumed = true;
                guard.dispatched();
                pending.put("dispatched", true); receipts.save(pending);
            });
            validateWriteReceipt(result, value);
            JSONObject finalReceipt = receipt[0] == null ? new JSONObject().put("writeReceiptId", receiptId)
                .put("owner", owner).put("callId", call).put("readId", readId).put("fingerprint", fp).put("valueType", read.type)
                .put("sessionId", sessionId).put("createdAtMs", System.currentTimeMillis()) : copy(receipt[0]);
            for (String key : Set.of("status", "reason", "dispatched", "bytesWritten", "writeResultKnown", "helperExited"))
                if (result.has(key)) finalReceipt.put(key, result.get(key));
            if (!result.has("status") || !result.has("writeResultKnown")) throw new ActionException("memory_backend_invalid", "The memory backend returned an incomplete write receipt");
            // A result returned before preparation is only legitimate when no write was attempted.
            if (receipt[0] == null && result.optBoolean("dispatched")) throw new ActionException("memory_backend_invalid", "The backend bypassed write preparation");
            if (receipt[0] == null) { read.consumed = true; finalReceipt.put("helperExited", result.optBoolean("helperExited", true)); }
            JSONObject readback = result.optJSONObject("readback");
            if (readback != null) {
                JSONObject publicReadback = copy(readback);
                if (publicReadback.has("hex")) {
                    byte[] after = MemoryValue.unhex(publicReadback.getString("hex"));
                    publicReadback.remove("hex");
                    if (after.length == value.length) publicReadback.put("values", decoded(read.type, after, 1));
                }
                // Persist only the readback state, never the process's bytes or scalar value.
                finalReceipt.put("readback", new JSONObject().put("status", publicReadback.optString("status", "unavailable")));
                receipts.save(finalReceipt);
                return MemoryReceiptStore.publicReceipt(finalReceipt).put("readback", publicReadback).put("goalVerified", false);
            }
            receipts.save(finalReceipt);
            return MemoryReceiptStore.publicReceipt(finalReceipt).put("goalVerified", false);
        } catch (Exception failure) {
            if (receipt[0] != null) {
                JSONObject unknown = copy(receipt[0]).put("status", "outcome_unknown").put("writeResultKnown", false)
                    .put("bytesWritten", JSONObject.NULL).put("helperExited", false).put("goalVerified", false);
                receipts.save(unknown);
                return MemoryReceiptStore.publicReceipt(unknown);
            }
            throw failure;
        }
    }

    private static void validateWriteReceipt(JSONObject result, byte[] requested) throws Exception {
        String state = result.optString("status");
        if (!Set.of("not_dispatched", "already_equal", "write_failed", "written_verified", "written_unverified", "partial_write", "outcome_unknown").contains(state))
            throw new ActionException("memory_backend_invalid", "Unknown native write result");
        if (!result.has("dispatched") || !result.has("writeResultKnown") || !result.has("bytesWritten"))
            throw new ActionException("memory_backend_invalid", "Incomplete native write result");
        boolean dispatched = result.getBoolean("dispatched"), known = result.getBoolean("writeResultKnown");
        int written = result.isNull("bytesWritten") ? -1 : result.getInt("bytesWritten");
        boolean valid = switch (state) {
            case "not_dispatched", "already_equal" -> !dispatched && known && written == 0;
            case "write_failed" -> dispatched && known && written == 0;
            case "written_verified", "written_unverified" -> dispatched && known && written == requested.length;
            case "partial_write" -> dispatched && known && written > 0 && written < requested.length;
            case "outcome_unknown" -> !known && written == -1;
            default -> false;
        };
        if (!valid) throw new ActionException("memory_backend_invalid", "Inconsistent native write result");
        if (state.equals("written_verified") || state.equals("already_equal")) {
            JSONObject after = result.optJSONObject("readback");
            if (after == null || !Arrays.equals(requested, MemoryValue.unhex(after.optString("hex"))))
                throw new ActionException("memory_backend_invalid", "Verified write did not contain a matching readback");
        }
    }

    private void resolvePriorHelpers(Guard guard) throws Exception {
        for (JSONObject previous : receipts.unresolvedHelpers()) {
            JSONObject helper = previous.optJSONObject("helper");
            if (helper == null) throw new ActionException("memory_write_blocked", "A previous memory executor has an unresolved identity");
            JSONObject state = backend.request(new JSONObject().put("op", "helper_status").put("helper", helper), guard, null);
            if (state.optBoolean("alive", true)) throw new ActionException("memory_write_blocked", "A previous memory write executor has not been confirmed stopped");
            previous.put("helperExited", true);
            if (previous.optString("status").equals("pending")) previous.put("status", "outcome_unknown");
            receipts.save(previous);
        }
    }

    public void cancel(String owner) { bumpOwner(owner); removeOwner(owner); }
    public void end(String owner) { cancel(owner); receipts.end(owner); }
    public void closeAll() {
        epoch.incrementAndGet();
        for (Session session : sessions.values()) session.closed = true;
        sessions.clear(); processes.clear();
    }
    public void onPermissionsChanged(boolean apps, boolean read, boolean write, boolean root) {
        appsAllowed = apps; readAllowed = read; writeAllowed = write; rootAllowed = root;
        if (!apps || !read || !root) closeAll();
        else if (!write) {
            for (Session session : sessions.values()) if (session.mode.equals("read_write")) {
                session.closed = true; sessions.remove(session.id, session); bumpOwner(session.owner);
            }
        }
    }

    private void removeOwner(String owner) {
        for (Session session : sessions.values()) if (session.owner.equals(owner)) { session.closed = true; sessions.remove(session.id, session); }
        processes.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
    }
    private void bumpOwner(String owner) { if (owner != null) ownerEpochs.computeIfAbsent(owner, key -> new AtomicLong()).incrementAndGet(); }
    private void checkGrants(boolean writing) throws ActionException {
        if (!appsAllowed || !readAllowed || !rootAllowed || writing && !writeAllowed)
            throw new ActionException("memory_permission_revoked", "The required process memory capability is disabled");
    }
    private Session session(String id, String owner) throws ActionException {
        Session session = sessions.get(id);
        if (session == null || session.closed) throw new ActionException("memory_reference_invalid", "Open a new process memory session");
        if (!session.owner.equals(owner)) throw ownerMismatch();
        return session;
    }
    private Session sessionForOwner(String owner) {
        for (Session session : sessions.values()) if (session.owner.equals(owner) && !session.closed) return session;
        return null;
    }
    private Session scanSession(String id, String owner) throws ActionException {
        for (Session session : sessions.values()) if (session.scan != null && session.scan.id.equals(id)) {
            if (!session.owner.equals(owner)) throw ownerMismatch();
            if (session.closed) break;
            return session;
        }
        throw new ActionException("memory_reference_invalid", "This scan no longer belongs to an active target session");
    }
    private void validateTarget(Session session) throws Exception {
        if (!session.target.same(resolver.resolve(session.target.packageName))) {
            session.closed = true; sessions.remove(session.id, session);
            throw new ActionException("memory_target_changed", "The installed target application changed");
        }
    }
    private static JSONObject common(Session session, String op) throws Exception {
        return new JSONObject().put("op", op).put("uid", session.target.uid).put("pid", session.process.getInt("pid"))
            .put("startTime", session.process.getString("startTime"));
    }
    private static JSONObject publicProcess(JSONObject process) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : Set.of("pid", "uid", "startTime", "name", "bitness")) if (process.has(key)) result.put(key, process.get(key));
        return result;
    }
    private static JSONObject scanSummary(Session session, Scan scan) throws Exception {
        return new JSONObject().put("sessionId", session.id).put("scanId", scan.id).put("generation", scan.generation)
            .put("referenceGeneration", scan.referenceGeneration).put("state", scan.state).put("candidateCount", scan.visible().size()).put("candidateCountExact", scan.state.equals("ready"))
            .put("coverageComplete", scan.state.equals("ready") && scan.coverageComplete).put("scannedBytes", scan.scannedBytes)
            .put("unreadableBytes", scan.unreadableBytes).put("skippedBytes", scan.skippedBytes)
            .put("skippedCandidates", scan.skippedCandidates).put("invalidatedCandidateCount", scan.invalidatedCandidates)
            .put("changedRegionCount", scan.changedRegionIndices.size())
            .put("sourceCoverageComplete", scan.sourceCoverageComplete)
            .put("coverageIncomplete", scan.coverageIncomplete || !scan.sourceCoverageComplete || scan.unreadableBytes != 0)
            .put("planFinished", scan.state.equals("ready"))
            .put("stopReason", scan.state.equals("paused") ? "work_budget" : scan.state.equals("failed") ? "failed" : "plan_finished")
            .put("meaning", "A unique candidate is not proof of a business field; partial coverage is not an exhaustive search");
    }
    private static void validateCandidate(Scan scan, JSONObject candidate) throws Exception {
        int regionIndex = candidate.getInt("regionIndex");
        if (regionIndex < 0 || regionIndex >= scan.regions.size()) throw new ActionException("memory_backend_invalid", "Invalid candidate region");
        byte[] bytes = MemoryValue.unhex(candidate.getString("hex"));
        int size = scan.type.equals("bytes") ? scan.patternSize : MemoryValue.size(scan.type);
        if (bytes.length != size) throw new ActionException("memory_backend_invalid", "Invalid candidate scalar length");
        scan.regions.get(regionIndex).checkRange(MemoryValue.address(candidate.get("address")), bytes.length, false);
    }
    private static List<Region> selectRegions(Session session, JSONArray ids) throws Exception {
        List<Region> result = new ArrayList<>();
        if (ids == null) {
            for (Region region : session.regions.values()) if (region.scannable()) result.add(region);
        } else {
            if (ids.length() < 1 || ids.length() > 64) throw invalid("Choose between 1 and 64 memory regions");
            Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                if (!seen.add(id)) throw invalid("Duplicate memory region");
                Region region = session.region(id);
                if (!region.scannable()) throw new ActionException("memory_region_not_allowed", "Scans require readable private non-executable data mappings");
                result.add(region);
            }
        }
        if (result.isEmpty()) throw new ActionException("memory_region_not_available", "No eligible data region was found");
        result.sort(java.util.Comparator.comparingLong(region -> region.start));
        return List.copyOf(result);
    }
    private static JSONArray decoded(String type, byte[] bytes, int count) throws Exception {
        JSONArray values = new JSONArray();
        if (type.equals("bytes")) return values.put(MemoryValue.hex(bytes));
        for (int i = 0; i < count; i++) values.put(MemoryValue.decode(type, bytes, i * MemoryValue.size(type)));
        return values;
    }
    private static Double tolerance(JSONObject args, String type) throws Exception {
        if (!args.has("absoluteTolerance")) return null;
        if (!Set.of("float32", "float64").contains(type)) throw invalid("absoluteTolerance applies only to floating-point scans");
        Object raw = args.get("absoluteTolerance");
        if (!(raw instanceof Number)) throw invalid("absoluteTolerance must be a finite non-negative number");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < 0) throw invalid("absoluteTolerance must be a finite non-negative number");
        return value;
    }
    private static String fingerprint(String session, String read, String type, String value) throws Exception {
        String encoded = MemoryValue.hex(MemoryValue.encode(type, value));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((session + "\n" + read + "\n" + type + "\n" + encoded).getBytes(StandardCharsets.UTF_8));
        return MemoryValue.hex(digest);
    }
    private static int cursor(JSONObject args, int generation) throws Exception {
        if (!args.has("cursor")) return 0;
        String raw = required(args, "cursor"), prefix = generation + ":";
        if (!raw.startsWith(prefix)) throw invalid("The result cursor belongs to another generation");
        return decimalIndex(raw.substring(prefix.length()));
    }
    private static Object nextCursor(int next, int count, int generation) { return next < count ? generation + ":" + next : JSONObject.NULL; }
    private static int decimalIndex(String raw) throws ActionException {
        if (!raw.matches("[0-9]{1,9}")) throw invalid("Invalid result cursor");
        try { return Integer.parseInt(raw); } catch (NumberFormatException bad) { throw invalid("Invalid result cursor"); }
    }
    private static int limit(JSONObject args) throws Exception { return integer(args, "limit", 20, 1, 50); }
    private static int integer(JSONObject args, String name, int fallback, int min, int max) throws Exception {
        if (!args.has(name)) return fallback;
        Object raw = args.get(name);
        if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).longValue()
            || ((Number) raw).longValue() < min || ((Number) raw).longValue() > max) throw invalid(name + " is outside its supported range");
        return ((Number) raw).intValue();
    }
    private static String stringValue(Object value) throws ActionException {
        if (!(value instanceof String) || ((String) value).isBlank() || ((String) value).length() > 128) throw invalid("Write values must be decimal strings");
        return (String) value;
    }
    private static String required(JSONObject args, String name) throws Exception {
        Object raw = args.opt(name);
        if (!(raw instanceof String) || ((String) raw).isBlank() || ((String) raw).length() > 512) throw invalid(name + " must be a non-empty string");
        return (String) raw;
    }
    private static void identity(String value, String name) throws ActionException {
        if (value == null || value.isBlank() || value.length() > 256) throw invalid("A trusted " + name + " identity is required");
    }
    private static void fields(JSONObject args, String... allowed) throws ActionException {
        Set<String> names = Set.of(allowed);
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) if (!names.contains(keys.next()))
            throw invalid("Supported parameters for this operation: " + String.join(", ", allowed));
    }
    private static ActionException ownerMismatch() { return new ActionException("memory_owner_mismatch", "The reference belongs to another task"); }
    private static ActionException invalid(String message) { return MemoryValue.invalid(message); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }

    interface TargetResolver { Target resolve(String packageName) throws Exception; }
    static final class Target {
        final String packageName; final int uid; final long version, updated;
        Target(String packageName, int uid, long version, long updated) { this.packageName = packageName; this.uid = uid; this.version = version; this.updated = updated; }
        boolean same(Target other) { return uid == other.uid && version == other.version && updated == other.updated && packageName.equals(other.packageName); }
        JSONObject json() throws Exception { return new JSONObject().put("packageName", packageName).put("uid", uid).put("versionCode", Long.toString(version)); }
    }
    private static final class AndroidTargets implements TargetResolver {
        final Context context;
        AndroidTargets(Context context) { this.context = context; }
        public Target resolve(String name) throws Exception {
            String packageName = ActionValidation.packageName(name);
            PackageManager manager = context.getPackageManager();
            PackageInfo info;
            try { info = manager.getPackageInfo(packageName, 0); }
            catch (PackageManager.NameNotFoundException missing) { throw new ActionException("memory_app_not_found", "The specified target application is not available to the current user"); }
            ApplicationInfo app = info.applicationInfo;
            if (app == null || app.uid / 100000 != android.os.Process.myUid() / 100000 || app.uid % 100000 < 10000 || app.uid % 100000 > 19999
                || app.uid == android.os.Process.myUid() || packageName.equals(context.getPackageName())
                || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                throw new ActionException("memory_target_not_allowed", "Only another ordinary third-party application in the current Android user is supported");
            String[] packages = manager.getPackagesForUid(app.uid);
            if (packages == null || packages.length != 1 || !packageName.equals(packages[0]) || info.sharedUserId != null)
                throw new ActionException("memory_target_not_allowed", "Shared or ambiguous application UIDs are not supported");
            return new Target(packageName, app.uid, info.getLongVersionCode(), info.lastUpdateTime);
        }
    }
    private record ProcessReference(String owner, Target target, JSONObject process) {}
    private static final class Session {
        final String id = id(), owner, mode; final Target target; final JSONObject process; final int pageSize;
        volatile boolean closed; volatile Scan scan; volatile int regionGeneration;
        volatile LinkedHashMap<String, Region> regions = new LinkedHashMap<>();
        final ConcurrentHashMap<String, Read> reads = new ConcurrentHashMap<>();
        Session(String owner, Target target, JSONObject process, String mode, int pageSize) throws Exception {
            this.owner = owner; this.target = target; this.process = copy(process); this.mode = mode; this.pageSize = pageSize;
        }
        void setRegions(JSONArray entries) throws Exception {
            if (entries.length() > 16384) throw new ActionException("memory_region_limit", "Too many target memory mappings");
            Map<String, Region> bySignature = new java.util.HashMap<>();
            for (Region region : regions.values()) bySignature.put(region.signature, region);
            LinkedHashMap<String, Region> replacement = new LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                Region region = bySignature.get(entry.getString("signature"));
                if (region == null) region = new Region(entry);
                replacement.put(region.id, region);
            }
            regions = replacement; regionGeneration++;
        }
        Region region(String id) throws ActionException {
            Region region = regions.get(id);
            if (region == null) throw new ActionException("memory_region_changed", "Query the current target memory regions again");
            return region;
        }
    }
    private static final class Region {
        final String id = id(), signature, permissions; final long start, end; final JSONObject nativeRegion;
        Region(JSONObject value) throws Exception {
            nativeRegion = copy(value); signature = value.getString("signature"); permissions = value.getString("perms");
            start = MemoryValue.address(value.get("start")); end = MemoryValue.address(value.get("end"));
            if (end <= start || permissions.length() != 4) throw new ActionException("memory_backend_invalid", "Invalid memory mapping");
        }
        boolean scannable() { return permissions.charAt(0) == 'r' && permissions.charAt(2) != 'x' && permissions.charAt(3) == 'p'; }
        boolean writable() { return scannable() && permissions.charAt(1) == 'w'; }
        void checkRange(long address, int length, boolean write) throws ActionException {
            if (length < 1 || address < start || address >= end || length > end - address || permissions.charAt(0) != 'r')
                throw new ActionException("memory_region_changed", "The requested bytes are outside a readable target mapping");
            if (write && !writable()) throw new ActionException("memory_region_not_allowed", "Writes require a readable writable private non-executable data mapping");
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("regionId", id).put("start", MemoryValue.address(start)).put("end", MemoryValue.address(end))
                .put("permissions", permissions).put("name", nativeRegion.optString("name"))
                .put("scanEligible", scannable()).put("writeEligible", writable());
        }
    }
    private static final class Read {
        final String id = id(), type; final Region region; final long address; final byte[] bytes; final int count;
        volatile boolean consumed;
        Read(Region region, long address, String type, byte[] bytes, int count) {
            this.region = region; this.address = address; this.type = type; this.bytes = bytes.clone(); this.count = count;
        }
    }
    private static final class Scan {
        final String id = id(), type; final List<Region> regions; final int patternSize;
        int generation = 1, referenceGeneration = 1, nextIndex; String hex, comparison = "eq", state = "paused"; Double tolerance;
        boolean refining, coverageComplete, sourceCoverageComplete = true, coverageIncomplete; volatile boolean cancelled;
        long scannedBytes, unreadableBytes, skippedBytes, skippedCandidates, invalidatedCandidates;
        final Set<Integer> changedRegionIndices = new java.util.HashSet<>();
        JSONObject nativeCursor;
        List<JSONObject> baseline = List.of(), staging = new ArrayList<>();
        Scan(String type, String hex, List<Region> regions, Double tolerance) {
            this.type = type; this.hex = hex; this.regions = regions; this.tolerance = tolerance; patternSize = hex.length() / 2;
        }
        void beginRefine(String comparison, String hex, Double tolerance) {
            generation++; referenceGeneration++; refining = true; sourceCoverageComplete = coverageComplete;
            this.comparison = comparison; this.hex = hex; this.tolerance = tolerance;
            state = "paused"; nextIndex = 0; staging = new ArrayList<>(); scannedBytes = 0; unreadableBytes = 0;
            skippedBytes = 0; skippedCandidates = 0; invalidatedCandidates = 0; coverageIncomplete = false; changedRegionIndices.clear();
        }
        void fail() { staging = new ArrayList<>(); state = "failed"; }
        List<JSONObject> visible() { return state.equals("ready") ? baseline : staging; }
        JSONArray regionArray() { JSONArray array = new JSONArray(); for (Region region : regions) array.put(region.nativeRegion); return array; }
    }
    private final class Guard implements Cancellation {
        final String owner; final Cancellation original; final long allVersion, ownerVersion; Session session; Scan scan;
        Guard(String owner, Cancellation original) {
            this.owner = owner; this.original = original; allVersion = epoch.get();
            ownerVersion = ownerEpochs.computeIfAbsent(owner, key -> new AtomicLong()).get();
        }
        void bind(Session session) { this.session = session; }
        void bindScan(Scan scan) { this.scan = scan; }
        @Override public void check() throws InterruptedException {
            original.check();
            if (epoch.get() != allVersion || ownerEpochs.get(owner).get() != ownerVersion || session != null && session.closed || scan != null && scan.cancelled)
                throw new InterruptedException("Process memory task stopped");
        }
        @Override public void usingRoot(boolean root) throws InterruptedException { original.usingRoot(root); check(); }
        @Override public long remainingMillis(long max) throws Exception { check(); return original.remainingMillis(max); }
        @Override public void dispatched() throws InterruptedException { check(); original.dispatched(); }
    }
}

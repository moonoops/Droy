package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import fi.iki.elonen.NanoHTTPD;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.PersonalDataTools;
import io.droyagent.androiduse.DeviceSettingsTools;
import io.droyagent.androiduse.SelectedFileTools;
import io.droyagent.androiduse.AppDiagnosticsTools;
import io.droyagent.androiduse.SystemDiagnosticsTools;
import io.droyagent.androiduse.ProcessMemoryTools;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class ProductBridge extends NanoHTTPD {
    private final String token;
    private final Context context;
    private final AndroidUseExecutor executor;
    private final SharedPreferences settings;
    private final CapabilityCatalog catalog;
    private final PersonalDataTools personal;
    private final DeviceSettingsTools deviceSettings;
    private final SelectedFileTools files;
    private final AppDiagnosticsTools appDiagnostics;
    private final SystemDiagnosticsTools systemDiagnostics;
    private final ProcessMemoryTools memory;
    private final BrowserSession browser;
    private final ComputeTools compute;
    private final HardwareRuntime hardware;
    private final ToolProgress progress;
    private final NativeFirstUseAuthorization firstUse;
    private final VerificationFlow verification = new VerificationFlow(SystemClock::elapsedRealtime);
    final ControlPolicy policy = new ControlPolicy(SystemClock::elapsedRealtime);

    static String deviceTimeZone() {
        String zone = java.util.TimeZone.getDefault().toZoneId().normalized().getId();
        return zone.equals("Z") ? "UTC" : zone;
    }

    ProductBridge(Context context, String token) {
        super("127.0.0.1", 0);
        this.token = token;
        settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Context app = context.getApplicationContext();
        this.context = app;
        executor = new AndroidUseExecutor(app);
        catalog = new CapabilityCatalog(app);
        firstUse = NativeFirstUseAuthorization.get(app);
        personal = new PersonalDataTools(app, new PersonalDataTools.NotificationSource() {
            public JSONObject status() throws Exception { return DroyNotificationService.status(app); }
            public JSONObject query(JSONObject args, Cancellation cancellation) throws Exception {
                return DroyNotificationService.query(app, args, cancellation);
            }
        });
        deviceSettings = new DeviceSettingsTools(app);
        files = new SelectedFileTools(app);
        appDiagnostics = new AppDiagnosticsTools(app);
        systemDiagnostics = new SystemDiagnosticsTools(app);
        memory = ProcessMemoryTools.get(app);
        browser = new BrowserSession(app);
        compute = new ComputeTools(app);
        hardware = HardwareRuntime.get(app);
        progress = new ToolProgress(app);
        progress.recoverInterrupted();
    }

    @Override public Response serve(IHTTPSession session) {
        String supplied = session.getHeaders().getOrDefault("authorization", "");
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8)))
            return response(Response.Status.UNAUTHORIZED, failure("UNAUTHORIZED", NativeLocale.text("拒绝未授权访问", "Unauthorized access denied")));
        CallTrace trace = new CallTrace();
        try {
            if (session.getMethod() == Method.GET && session.getUri().equals("/locale"))
                return ok(NativeLocale.bootstrap());
            if (session.getMethod() == Method.GET && session.getUri().equals("/status"))
                return response(Response.Status.OK, new JSONObject().put("ok", true).put("stopped", policy.isStopped()));
            if (session.getMethod() != Method.POST) return response(Response.Status.METHOD_NOT_ALLOWED, failure("METHOD", NativeLocale.text("只接受 POST", "Only POST is accepted")));
            long size = Long.parseLong(session.getHeaders().getOrDefault("content-length", "-1"));
            if (size < 0 || size > 262144) return response(Response.Status.BAD_REQUEST, failure("SIZE", NativeLocale.text("请求大小无效", "Invalid request size")));
            byte[] body = new byte[(int) size];
            new java.io.DataInputStream(session.getInputStream()).readFully(body);
            // JSON's wire encoding is UTF-8. NanoHTTPD's generic form parser defaults
            // to ASCII without a charset parameter, corrupting Chinese tool arguments.
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body)).toString();
            JSONObject request = new JSONObject(json);
            if (session.getUri().equals("/clock")) return ok(new JSONObject()
                .put("now", System.currentTimeMillis()).put("timeZone", deviceTimeZone()));
            if (session.getUri().equals("/setup")) return ok(setupRequest(request));
            if (session.getUri().equals("/memory") || session.getUri().equals("/history"))
                return memoryRequest(session.getUri(), request);
            if (session.getUri().equals("/voice")) return ok(VoiceRuntime.request(context, request));
            if (session.getUri().equals("/embedding")) {
                if (request.optString("operation").equals("embed") && (policy.isStopped() || ProductPermissions.stopped(context))) {
                    io.droyagent.app.embedding.EmbeddingRuntime.cancelAllIfCreated();
                    return response(Response.Status.OK, failure("EMBEDDING_PAUSED", NativeLocale.text("本地语义处理已暂停", "Local semantic processing is paused")));
                }
                try { return ok(io.droyagent.app.embedding.EmbeddingRuntime.get(context).request(request)); }
                catch (Exception error) {
                    String code = error instanceof InterruptedException ? "EMBEDDING_CANCELLED" : error.getMessage();
                    if (code == null || !code.matches("EMBEDDING_[A-Z_]{1,64}")) code = "EMBEDDING_NATIVE_FAILED";
                    return response(Response.Status.OK, failure(code, NativeLocale.text("本地语义处理未完成", "Local semantic processing did not complete")));
                }
            }
            if (session.getUri().equals("/audio"))
                return ok(audioRequest(request, audioOwner(request)));
            if (session.getUri().equals("/audio/file")) {
                checkAudioDataAccess();
                java.io.File file = AudioCapture.get(context).file(request.getString("captureId"), audioOwner(request));
                return newFixedLengthResponse(Response.Status.OK, "audio/wav", new java.io.FileInputStream(file), file.length());
            }
            if (session.getUri().equals("/audio/frames")) {
                checkAudioDataAccess();
                AudioCapture.FrameRead frames = AudioCapture.get(context).readFrames(request.getString("captureId"),
                    audioOwner(request), request.optLong("cursor", 0), request.optInt("maxBytes", 32768));
                Response response = newFixedLengthResponse(Response.Status.OK, "application/octet-stream",
                    new java.io.ByteArrayInputStream(frames.data), frames.data.length);
                response.addHeader("X-Droy-Audio-Metadata", frames.metadata.toString());
                response.addHeader("Cache-Control", "no-store");
                return response;
            }
            if (session.getUri().equals("/companion")) return ok(companionIdentity());
            if (session.getUri().equals("/companion-memory")) {
                try { return ok(NativeCompanionMemoryPurge.request(context, request)); }
                catch (Exception error) {
                    String code = error.getMessage();
                    if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,100}")) code = "COMPANION_MEMORY_PURGE_FAILED";
                    return response(Response.Status.OK, failure(code, NativeLocale.text("记忆清理未完成，相关记忆保持停用", "Memory cleanup is incomplete. The affected memory remains disabled")));
                }
            }
            if (session.getUri().equals("/notify")) return ok(ProactiveNotifications.get(context).request(request));
            if (session.getUri().equals("/notifications")) {
                if (request.optString("operation").equals("archive")) {
                    if (policy.isStopped() || ProductPermissions.stopped(context))
                        throw new ProductPermissionDenied("operations_paused", NativeLocale.text("通知读取已暂停", "Notification reading is paused"));
                    checkPermission(catalog.tool("notifications_query"), false);
                }
                JSONObject result = NotificationManagement.get(context).request(request);
                if (!request.optString("operation").equals("archive")) result.remove("archive");
                return ok(result);
            }
            if (session.getUri().equals("/progress")) {
                boolean completedNow = ToolProgress.updateFromEngine(request);
                RuntimeService.refreshExecutionProgress();
                if (completedNow) RuntimeService.taskCompleted(request.getString("taskId"));
                return ok(ToolProgress.current(context));
            }
            if (session.getUri().equals("/attachments/normalize"))
                return ok(AttachmentImageCodec.normalize(context, request));
            String task = request.optString("taskId"), call = request.optString("callId");
            if (session.getUri().equals("/permissions/preflight")) {
                JSONObject arguments = request.optJSONObject("args");
                return ok(firstUse.preflight(request.getString("action"), arguments == null ? new JSONObject() : arguments,
                    task, call, request.has("humanBudgetMs") ? budget(request.get("humanBudgetMs")) : 120000, policy));
            }
            if (session.getUri().equals("/cancel")) {
                policy.cancel(task, call);
                hardware.closeOwner(task);
                memory.cancel(task);
                AudioCapture.get(context).cancelStart(task, call);
                AudioTranscription.get(context).cancelStart(task, call);
                ToolProgress.endScreenOperation(task); RuntimeService.refreshExecutionProgress();
                firstUse.cancel(task, call);
                return ok(new JSONObject());
            }
            if (session.getUri().equals("/task/end")) {
                policy.end(task); verification.end(task);
                memory.end(task);
                hardware.closeOwner(task);
                ToolProgress.endScreenOperation(task); RuntimeService.refreshExecutionProgress();
                firstUse.end(task);
                return ok(new JSONObject());
            }
            if (!session.getUri().equals("/tool")) return response(Response.Status.NOT_FOUND, failure("NOT_FOUND", NativeLocale.text("接口不存在", "Endpoint not found")));
            String action = request.getString("action");
            JSONObject args = request.optJSONObject("args");
            JSONObject arguments = args == null ? new JSONObject() : args;
            long budget = request.has("budgetMs") ? budget(request.get("budgetMs")) : defaultBudget(action, arguments);
            return ok(executeTool(action, arguments, task, call, budget, trace));
        } catch (ControlPolicy.DeadlineExceeded e) { return response(Response.Status.OK, failure("action_timeout", e.getMessage(), "deadline_exceeded", trace)); }
        catch (ActionException e) { return response(Response.Status.OK, actionFailure(e, trace)); }
        catch (InterruptedException | java.io.InterruptedIOException e) { return response(Response.Status.OK, failure("CANCELLED", NativeLocale.text("操作已停止；先核实已经发生的动作，不自动重放", "Operation stopped. Verify completed actions before continuing; do not replay automatically"), "cancelled", trace)); }
        catch (ProductPermissionDenied e) { return response(Response.Status.OK, failure("PERMISSION_DENIED", e.getMessage(), e.reason, trace)); }
        catch (SecurityException e) { return response(Response.Status.OK, failure("PERMISSION_DENIED", e.getMessage(), "system_permission_denied", trace)); }
        catch (IllegalArgumentException | IllegalStateException e) { return response(Response.Status.OK, failure("REJECTED", e.getMessage(), "request_rejected", trace)); }
        catch (Exception e) { return response(Response.Status.INTERNAL_ERROR, failure("BRIDGE_ERROR", NativeLocale.text("原生接口失败：", "Native interface failed: ") + e.getClass().getSimpleName(), "native_failure", trace)); }
    }

    @Override public void stop() {
        io.droyagent.app.embedding.EmbeddingRuntime.cancelAllIfCreated();
        super.stop();
    }

    private Response memoryRequest(String route, JSONObject request) {
        try {
            MemoryRuntime host = MemoryRuntime.get(context);
            return ok(route.equals("/memory") ? host.request(request) : host.historyRequest(request));
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.util.concurrent.ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
            String code = cause.getMessage();
            if (code == null || !code.matches("(?:MEMORY|HISTORY|EXECUTION)_[A-Z0-9_]{1,80}")) code = "MEMORY_NATIVE_FAILED";
            return response(Response.Status.OK, failure(code, code));
        }
    }

    JSONObject companionIdentity() throws Exception {
        PetPackage appearance = PetPackage.selected(context);
        return new JSONObject().put("name", PetOverlay.name(context))
            .put("displayName", AppBranding.name(context, settings))
            .put("appearanceId", appearance.id)
            .put("personality", settings.getString("companionPersonality", "").isBlank()
                ? appearance.spec.persona.description() : settings.getString("companionPersonality", ""))
            .put("userAddress", settings.getString("userAddress", ""))
            .put("runtime", new JSONObject()
                .put("stopped", policy.isStopped() || ProductPermissions.stopped(context))
                .put("observeAllowed", ProductPermissions.allowed(settings, "allowObserve"))
                .put("controlAllowed", ProductPermissions.allowed(settings, "allowControl"))
                .put("screenshotsAllowed", ProductPermissions.allowed(settings, "allowScreenshot"))
                .put("appsAllowed", ProductPermissions.allowed(settings, "allowApps"))
                .put("accessibilityConnected", io.droyagent.androiduse.DroyAccessibilityService.isConnected(context))
                .put("rootEnabled", ProductPermissions.allowed(settings, "allowRoot")));
    }

    private JSONObject setupRequest(JSONObject request) throws Exception {
        String operation = request.optString("operation", request.optString("action", "status"));
        return switch (operation) {
            case "status" -> SetupManager.status(context);
            case "update" -> SetupManager.update(context, request.getJSONObject("configuration"));
            case "verify" -> SetupManager.verify(context);
            case "recordModel" -> SetupManager.recordModel(context, request);
            case "checkModelFingerprint" -> SetupManager.checkModelFingerprint(context, request.getString("configFingerprint"));
            case "complete" -> SetupManager.complete(context);
            case "nativeStatus" -> SetupManager.nativeStatus(context, request);
            case "nativeAcknowledgeIdentity" -> SetupManager.nativeAcknowledgeIdentity(context, request);
            case "nativeComplete" -> SetupManager.nativeComplete(context, request);
            default -> throw new IllegalArgumentException(NativeLocale.text("不支持的安装操作", "Unsupported installation operation"));
        };
    }

    JSONObject executeTool(String action, JSONObject args, String task, String call) throws Exception {
        return executeTool(action, args, task, call, defaultBudget(action, args));
    }

    JSONObject executeTool(String action, JSONObject args, String task, String call, long budgetMs) throws Exception {
        return executeTool(action, args, task, call, budget(budgetMs), new CallTrace());
    }

    private JSONObject executeTool(String action, JSONObject args, String task, String call,
                                   long budgetMs, CallTrace trace) throws Exception {
        if (action.equals("sequence")) return executeSequence(args, task, call, budgetMs, trace);
        return executeTool(action, args, task, call, budgetMs, trace, null);
    }

    private JSONObject executeTool(String action, JSONObject args, String task, String call,
                                   long budgetMs, CallTrace trace, ControlPolicy.Ticket sequenceTicket) throws Exception {
        if (action.equals("capabilities")) return capabilityStatus();
        if (Set.of("audio_start", "audio_status", "audio_stop").contains(action))
            return executeAudio(action, args, task, call, budgetMs);
        JSONObject selectedTool = CapabilityAuthorization.forArguments(catalog.tool(action), args);
        if (HardwareRuntime.cleanup(action, args))
            return hardware.execute(action, args, task, () -> {});
        if (ProcessMemoryTools.isLocal(action, args)) {
            requireMemoryIdentity(task, call);
            // Local cleanup and existing receipts remain accessible after stop or
            // revocation. This branch must never start a device-access backend.
            return memory.execute(action, args, task, call, () -> {});
        }
        boolean root = ProductPermissions.allowed(settings, "allowRoot");
            JSONObject tool = selectedTool;
            final JSONObject arguments = args;
            ControlPolicy.Ticket ticket = sequenceTicket == null ? policy.begin(task, call, budgetMs) : sequenceTicket;
            trace.ticket = ticket;
            trace.sharedTicket = sequenceTicket != null;
            boolean verificationReturn = action.equals("verification") && args.optString("operation").equals("return_to_app");
            trace.dispatchTracked = verificationReturn || Set.of("open_app", "tap", "swipe", "gesture", "input_text", "back",
                "node_action", "global_action", "open_settings", "memory_write").contains(action);
            trace.readOnly = Set.of("observe", "screenshot", "system_info", "find_apps", "wait_for", "settings_get",
                "calendar_query", "contacts_query", "usage_query", "notifications_query", "file_list", "file_read",
                "app_info", "app_exit_info", "network_usage", "privacy_usage", "battery_usage",
                "browser_status", "browser_observe", "browser_wait", "compute_jobs", "compute_artifact", "verification",
                "process_memory", "memory_scan", "memory_read").contains(action);
            if (HardwareRuntime.ACTIONS.contains(action)) trace.readOnly = HardwareRuntime.readOnly(action, args);
            if (verificationReturn) trace.readOnly = false;
            boolean started = false;
            boolean verificationActionStarted = false;
            boolean screenMutation = VerificationFlow.affectsScreen(action, tool.optBoolean("screen"), trace.readOnly);
            try {
                if (sequenceTicket == null) firstUse.checkDispatch(task, call, ticket, policy);
                long authorizedAt = SystemClock.elapsedRealtime();
                checkPermission(tool, false);
                trace.authorizationMs = SystemClock.elapsedRealtime() - authorizedAt;
                Cancellation cancellation = new Cancellation() {
                    private boolean usingRoot;
                    @Override public void usingRoot(boolean value) throws InterruptedException {
                        usingRoot = value;
                        check();
                    }
                    @Override public void check() throws InterruptedException {
                        ticket.check();
                        try { checkPermission(tool, usingRoot); }
                        catch (org.json.JSONException invalid) { throw new IllegalStateException(NativeLocale.text("能力目录无效", "Invalid capability catalog"), invalid); }
                    }
                    @Override public long remainingMillis(long maximumMs) throws Exception {
                        check(); return ticket.remainingMillis(maximumMs);
                    }
                    @Override public void dispatched() throws InterruptedException {
                        check(); ticket.dispatched(); trace.dispatchedAtMs = SystemClock.elapsedRealtime() - trace.startedAt;
                    }
                };
                cancellation.check();
                verification.beforeAction(task, action, args, screenMutation);
                verificationActionStarted = screenMutation;
                progress.record(action, "executing", NativeLocale.text("正在执行；中断后需核实结果", "Executing. Verify the result after any interruption"));
                ToolProgress.update(task, "executing", true);
                if (screenOperation(action, tool.optBoolean("screen"), verificationReturn, BrowserUiProtection.isVisible(context)))
                    ToolProgress.phoneOperation(task);
                RuntimeService.refreshExecutionProgress();
                started = true;
                JSONObject dispatchArgs = arguments;
                boolean observeAfterLaunch = action.equals("open_app") && arguments.optBoolean("afterObserve", true);
                if (observeAfterLaunch) { dispatchArgs = new JSONObject(arguments.toString()); dispatchArgs.put("afterObserve", false); }
                long dispatchedAt = SystemClock.elapsedRealtime();
                trace.dispatchEntered = true;
                JSONObject result;
                try { result = verificationReturn ? returnToVerificationApp(task, cancellation)
                        : action.equals("verification") ? verification.request(task, dispatchArgs)
                        : dispatch(action, dispatchArgs, task, call, root, cancellation); }
                finally { trace.dispatchMs = SystemClock.elapsedRealtime() - dispatchedAt; }
                if (observeAfterLaunch && result.optBoolean("dispatched")) {
                    ToolProgress.update(task, "verifying", true);
                    RuntimeService.refreshExecutionProgress();
                    long observedAt = SystemClock.elapsedRealtime();
                    try { result.put("afterObservation", observeAfterLaunch(result.optString("packageName"), cancellation)); }
                    finally { trace.afterObservationMs = SystemClock.elapsedRealtime() - observedAt; }
                }
                // A terminal write receipt is evidence even when stop/revocation
                // arrives during readback. Do not replace it with a generic error.
                if (!action.equals("memory_write")) cancellation.check();
                if (!action.equals("verification")) verification.returned(task, action, result);
                if (action.equals("verification") && result.optString("state").equals("manual_required"))
                    ToolProgress.endScreenOperation(task);
                String status = result.optString("status", "returned");
                boolean needsVerification = result.has("goalVerified") && !result.optBoolean("goalVerified");
                progress.record(action, needsVerification ? "awaiting_verification" : "returned",
                    needsVerification ? NativeLocale.text("工具已返回，任务目标仍需核实", "The tool returned. The task outcome still needs verification") : outcome(status));
                ToolProgress.update(task, "verifying", true);
                RuntimeService.refreshExecutionProgress();
                result.put("nativeTiming", trace.timing());
                return result;
            } catch (Exception failure) {
                if (verificationActionStarted || Set.of("observe", "screenshot", "wait_for").contains(action))
                    verification.failed(task, verificationActionStarted);
                if (started) {
                    progress.record(action, "interrupted", trace.wasDispatched()
                        ? NativeLocale.text("动作已派发，结果需重新核实；不会自动重放", "Action dispatched. Verify the result again; it will not be replayed automatically") : NativeLocale.text("本次调用已中断，请按当前状态继续", "This call was interrupted. Continue from the current state"));
                    ToolProgress.update(task, "interrupted", true);
                }
                ToolProgress.endScreenOperation(task);
                RuntimeService.refreshExecutionProgress();
                throw failure;
            } finally { if (sequenceTicket == null) policy.finish(ticket); }
    }

    private JSONObject executeSequence(JSONObject args, String task, String call, long budgetMs, CallTrace trace) throws Exception {
        ActionSequence sequence = new ActionSequence(args);
        JSONObject tool = catalog.tool("sequence");
        long sequenceBudget = Math.min(budgetMs, args.optLong("timeoutMs", 30000));
        ControlPolicy.Ticket ticket = policy.begin(task, call, sequenceBudget);
        trace.ticket = ticket; trace.dispatchTracked = true;
        SharedPreferences receipts = context.getSharedPreferences("sequence-receipts", Context.MODE_PRIVATE);
        String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest((task + "\0" + call).getBytes(StandardCharsets.UTF_8)));
        try {
            firstUse.checkDispatch(task, call, ticket, policy);
            checkPermission(tool, false);
            String previous = receipts.getString(key, null);
            if (previous != null) return ActionSequence.resumed(new JSONObject(previous)).put("nativeTiming", trace.timing());
            Cancellation cancellation = new Cancellation() {
                @Override public void check() throws InterruptedException {
                    ticket.check();
                    try { checkPermission(tool, false); }
                    catch (org.json.JSONException invalid) { throw new IllegalStateException(NativeLocale.text("能力目录无效", "Invalid capability catalog"), invalid); }
                }
                @Override public long remainingMillis(long maximumMs) throws Exception {
                    check(); return ticket.remainingMillis(maximumMs);
                }
            };
            JSONObject result = sequence.run(new ActionSequence.Host() {
                @Override public void check() throws Exception { cancellation.check(); }
                @Override public long now() { return SystemClock.elapsedRealtime(); }
                @Override public JSONObject basis(String snapshotId) throws Exception {
                    cancellation.check();
                    return executor.sequenceBasis(snapshotId, cancellation);
                }
                @Override public JSONObject observe() throws Exception {
                    return executeTool("observe", new JSONObject(), task, call, sequenceBudget, new CallTrace(), ticket);
                }
                @Override public JSONObject execute(String action, JSONObject arguments, int step) throws Exception {
                    CallTrace child = new CallTrace();
                    try {
                        return executeTool(action, arguments, task, call, sequenceBudget, child, ticket);
                    } catch (Exception error) {
                        JSONObject failure = sequenceFailure(error, child);
                        throw new ActionException(failure.getString("code"), failure.optString("reason"), failure.getString("message"))
                                .withDetails(failure);
                    }
                }
                @Override public JSONObject failure(Exception error) throws Exception {
                    if (error instanceof ActionException failure) {
                        JSONObject details = failure.getDetails() == null ? new JSONObject() : new JSONObject(failure.getDetails().toString());
                        return details.put("code", failure.getCode()).put("message", failure.getMessage())
                                .put("reason", failure.getReason() == null ? "" : failure.getReason());
                    }
                    return sequenceFailure(error, new CallTrace());
                }
                @Override public void save(JSONObject metadata) {
                    // Store only step receipts, never selectors, input text, or screen data.
                    // A started step without a result remains unknown after a process restart.
                    if (!receipts.edit().putString(key, metadata.toString()).commit())
                        throw new IllegalStateException(NativeLocale.text("连续动作回执未保存；先核实已执行部分", "Action sequence receipt was not saved. Verify the completed portion first"));
                }
            });
            return result.put("nativeTiming", trace.timing());
        } finally { policy.finish(ticket); }
    }

    static void clearSequenceReceipts(Context context) {
        if (!context.getSharedPreferences("sequence-receipts", Context.MODE_PRIVATE).edit().clear().commit())
            throw new IllegalStateException(NativeLocale.text("连续动作回执未清除", "Action sequence receipt was not cleared"));
    }

    private static JSONObject sequenceFailure(Exception error, CallTrace trace) throws Exception {
        if (error instanceof ActionException failure) return actionFailure(failure, trace).getJSONObject("error");
        if (error instanceof ControlPolicy.DeadlineExceeded) return failure("action_timeout", error.getMessage(), "deadline_exceeded", trace).getJSONObject("error");
        if (error instanceof InterruptedException || error instanceof java.io.InterruptedIOException)
            return failure("CANCELLED", NativeLocale.text("操作已停止；先核实已经发生的动作，不自动重放", "Operation stopped. Verify completed actions before continuing; do not replay automatically"), "cancelled", trace).getJSONObject("error");
        if (error instanceof ProductPermissionDenied denied)
            return failure("PERMISSION_DENIED", denied.getMessage(), denied.reason, trace).getJSONObject("error");
        if (error instanceof SecurityException)
            return failure("PERMISSION_DENIED", error.getMessage(), "system_permission_denied", trace).getJSONObject("error");
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException)
            return failure("REJECTED", error.getMessage(), "request_rejected", trace).getJSONObject("error");
        return failure("BRIDGE_ERROR", NativeLocale.text("原生接口失败：", "Native interface failed: ") + error.getClass().getSimpleName(), "native_failure", trace).getJSONObject("error");
    }

    private static void requireMemoryIdentity(String owner, String call) {
        if (owner == null || owner.isBlank() || owner.length() > 256
                || call == null || call.isBlank() || call.length() > 256)
            throw new IllegalArgumentException(NativeLocale.text("无效的任务或调用标识", "Invalid task or call identifier"));
    }

    private JSONObject executeAudio(String action, JSONObject args, String owner, String call, long budgetMs) throws Exception {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException(NativeLocale.text("音频调用缺少任务身份", "Audio call is missing a task identity"));
        AudioCapture audio = AudioCapture.get(context);
        if (action.equals("audio_status")) return audio.status(args.optString("captureId"), owner);
        // Cleanup remains available after a product grant or the engine is stopped.
        if (action.equals("audio_stop")) return audio.stop(args.optString("captureId"), owner);
        ControlPolicy.Ticket ticket = policy.begin(owner, call, budgetMs);
        try {
            firstUse.checkDispatch(owner, call, ticket, policy);
            VoiceWake.get(context).pauseForVoice();
            ticket.check();
            JSONObject result = audio.start(args, owner, call);
            ticket.check();
            return result;
        } catch (Exception failure) {
            audio.cancelStart(owner, call);
            throw failure;
        } finally { policy.finish(ticket); }
    }

    private static String audioOwner(JSONObject request) throws Exception {
        String owner = request.getString("taskId");
        if (owner.isBlank() || owner.length() > 256) throw new IllegalArgumentException(NativeLocale.text("音频调用身份无效", "Invalid audio call identity"));
        return owner;
    }

    private void checkAudioDataAccess() {
        if (policy.isStopped() || ProductPermissions.stopped(context))
            throw new ProductPermissionDenied("operations_paused", NativeLocale.text("音频处理已暂停", "Audio processing is paused"));
    }

    JSONObject audioRequest(JSONObject request, String owner) throws Exception {
        String operation = request.optString("operation");
        if (!Set.of("preparation", "transcript", "stop_transcription", "cancel_preparation").contains(operation))
            checkAudioDataAccess();
        if (operation.equals("transcript") && !request.optBoolean("statusOnly", false)) checkAudioDataAccess();
        if (operation.equals("transcribe")) {
            String callId = request.getString("callId");
            if (callId.isBlank() || callId.length() > 256) throw new IllegalArgumentException(NativeLocale.text("转录调用身份无效", "Invalid transcription call identity"));
            return AudioTranscription.get(context).request(request, owner, callId);
        }
        return AudioTranscription.get(context).request(request, owner);
    }

    JSONObject audioRequestFromUi(JSONObject request) throws Exception {
        return AudioTranscription.get(context).requestFromUi(request);
    }

    static boolean screenOperation(String action, boolean screen, boolean verificationReturn, boolean browserVisible) {
        if (action.equals("verification")) return verificationReturn;
        if (action.startsWith("browser_"))
            return action.equals("browser_open") || browserVisible && !action.equals("browser_status");
        return screen || action.equals("open_app");
    }

    private JSONObject returnToVerificationApp(String task, Cancellation original) throws Exception {
        String target = verification.returnTarget(task);
        JSONObject observation = executor.execute("observe", new JSONObject(), false, original);
        if (target.equals(observation.optString("packageName")))
            return new JSONObject().put("state", "manual_required").put("targetPackage", target)
                    .put("alreadyForeground", true).put("goalVerified", false);
        JSONObject launchTool = catalog.tool("open_app");
        Cancellation launch = new Cancellation() {
            @Override public void check() throws InterruptedException {
                original.check();
                try { checkPermission(launchTool, false); }
                catch (org.json.JSONException invalid) { throw new IllegalStateException(NativeLocale.text("能力目录无效", "Invalid capability catalog"), invalid); }
            }
            @Override public long remainingMillis(long maximumMs) throws Exception {
                check(); return original.remainingMillis(maximumMs);
            }
            @Override public void dispatched() throws InterruptedException {
                check(); original.dispatched();
            }
        };
        launch.check();
        JSONObject result = executor.execute("open_app", new JSONObject().put("packageName", target)
                .put("afterObserve", false), false, launch);
        if (!result.optBoolean("foregroundVerified"))
            throw new ActionException("verification_target_changed", NativeLocale.text("已请求返回原应用，但尚未确认在前台；请手动回到验证页面后再次发起人工接管", "Return to the original app was requested but foreground status is unconfirmed. Open the verification page manually, then request human handoff again"));
        // Returning to the original package does not establish that its challenge passed.
        return result.put("state", "manual_required").put("goalVerified", false)
                .put("targetPackage", target).put("verificationRequired", NativeLocale.text("重新观察原应用并核实验证结果", "Observe the original app again and verify the result"));
    }

    private JSONObject observeAfterLaunch(String targetPackage, Cancellation original) throws Exception {
        JSONObject observeTool = catalog.tool("observe");
        Cancellation observation = new Cancellation() {
            @Override public void check() throws InterruptedException {
                original.check();
                try { checkPermission(observeTool, false); }
                catch (org.json.JSONException invalid) { throw new IllegalStateException(NativeLocale.text("能力目录无效", "Invalid capability catalog"), invalid); }
            }
            @Override public long remainingMillis(long maximumMs) throws Exception {
                check(); return original.remainingMillis(maximumMs);
            }
        };
        boolean leftProductUi = false;
        boolean openingProduct = context.getPackageName().equals(targetPackage);
        while (true) {
            original.check();
            try {
                // The launch may finish before Activity.onStop. Wait only for
                // this initial departure; a later user return ends observation.
                checkPermission(observeTool, false);
                boolean departingProductUi = !openingProduct &&
                    (MainActivity.isSensitiveUiVisible() || BrowserUiProtection.isVisible(context));
                if (departingProductUi) {
                    if (leftProductUi) return unavailable("foreground_changed", NativeLocale.text("目标应用已不在前台；请核实当前页面后继续", "The target app is no longer in the foreground. Check the current page before continuing"));
                } else {
                    leftProductUi = true;
                    JSONObject observed = executor.execute("observe", new JSONObject(), false, observation);
                    if (!targetPackage.equals(observed.optString("packageName")))
                        return unavailable("foreground_changed", NativeLocale.text("目标应用已不在前台；请核实当前页面后继续", "The target app is no longer in the foreground. Check the current page before continuing"));
                    org.json.JSONArray nodes = observed.optJSONArray("nodes");
                    if ((nodes != null && nodes.length() > 0) || !observed.optString("text").isBlank()) return observed;
                }
            } catch (ProductPermissionDenied denied) {
                return unavailable(denied.reason, denied.getMessage());
            } catch (SecurityException denied) {
                return unavailable("system_permission_denied", denied.getMessage());
            } catch (ActionException unavailable) {
                if (!"window_unavailable".equals(unavailable.getCode()))
                    return unavailable(unavailable.getCode(), unavailable.getMessage());
            }
            Thread.sleep(original.remainingMillis(100));
        }
    }

    private static JSONObject unavailable(String reason, String message) throws Exception {
        return new JSONObject().put("status", "unavailable").put("reason", reason).put("message", message);
    }

    private JSONObject dispatch(String action, JSONObject args, String owner, String call,
                                boolean root, Cancellation cancellation) throws Exception {
        if (HardwareRuntime.ACTIONS.contains(action)) return hardware.execute(action, args, owner, cancellation);
        if (isMemoryAction(action)) {
            cancellation.usingRoot(true);
            return memory.execute(action, args, owner, call, cancellation);
        }
        if (Set.of("app_info", "app_exit_info", "network_usage").contains(action))
            return appDiagnostics.execute(action, args, cancellation);
        if (Set.of("privacy_usage", "battery_usage").contains(action))
            return systemDiagnostics.execute(action, args, cancellation);
        if (action.startsWith("settings_")) return deviceSettings.execute(action, args, cancellation);
        if (action.startsWith("file_")) return files.execute(action, args, cancellation);
        if (action.startsWith("browser_")) return browser.execute(action, args, cancellation);
        if (action.startsWith("compute_")) return compute.execute(action, args, cancellation);
        if (Set.of("calendar_query", "contacts_query", "usage_query", "notifications_query").contains(action))
            return personal.execute(action, args, cancellation);
        return executor.execute(action, args, root, cancellation);
    }

    static boolean isMemoryAction(String action) {
        return Set.of("process_memory", "memory_scan", "memory_read", "memory_write").contains(action);
    }

    JSONObject capabilityStatus() throws Exception {
        JSONObject memoryStatus = memory.capabilities();
        JSONObject audioCapture = new JSONObject()
            .put("busy", !AudioCapture.get(context).uiStatus().isNull("active"))
            .put("microphonePermission", context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("playbackAuthorization", "per_session")
            .put("sources", new org.json.JSONArray().put("microphone").put("app_playback").put("system_playback"));
        JSONObject audioTranscription = AudioTranscription.get(context)
            .request(new JSONObject().put("operation", "preparation"), "capability-query")
            .put("busy", AudioTranscription.get(context).hasActiveJobs());
        JSONObject grants = new JSONObject();
        org.json.JSONArray groupStates = new org.json.JSONArray();
        for (int i = 0; i < catalog.groups().length(); i++) {
            JSONObject group = catalog.groups().getJSONObject(i);
            boolean allowed = ProductPermissions.allowed(settings, group.getString("preference"));
            grants.put(group.getString("id"), allowed);
            groupStates.put(new JSONObject(group.toString()).put("allowed", allowed));
        }
        JSONObject nativeStatus = executor.capabilities(ProductPermissions.allowed(settings, "allowRoot"));
        JSONObject diagnostics = appDiagnostics.capabilities();
        JSONObject enhanced = systemDiagnostics.capabilities();
        for (java.util.Iterator<String> keys = enhanced.keys(); keys.hasNext();) {
            String key = keys.next();
            diagnostics.put(key, enhanced.get(key));
        }
        JSONObject systemPermissions = personal.permissionStatus();
        JSONObject hardwareStatus = hardware.capabilities();
        diagnostics.put("usage_query", new JSONObject().put("available", systemPermissions.optBoolean("usage"))
            .put("reason", systemPermissions.optBoolean("usage") ? JSONObject.NULL : "usage_permission_required"));
        for (java.util.Iterator<String> keys = diagnostics.keys(); keys.hasNext();) {
            String action = keys.next();
            if (!action.equals("usage_query")) nativeStatus.getJSONArray("nativeActions").put(action);
            if (diagnostics.getJSONObject(action).optBoolean("available")) nativeStatus.getJSONArray("availableActions").put(action);
        }
        org.json.JSONArray toolStates = new org.json.JSONArray();
        for (int i = 0; i < catalog.tools().length(); i++) {
            JSONObject tool = catalog.tools().getJSONObject(i);
            if (HardwareRuntime.ACTIONS.contains(tool.getString("action"))) {
                JSONObject state = HardwareToolState.describe(tool, grants, policy.isStopped() || ProductPermissions.stopped(context), hardwareStatus);
                toolStates.put(state);
                nativeStatus.getJSONArray("nativeActions").put(tool.getString("action"));
                if (state.optBoolean("available")) nativeStatus.getJSONArray("availableActions").put(tool.getString("action"));
                continue;
            }
            String action = tool.getString("action");
            if (isMemoryAction(action)) {
                JSONObject state = memoryAvailability(tool, grants, policy.isStopped(), memoryStatus);
                toolStates.put(state);
                nativeStatus.getJSONArray("nativeActions").put(action);
                if (state.optBoolean("available")) nativeStatus.getJSONArray("availableActions").put(action);
                continue;
            }
            org.json.JSONArray reasons = new org.json.JSONArray();
            if (!tool.getString("action").equals("capabilities")) {
                if (policy.isStopped()) reasons.put(NativeLocale.text("Android Use 操作已暂停；用户可在权限设置中恢复", "Android Use operations are paused. The user can resume them in permission settings"));
                org.json.JSONArray required = tool.getJSONArray("permissions");
                for (int j = 0; j < required.length(); j++) if (!grants.optBoolean(required.getString(j)))
                    reasons.put(NativeLocale.text("产品未授权：", "Not authorized in product settings: ") + catalog.group(required.getString(j)).getString("label"));
                if (tool.optBoolean("screen") && NativeRuntimeHost.isFilePickerOpen()) reasons.put(NativeLocale.text("请先完成系统文件选择", "Complete the system file selection first"));
            }
            boolean productAllowed = reasons.length() == 0;
            JSONObject systemState = diagnostics.optJSONObject(tool.getString("action"));
            if (systemState != null && !systemState.optBoolean("available"))
                reasons.put(systemState.optString("reason", NativeLocale.text("系统权限或诊断数据暂不可用", "System permissions or diagnostic data are temporarily unavailable")));
            if (Set.of("node_action", "global_action").contains(tool.getString("action"))
                    && !nativeStatus.optBoolean("accessibilityConnected")) reasons.put("accessibility_unavailable");
            toolStates.put(new JSONObject().put("action", tool.getString("action")).put("name", tool.getString("name"))
                .put("productAllowed", productAllowed).put("available", reasons.length() == 0).put("reasons", reasons));
        }
        return nativeStatus
            .put("hardware", hardwareStatus)
            .put("processMemory", memoryStatus)
            .put("audioCapture", audioCapture)
            .put("audioTranscription", audioTranscription)
            .put("product", new JSONObject().put("observeAllowed", ProductPermissions.allowed(settings, "allowObserve"))
                .put("controlAllowed", ProductPermissions.allowed(settings, "allowControl"))
                .put("screenshotsAllowed", ProductPermissions.allowed(settings, "allowScreenshot")).put("stopped", policy.isStopped())
                .put("productUiProtected", false).put("filePickerProtected", NativeRuntimeHost.isFilePickerOpen()).put("grants", grants))
            .put("groups", groupStates).put("tools", toolStates).put("systemPermissions", systemPermissions)
            .put("diagnostics", diagnostics)
            .put("progress", progress.history())
            .put("executionProgress", ToolProgress.current(context))
            .put("productUiRule", NativeLocale.text("自身对话、设置和浏览器界面可按已授权任务读取、截图和操作；能力按安装选择和后续设置生效，新增能力默认关闭。系统文件选择仍由用户完成，所有工具服从能力授权、系统权限和停止控制。", "Droy conversation, settings and browser screens may be read, captured and operated for authorized tasks. Capabilities follow installation choices and later settings; new capabilities default to off. The user must complete system file selection. All tools obey capability authorization, system permissions and stop controls."));
    }

    static JSONObject memoryAvailability(JSONObject tool, JSONObject grants, boolean stopped,
                                         JSONObject backend) throws org.json.JSONException {
        String action = tool.getString("action");
        JSONObject declared = tool.optJSONObject("operationPermissions");
        JSONObject operations = new JSONObject();
        if (declared != null) {
            for (java.util.Iterator<String> keys = declared.keys(); keys.hasNext();) {
                String operation = keys.next();
                org.json.JSONArray required = new org.json.JSONArray(tool.getJSONArray("permissions").toString());
                org.json.JSONArray specific = declared.getJSONArray(operation);
                for (int p = 0; p < specific.length(); p++) required.put(specific.getString(p));
                operations.put(operation, memoryOperationAvailability(required, grants, stopped, backend,
                    Set.of("status", "close").contains(operation)));
            }
        } else if (action.equals("memory_scan")) {
            for (String operation : new String[]{"start", "continue", "refine", "results", "discard"})
                operations.put(operation, memoryOperationAvailability(tool.getJSONArray("permissions"), grants,
                    stopped, backend, operation.equals("discard")));
        }
        JSONObject state;
        if (operations.length() == 0) {
            state = memoryOperationAvailability(tool.getJSONArray("permissions"), grants, stopped, backend, false);
        } else {
            boolean available = false, productAllowed = false;
            for (java.util.Iterator<String> keys = operations.keys(); keys.hasNext();) {
                JSONObject operation = operations.getJSONObject(keys.next());
                available |= operation.getBoolean("available");
                productAllowed |= operation.getBoolean("productAllowed");
            }
            state = new JSONObject().put("available", available).put("productAllowed", productAllowed)
                .put("reasons", new org.json.JSONArray()).put("operations", operations);
        }
        return state.put("action", action).put("name", tool.getString("name"));
    }

    private static JSONObject memoryOperationAvailability(org.json.JSONArray required, JSONObject grants,
            boolean stopped, JSONObject backend, boolean local) throws org.json.JSONException {
        org.json.JSONArray reasons = new org.json.JSONArray();
        if (!local) {
            if (stopped) reasons.put("operations_paused");
            for (int i = 0; i < required.length(); i++) {
                String permission = required.getString(i);
                if (!grants.optBoolean(permission)) reasons.put("product_permission_required:" + permission);
            }
            if (!grants.optBoolean("root")) reasons.put("root_not_authorized");
        }
        boolean productAllowed = reasons.length() == 0;
        if (!local && !backend.optBoolean("supported"))
            reasons.put(backend.optString("reason", "memory_backend_unavailable"));
        return new JSONObject().put("available", reasons.length() == 0).put("productAllowed", productAllowed)
            .put("localOnly", local).put("targetAccess", local ? "not_required" : "checked_on_open")
            .put("reasons", reasons);
    }

    private void checkPermission(JSONObject tool, boolean rootRequested) throws org.json.JSONException {
        if (tool.optBoolean("screen") && MemoryRuntime.get(context).ownUiVisible())
            throw new ProductPermissionDenied("private_droy_ui", NativeLocale.text("Droy 的私人记忆与对话界面仅供用户查看；请切换到目标应用后继续", "Droy private memory and conversation screens are for the user only. Switch to the target app before continuing"));
        // The system picker grants persistent access to user-selected documents;
        // opening Droy's own UI does not extend that separate authorization flow.
        if (tool.optBoolean("screen") && NativeRuntimeHost.isFilePickerOpen())
            throw new ProductPermissionDenied("picker_visible", NativeLocale.text("文件选择界面仅供用户操作；请完成选择后继续", "The file picker is for the user only. Complete the selection before continuing"));
        try {
            CapabilityAuthorization.check(tool, catalog::group, key -> ProductPermissions.allowed(settings, key), rootRequested);
        } catch (SecurityException denied) {
            throw new ProductPermissionDenied(rootRequested && !ProductPermissions.allowed(settings, "allowRoot")
                ? "root_not_authorized" : "permission_revoked", denied.getMessage());
        }
    }

    private static long budget(Object value) {
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).longValue()
                || ((Number) value).longValue() < 1 || ((Number) value).longValue() > 120000)
            throw new IllegalArgumentException("budgetMs must be an integer between 1 and 120000");
        return ((Number) value).longValue();
    }

    private static long defaultBudget(String action, JSONObject args) {
        if (HardwareRuntime.ACTIONS.contains(action)) return Math.max(15000, Math.min(60000,
            Math.max(args.optLong("durationMs", 0), args.optLong("timeoutMs", 0))) + 5000);
        if (action.equals("sequence")) return Math.min(60000, args.optLong("timeoutMs", 30000));
        if (args.has("afterCondition")) return Math.max(15000, Math.min(60000, args.optLong("afterTimeoutMs", 2000)) + 5000);
        if (action.startsWith("browser_")) return Math.max(15000, Math.min(60000, args.optLong("timeoutMs", 15000)) + 10000);
        if (action.equals("wait_for")) return Math.max(1000, Math.min(60000, args.optLong("timeoutMs", 10000)) + 1500);
        if (action.equals("compute_run")) return Math.max(15000, Math.min(30000, args.optLong("timeoutMs", 5000)) + 10000);
        return 15000;
    }

    private static String outcome(String status) {
        return switch (status) {
            case "timed_out" -> NativeLocale.text("等待或计算超时；不会自动重试", "Waiting or computation timed out. It will not retry automatically");
            case "ambiguous" -> NativeLocale.text("有多个候选，需要选择", "Multiple candidates need a selection");
            case "conflict" -> NativeLocale.text("与当前状态冲突，未覆盖", "Conflict with the current state. No overwrite was made");
            case "failed" -> NativeLocale.text("执行失败，请检查结果", "Execution failed. Check the result");
            default -> NativeLocale.text("工具已返回，请查看结果", "The tool returned. Check the result");
        };
    }

    private static Response ok(JSONObject result) throws Exception { return response(Response.Status.OK, new JSONObject().put("ok", true).put("result", result)); }
    private static JSONObject failure(String code, String message) {
        try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static JSONObject failure(String code, String message, String reason, CallTrace trace) {
        JSONObject result = failure(code, message);
        try {
            JSONObject error = result.getJSONObject("error");
            if (reason != null && !reason.isBlank()) error.put("reason", reason);
            boolean dispatched = trace.wasDispatched();
            if (trace.dispatchTracked || trace.readOnly || !trace.dispatchEntered) error.put("dispatched", dispatched);
            error.put("outcomeUnknown", dispatched || (trace.dispatchEntered && !trace.dispatchTracked && !trace.readOnly));
            error.put("nativeTiming", trace.timing());
            return result;
        } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    private static JSONObject actionFailure(ActionException failure, CallTrace trace) {
        JSONObject result = failure(failure.getCode(), failure.getMessage(), failure.getReason(), trace);
        JSONObject details = failure.getDetails();
        if (details == null) return result;
        try {
            JSONObject error = result.getJSONObject("error");
            for (java.util.Iterator<String> keys = details.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!error.has(key)) error.put(key, details.get(key));
            }
            return result;
        } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    private static final class ProductPermissionDenied extends SecurityException {
        final String reason;
        ProductPermissionDenied(String reason, String message) { super(message); this.reason = reason; }
    }

    private static final class CallTrace {
        final long startedAt = SystemClock.elapsedRealtime();
        ControlPolicy.Ticket ticket;
        long authorizationMs, dispatchMs, afterObservationMs, dispatchedAtMs = -1;
        boolean dispatchEntered, dispatchTracked, readOnly, sharedTicket;
        boolean wasDispatched() { return sharedTicket ? dispatchedAtMs >= 0 : ticket != null && ticket.wasDispatched(); }
        JSONObject timing() throws org.json.JSONException {
            JSONObject timing = new JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                .put("authorizationMs", authorizationMs).put("dispatchMs", dispatchMs)
                .put("afterObservationMs", afterObservationMs);
            if (dispatchedAtMs >= 0) timing.put("dispatchedAtMs", dispatchedAtMs);
            return timing;
        }
    }
    private static Response response(Response.Status status, JSONObject value) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}


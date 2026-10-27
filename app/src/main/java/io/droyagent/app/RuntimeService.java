package io.droyagent.app;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import org.json.JSONObject;

public final class RuntimeService extends Service {
    static volatile RuntimeService instance;
    static volatile String status = NativeLocale.text("引擎尚未启动", "Engine has not started");
    static volatile String webToken = "";
    static volatile int webPort;
    static volatile boolean ready;
    private static volatile Process lastProcess;
    private static ExecutorService lastWorker;
    private static volatile String lastDiagnostics = NativeLocale.text("引擎未运行", "Engine is not running");
    private static boolean historyMaintenance;
    private static ProductBridge statusBridge;
    private static final java.util.concurrent.atomic.AtomicBoolean memoryRestarting = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private volatile Process process;
    private volatile boolean stopping;
    private volatile boolean starting;
    private volatile boolean idleStopping;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopWhenIdle = this::stopIfBackgroundIdle;
    private volatile String terminalStatus;
    private volatile RuntimeRelease runningRelease;
    private final Object launchLock = new Object();
    private ProductBridge bridge;
    static final String STOP = "io.droyagent.STOP";
    static final String PAUSE = "io.droyagent.PAUSE_OPERATIONS";
    static final String CANCEL_TASK = "io.droyagent.CANCEL_TASK";
    static final String REFRESH = "io.droyagent.REFRESH_BACKGROUND";

    @Override public void onCreate() {
        super.onCreate();
        synchronized (RuntimeService.class) { lastWorker = worker; instance = this; }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManagement.refreshAccess(this);
        if (intent != null && STOP.equals(intent.getAction())) {
            HardwareRuntime.stopAll(this);
            BackgroundRuntime.stoppedByUser(this); stopGracefully(); return START_NOT_STICKY;
        }
        if (intent != null && CANCEL_TASK.equals(intent.getAction())) {
            String task = intent.getStringExtra("taskId");
            new Thread(() -> {
                try { PetActions.send(task, "cancel", null, null); }
                catch (Exception failure) { main.post(() -> android.widget.Toast.makeText(this,
                    NativeLocale.text("未能停止任务，请打开对话查看", "Unable to stop the task. Open the conversation to check"), android.widget.Toast.LENGTH_LONG).show()); }
            }, "droy-stop-task").start();
            return restartMode();
        }
        if (intent != null && PAUSE.equals(intent.getAction())) {
            pauseOperations(this);
            if (starting || (process != null && process.isAlive())) updateNotification(); else stopSelf();
            return restartMode();
        }
        if (intent == null && !BackgroundRuntime.keepRunning(this)) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && REFRESH.equals(intent.getAction()) && BackgroundRuntime.explicitlyStopped(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (intent != null && !REFRESH.equals(intent.getAction())) BackgroundRuntime.startedByUser(this);
        synchronized (RuntimeService.class) {
            if (historyMaintenance) { status = NativeLocale.text("正在删除对话记录，请稍后启动", "Deleting conversation history. Start again shortly"); stopSelf(); return START_NOT_STICKY; }
        }
        if (stopping) return START_NOT_STICKY;
        if (starting || (process != null && process.isAlive())) { scheduleBackgroundCheck(); return restartMode(); }
        starting = true;
        stopping = false;
        ForegroundStatusNotifications.startRuntime(this);
        PetOverlay.refresh(this);
        AutomationScreenOverlay.refresh(this);
        status = NativeLocale.text("正在准备手机运行环境…", "Preparing the mobile runtime…");
        AudioTranscription.get(this).initialize();
        worker.submit(this::startEngine);
        return restartMode();
    }

    private int restartMode() { return BackgroundRuntime.keepRunning(this) ? START_STICKY : START_NOT_STICKY; }

    @Override public void onTaskRemoved(Intent rootIntent) {
        scheduleBackgroundCheck();
        super.onTaskRemoved(rootIntent);
    }

    static void refreshBackgroundPolicy() {
        RuntimeService active = instance;
        if (active != null) active.scheduleBackgroundCheck();
    }

    private void scheduleBackgroundCheck() {
        main.removeCallbacks(stopWhenIdle);
        // Let foreground transitions and completion navigation settle before releasing an idle engine.
        main.postDelayed(stopWhenIdle, 1000);
    }

    private void stopIfBackgroundIdle() {
        if (stopping || BackgroundRuntime.visible() || BackgroundRuntime.keepRunning(this)
                || ChatFileTransfers.hasRuntimeWork()
                || MemoryRuntime.get(this).requiresBackgroundRuntime()
                || ToolProgress.current(this).optBoolean("active") || AudioTranscription.get(this).hasActiveJobs()
                || VoiceRuntime.active(this)) return;
        idleStopping = true;
        stopGracefully();
    }

    static String notificationTitle(boolean ready, boolean active, String phase) {
        return NotificationCopy.title(ready, active, phase, 0);
    }

    static void refreshNotificationStatus() {
        RuntimeService active = instance;
        if (active != null) ForegroundStatusNotifications.refresh(active);
    }

    static String executionPhaseLabel(String phase) {
        return switch (phase) {
            case "thinking" -> NativeLocale.text("等待模型响应", "Waiting for model response");
            case "executing" -> NativeLocale.text("正在执行工具", "Running tools");
            case "verifying" -> NativeLocale.text("正在核实执行结果", "Verifying the result");
            case "waiting" -> NativeLocale.text("等待目标时间或条件", "Waiting for the target time or condition");
            case "completed" -> NativeLocale.text("本轮执行已结束", "This turn has finished");
            case "interrupted" -> NativeLocale.text("任务已中断，请核实当前状态", "Task interrupted. Check the current state");
            case "waiting_input" -> NativeLocale.text("等待你的回应", "Waiting for your response");
            case "blocked" -> NativeLocale.text("任务遇到了阻碍", "The task is blocked");
            default -> "";
        };
    }

    static void refreshExecutionProgress() {
        RuntimeService active = instance;
        if (active != null) {
            active.updateNotification(); PetOverlay.refresh(active); AutomationScreenOverlay.refresh(active);
            active.scheduleBackgroundCheck();
        }
    }

    static void taskCompleted(String taskId) {
        RuntimeService active = instance;
        if (active == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (instance != active || !ready || active.stopping
                    || !active.getSharedPreferences("product", MODE_PRIVATE).getBoolean("returnToDroyOnCompletion", false)) return;
            JSONObject progress = ToolProgress.current(active);
            // A new task can begin while the completion notification waits for the UI thread.
            if (taskId.equals(progress.optString("taskId")) && !progress.optBoolean("active")
                    && "completed".equals(progress.optString("phase"))) MainActivity.revealTask(active, taskId);
        });
    }

    private String executionProgressDescription() {
        JSONObject progress = ToolProgress.current(this);
        String label = executionPhaseLabel(progress.optString("phase"));
        if (label.isEmpty()) return "";
        long startedAt = progress.optLong("startedAt");
        if (progress.optBoolean("active") && startedAt > 0) {
            long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
            label += NativeLocale.text("（当前阶段已用 ", " (Time in current phase: ") + seconds + NativeLocale.text(" 秒）", " seconds)");
        }
        return NativeLocale.text("\n当前阶段：", "\nCurrent phase: ") + label;
    }

    private void updateNotification() {
        if (!stopping && (starting || ready || (process != null && process.isAlive())))
            ForegroundStatusNotifications.refresh(this);
    }

    static synchronized boolean hasActiveSession() {
        return ready || instance != null || (lastWorker != null && !lastWorker.isTerminated());
    }

    private void startEngine() {
        try {
            MemoryRuntime memoryHost = MemoryRuntime.get(this);
            memoryHost.prepareRuntimeStart();
            RuntimeInstaller.installCertificate(this);
            File terminfo = RuntimeInstaller.installTerminfo(this);
            File runtime = RuntimeInstaller.install(this, memoryHost.protectionRequired());
            RuntimeRelease release = RuntimeInstaller.release(this);
            runningRelease = release;
            if (stopping || Thread.currentThread().isInterrupted()) return;
            appendLog(NativeLocale.text("运行包校验与准备完成", "Runtime verified and prepared"));
            File home = new File(getFilesDir(), "dsh-home"), workspace = new File(getFilesDir(), "workspace");
            if ((!home.isDirectory() && !home.mkdirs()) || (!workspace.isDirectory() && !workspace.mkdirs())) throw new IOException(NativeLocale.text("无法创建会话目录", "Unable to create the conversation directory"));
            webToken = randomToken();
            String bridgeToken = randomToken();
            if (bridge != null) bridge.stop();
            synchronized (launchLock) {
                if (stopping) return;
                bridge = new ProductBridge(this, bridgeToken);
                bridge.start(10000, false);
            }
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { webPort = socket.getLocalPort(); }
            String executable = getApplicationInfo().nativeLibraryDir + "/libdroy_node.so";
            File executableDirectory = prepareExecutables();
            Map<String, String> env = new HashMap<>();
            env.put("HOME", home.getAbsolutePath());
            env.put("DSH_HOME", home.getAbsolutePath());
            env.put("DROY_MEDIA_INPUT_DIR", new File(getFilesDir(), "media-input/v1").getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("SHELL", new File(executableDirectory, "bash").getAbsolutePath());
            env.put("PATH", executableDirectory.getAbsolutePath() + ":" + getApplicationInfo().nativeLibraryDir + ":/system/bin");
            env.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
            env.put("NODE_EXTRA_CA_CERTS", new File(getFilesDir(), "ca-certificates.crt").getAbsolutePath());
            env.put("DROY_LOCALE", NativeLocale.active());
            env.put("DROY_NATIVE_DIR", getApplicationInfo().nativeLibraryDir);
            env.put("DROY_RUNTIME_DIR", runtime.getAbsolutePath());
            env.put("DROY_BASH_PATH", new File(executableDirectory, "bash").getAbsolutePath());
            env.put("DROY_RG_PATH", new File(executableDirectory, "rg").getAbsolutePath());
            env.put("TERM", "xterm-256color");
            env.put("TERMINFO", terminfo.getAbsolutePath());
            env.put("DROY_WEB_TOKEN", webToken);
            env.put("DROY_WEB_PORT", String.valueOf(webPort));
            env.put("DROY_BRIDGE_TOKEN", bridgeToken);
            env.put("DROY_BRIDGE_PORT", String.valueOf(bridge.getListeningPort()));
            env.put("DROY_MEMORY_PROTECTION_REQUIRED", memoryHost.protectionRequired() ? "1" : "0");
            env.put("DROY_HOST_UID", Integer.toString(android.os.Process.myUid()));
            env.put("DSH_TOOLS_MODE", "native");
            env.put("DSH_TELEMETRY_DISABLED", "1");
            env.put("NO_COLOR", "1");
            env.put("TZ", TimeZone.getDefault().getID());
            runBootstrap(executable, runtime, workspace, env);
            if (stopping) return;
            appendLog(NativeLocale.text("手机运行配置已准备", "Mobile runtime configuration prepared"));
            ProcessBuilder builder = new ProcessBuilder(executable, "--expose-internals",
                new File(runtime, "node_modules/@deepseek-ai/dsh/lib/bin.js").getAbsolutePath(),
                "--profile", "mobile", "--host", "127.0.0.1", "--port", String.valueOf(webPort), "--no-open");
            builder.directory(workspace).redirectErrorStream(true).environment().putAll(env);
            process = launch(builder);
            MemoryRuntime.get(this).runtimeStarted();
            Process launched = process;
            synchronized (launchLock) { if (!stopping) status = NativeLocale.text("正在启动引擎…", "Starting the engine…"); }
            Thread reader = new Thread(() -> readOutput(launched), "droy-runtime-log");
            reader.start();
            for (int attempt = 0; attempt < 150 && !stopping; attempt++) {
                if (!process.isAlive()) throw new IOException(NativeLocale.text("DSH 退出（", "DSH exited (") + process.exitValue() + "）");
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + webPort + "/droy/ready").openConnection();
                    int code;
                    JSONObject engine = null;
                    try {
                        connection.setConnectTimeout(500); connection.setReadTimeout(1000);
                        connection.setInstanceFollowRedirects(false);
                        connection.setRequestProperty("Cookie", "droy_session=" + webToken);
                        code = connection.getResponseCode();
                        if (code == 200) {
                            try (InputStream input = connection.getInputStream()) {
                                ByteArrayOutputStream response = new ByteArrayOutputStream();
                                byte[] chunk = new byte[1024];
                                int count;
                                while ((count = input.read(chunk)) != -1) {
                                    if (response.size() + count > 4096) throw new IOException(NativeLocale.text("引擎就绪响应过大", "Engine readiness response is too large"));
                                    response.write(chunk, 0, count);
                                }
                                engine = new JSONObject(new String(response.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                    } finally { connection.disconnect(); }
                    if (code == 200) {
                        try { release.verifyReady(engine); }
                        catch (IOException mismatch) { throw new IllegalStateException(mismatch.getMessage(), mismatch); }
                        synchronized (launchLock) {
                            if (!stopping) {
                                ready = true; status = NativeLocale.text("引擎运行中", "Engine running");
                                applyOperationState();
                                appendLog(NativeLocale.text("DSH 服务与 Android 工具已就绪", "DSH service and Android tools are ready"));
                                updateNotification();
                                scheduleBackgroundCheck();
                                DroyNotificationService.processCurrentNotifications(this);
                            }
                        }
                        break;
                    }
                } catch (IOException unavailable) { /* Readiness is bounded and process failure is checked above. */ }
                Thread.sleep(400);
            }
            if (!ready && !stopping) throw new IOException(NativeLocale.text("DSH 启动超时，请查看诊断", "DSH startup timed out. Check diagnostics"));
            int code = process.waitFor();
            recordTerminalStatus(NativeLocale.text("引擎已退出（", "Engine exited (") + code + NativeLocale.text("），可重新启动", "). You can restart it"));
        } catch (Exception error) {
            if (MemoryRuntime.get(this).protectionRequired()) MemoryRuntime.get(this).startupFailed();
            if (recordTerminalStatus(NativeLocale.text("启动失败：", "Startup failed: ") + error.getMessage())) appendLog(error.toString());
        } finally {
            MemoryRuntime.get(this).runtimeStopped();
            synchronized (launchLock) {
                stopping = true;
                if (instance == this) ready = false;
                stopOperations();
                if (process != null) process.destroyForcibly();
                if (bridge != null) bridge.stop();
            }
            starting = false;
            // Every terminal path must release the foreground service as well as
            // the child; otherwise its notification and history lock remain live.
            stopSelf();
        }
    }

    private File prepareExecutables() throws IOException {
        File directory = new File(getFilesDir(), "bin");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(NativeLocale.text("无法准备运行命令目录", "Unable to prepare the runtime command directory"));
        for (String name : new String[]{"node", "bash", "rg"}) {
            Path link = new File(directory, name).toPath();
            Path target = new File(getApplicationInfo().nativeLibraryDir, "libdroy_" + name + ".so").toPath();
            if (!Files.isRegularFile(target)) throw new IOException(NativeLocale.text("运行环境缺少 ", "Runtime is missing ") + name);
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isSymbolicLink(link)) throw new IOException(NativeLocale.text("运行命令路径被占用：", "Runtime command path is occupied: ") + name);
                if (Files.readSymbolicLink(link).equals(target)) continue;
                Files.delete(link);
            }
            Files.createSymbolicLink(link, target);
        }
        return directory;
    }

    private boolean recordTerminalStatus(String message) {
        synchronized (launchLock) {
            if (stopping) return false;
            terminalStatus = message;
            status = message;
            stopping = true;
            ready = false;
            return true;
        }
    }

    private void runBootstrap(String executable, File runtime, File workspace, Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(executable, "--expose-internals", new File(runtime, "mobile/bootstrap.mjs").getAbsolutePath());
        builder.directory(workspace).redirectErrorStream(true).environment().putAll(env);
        process = launch(builder);
        Process bootstrap = process;
        Thread reader = new Thread(() -> readOutput(bootstrap), "droy-bootstrap-log");
        reader.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(NativeLocale.text("运行配置初始化超时", "Runtime configuration initialization timed out")); }
        reader.join(1000);
        if (process.exitValue() != 0) throw new IOException(NativeLocale.text("运行配置初始化失败（", "Runtime configuration initialization failed (") + process.exitValue() + "）");
    }

    private Process launch(ProcessBuilder builder) throws IOException, InterruptedException {
        synchronized (launchLock) {
            if (stopping || Thread.currentThread().isInterrupted()) throw new InterruptedException(NativeLocale.text("引擎已停止", "Engine stopped"));
            Process child = builder.start();
            process = child;
            lastProcess = child;
            return child;
        }
    }

    static synchronized boolean canManageHistory() {
        Process child = lastProcess;
        return !historyMaintenance && instance == null && (child == null || !child.isAlive())
            && !MemoryExecutionQualification.hasActiveProcesses()
            && (lastWorker == null || lastWorker.isTerminated());
    }

    static synchronized boolean beginHistoryChange() {
        if (!canManageHistory()) return false;
        historyMaintenance = true;
        return true;
    }

    static synchronized void endHistoryChange() {
        historyMaintenance = false;
    }

    private void readOutput(Process source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Provider/plugin output may include arbitrary content or credentials.
                // Export only recognized error identifiers, never the original line.
                String code = DiagnosticCodes.fromRuntimeOutput(line);
                if (code != null) appendLog(NativeLocale.text("运行时提示：", "Runtime notice: ") + code);
            }
        } catch (IOException e) { if (!stopping) appendLog(NativeLocale.text("读取运行日志失败：", "Unable to read runtime logs: ") + e.getClass().getSimpleName()); }
    }

    private synchronized void appendLog(String line) {
        String safe = line.replaceAll("(?i)(bearer\\s+|api[_-]?key[\\s:=]+)[^\\s,]+", "$1[redacted]")
            .replaceAll("sk-[A-Za-z0-9_-]+", "[redacted]");
        if (!webToken.isEmpty()) safe = safe.replace(webToken, "[redacted]");
        log.addLast(safe); while (log.size() > 120) log.removeFirst();
    }
    synchronized String diagnostics() {
        RuntimeRelease release = runningRelease;
        String versions = release == null ? NativeLocale.text("Droy · 运行包尚未校验", "Droy · Runtime has not been verified") : "Droy " + release.droyVersion
            + " · DSH " + release.dshVersion + NativeLocale.text(" · Android 适配 ", " · Android adapter ") + release.mobileVersion;
        return versions + "\nAndroid " + android.os.Build.VERSION.RELEASE
            + " / API " + android.os.Build.VERSION.SDK_INT + executionProgressDescription()
            + "\n" + String.join("\n", log);
    }
    static String diagnosticsSnapshot() {
        RuntimeService active = instance;
        return active != null ? active.diagnostics() : NativeLocale.generatedStatus(lastDiagnostics);
    }
    void allowOperations() {
        synchronized (launchLock) {
            if (!stopping && ready && bridge != null) resumeOperations(this);
        }
    }
    static void resumeOperations(Context context) {
        ProductPermissions.setStopped(context, false);
        MemoryRuntime.get(context).operationsChanged();
        applySavedOperationState();
        RuntimeService active = instance;
        if (active != null) active.updateNotification();
    }
    static void pauseOperations(Context context) {
        ProductPermissions.setStopped(context, true);
        HardwareRuntime.stopAll(context);
        MemoryRuntime.get(context).operationsChanged();
        VoiceRuntime.stop("operations_paused");
        AudioCapture.get(context).stopAll("operations_paused");
        AudioTranscription.get(context).stopAll("operations_paused");
        io.droyagent.androiduse.ProcessMemoryTools.get(context).closeAll();
        RuntimeService active = instance;
        if (active != null) { active.stopOperations(); active.updateNotification(); }
        NotificationManagement.get(context).rescan();
    }
    static void applySavedOperationState() {
        RuntimeService active = instance;
        if (active != null) {
            MemoryRuntime.get(active).operationsChanged();
            active.applyOperationState();
            NotificationManagement.get(active).rescan();
        }
    }
    static void memoryPermissionsChanged(Context context) {
        MemoryRuntime.get(context).rootPermissionChanged();
        android.content.SharedPreferences choices = ProductPermissions.preferences(context);
        io.droyagent.androiduse.ProcessMemoryTools.get(context).onPermissionsChanged(
            ProductPermissions.allowed(choices, "allowApps"),
            ProductPermissions.allowed(choices, "allowMemoryRead"),
            ProductPermissions.allowed(choices, "allowMemoryWrite"),
            ProductPermissions.allowed(choices, "allowRoot"));
    }
    private void applyOperationState() {
        synchronized (launchLock) {
            if (!stopping && ready && bridge != null) {
                boolean paused = ProductPermissions.stopped(this);
                if (paused && !bridge.policy.isStopped()) {
                    bridge.policy.stop();
                    HardwareRuntime.stopAll(this);
                    io.droyagent.androiduse.ProcessMemoryTools.get(this).closeAll();
                }
                else if (!paused && bridge.policy.isStopped()) bridge.policy.enable();
            }
        }
    }
    void stopOperations() {
        if (bridge != null) bridge.policy.stop();
        HardwareRuntime.releaseTransient(this);
        io.droyagent.androiduse.ProcessMemoryTools.get(this).closeAll();
        ToolProgress.clearScreenOperations();
        AutomationScreenOverlay.refresh(this);
    }

    static void requestStop() {
        RuntimeService active = instance;
        if (active != null) requestStop(active);
    }

    static void restartForMemoryProtection(Context context) {
        if (!memoryRestarting.compareAndSet(false,true)) return;
        Context app = context.getApplicationContext();
        // Reply to the old authenticated request before retiring its token/server.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            RuntimeService previous = instance;
            if (previous != null) previous.stopGracefully();
            new Thread(() -> {
                try {
                    long deadline = android.os.SystemClock.elapsedRealtime()+15000;
                    while (previous != null && (instance == previous || !previous.worker.isTerminated())) {
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("MEMORY_RESTART_STOP_UNCONFIRMED");
                        Thread.sleep(100);
                    }
                    app.startForegroundService(new Intent(app,RuntimeService.class));
                } catch (Exception failed) { MemoryRuntime.get(app).startupFailed(); }
                finally { memoryRestarting.set(false); }
            },"droy-memory-restart").start();
        },300);
    }

    static void requestStop(Context context) {
        HardwareRuntime.stopAll(context);
        MemoryRuntime.get(context).runtimeStopped();
        VoiceRuntime.stop("user_stop");
        AudioCapture.get(context).stopAll("user_stop");
        AudioTranscription.get(context).stopAll("user_stop");
        io.droyagent.androiduse.ProcessMemoryTools.get(context).closeAll();
        BackgroundRuntime.stoppedByUser(context);
        RuntimeService active = instance;
        if (active != null) active.stopGracefully();
    }

    private void stopGracefully() {
        MemoryRuntime.get(this).runtimeStopped();
        if (!idleStopping) {
            VoiceRuntime.stop("runtime_stopped");
            AudioCapture.get(this).stopAll("runtime_stopped");
            AudioTranscription.get(this).stopAll("runtime_stopped");
        }
        final Process child;
        final boolean wasReady;
        synchronized (launchLock) {
            if (stopping) return;
            stopping = true;
            wasReady = ready;
            ready = false;
            stopOperations();
            child = process;
        }
        status = NativeLocale.text("正在停止任务并保存会话…", "Stopping the task and saving the conversation…");
        if (!wasReady) worker.shutdownNow();
        // Android's main thread must remain free while DSH cancels and flushes.
        new Thread(() -> {
            try {
                if (wasReady && child != null && child.isAlive()) {
                    HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + webPort + "/droy/shutdown").openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(500); connection.setReadTimeout(2500);
                    connection.setRequestProperty("Cookie", "droy_session=" + webToken);
                    int code;
                    try { code = connection.getResponseCode(); } finally { connection.disconnect(); }
                    if (code == 200) appendLog(NativeLocale.text("会话已保存，正在退出引擎", "Conversation saved. Exiting the engine"));
                    else appendLog(NativeLocale.text("引擎未确认保存，正在结束进程", "The engine did not confirm saving. Terminating the process"));
                }
            } catch (IOException failure) { appendLog(NativeLocale.text("未收到保存确认：", "No save confirmation received: ") + failure.getClass().getSimpleName()); }
            finally {
                if (child != null) {
                    try {
                        if (!child.waitFor(500, TimeUnit.MILLISECONDS)) {
                            child.destroy();
                            if (!child.waitFor(1000, TimeUnit.MILLISECONDS)) child.destroyForcibly();
                        }
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
                }
                stopSelf();
            }
        }, "droy-graceful-stop").start();
    }

    static org.json.JSONObject executeProductAction(String action, org.json.JSONObject args) throws Exception {
        RuntimeService active = instance;
        if (active == null || active.bridge == null || !ready)
            throw new IllegalStateException(NativeLocale.text("请先启动引擎", "Start the engine first"));
        try { return active.bridge.executeTool(action, args, "product-user-action", java.util.UUID.randomUUID().toString()); }
        finally { active.bridge.policy.end("product-user-action"); }
    }

    static JSONObject audioRequest(JSONObject request, String owner) throws Exception {
        if (!"product-user-action".equals(owner)) throw new SecurityException(NativeLocale.text("无效的本机音频操作身份", "Invalid local audio operation identity"));
        RuntimeService active = instance;
        if (active != null && active.bridge != null) return active.bridge.audioRequestFromUi(request);
        synchronized (RuntimeService.class) {
            if (statusBridge == null) throw new IllegalStateException(NativeLocale.text("请先打开 Droy", "Open Droy first"));
            return statusBridge.audioRequestFromUi(request);
        }
    }

    static org.json.JSONObject capabilityStatus(Context context) {
        try {
            RuntimeService active = instance;
            if (active != null && active.bridge != null) return active.bridge.capabilityStatus();
            synchronized (RuntimeService.class) {
                if (statusBridge == null) statusBridge = new ProductBridge(context, "unused-status-token-not-a-listener");
                return statusBridge.capabilityStatus();
            }
        } catch (Exception failure) { throw new IllegalStateException(NativeLocale.text("能力状态查询失败", "Unable to query capability status"), failure); }
    }

    @Override public void onDestroy() {
        MemoryRuntime.get(this).runtimeStopped();
        main.removeCallbacksAndMessages(null);
        if (!idleStopping) VoiceRuntime.stop("runtime_stopped");
        synchronized (launchLock) {
            stopping = true; ready = false;
            stopOperations();
            if (process != null) { process.destroy(); process.destroyForcibly(); }
            if (bridge != null) bridge.stop();
        }
        worker.shutdownNow();
        NotificationManagement.get(this).rescan();
        BrowserSession.release();
        ForegroundStatusNotifications.stop(this);
        lastDiagnostics = diagnostics();
        status = terminalStatus != null ? terminalStatus : NativeLocale.text("引擎已停止；已执行的动作不会自动撤销", "Engine stopped. Completed actions are not automatically undone");
        instance = null;
        PetOverlay.runtimeStopped();
        AutomationScreenOverlay.runtimeStopped();
        super.onDestroy();
        if (idleStopping && BackgroundRuntime.visible() && !BackgroundRuntime.explicitlyStopped(this))
            startForegroundService(new Intent(this, RuntimeService.class).setAction(REFRESH));
    }

    static String randomToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return RuntimeInstaller.hex(bytes); }
}

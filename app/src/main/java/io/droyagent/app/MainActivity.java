package io.droyagent.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences preferences;
    private FrameLayout content, chatContainer, loadingOverlay;
    private View nativePage;
    private TextView runtimeMessage, diagnosticDetails;
    private TextView setupPreparationStatus;
    private Button runtimeStart;
    private WebView web;
    private ChatFileTransfers chatFiles;
    private io.droyagent.app.media.MediaInputController mediaInput;
    private ChatMicrophonePermission chatMicrophone;
    private ChatCameraPermission chatCamera;
    private PermissionSetup permissionSetup;
    private VoiceAuthentication voiceAuthentication;
    private android.media.MediaPlayer audioPlayer;
    private AlertDialog audioPlaybackDialog;
    private java.io.File pendingAudioExport;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener microphonePolicyChanged = (prefs, key) -> {
        boolean engineStopped=BackgroundRuntime.STOPPED.equals(key)&&BackgroundRuntime.explicitlyStopped(this);
        if (engineStopped || ((ProductPermissions.STOPPED.equals(key) || "allowAudioMicrophone".equals(key))
                && (ProductPermissions.stopped(this) || !ProductPermissions.allowed(prefs, "allowAudioMicrophone"))))
            runOnUiThread(() -> {
                stopChatMicrophone();
                if (chatCamera != null) chatCamera.cancel();
                if (web != null) web.evaluateJavascript("window.dispatchEvent(new Event('droy-voice-stop'))", null);
                if (engineStopped) stopAudioPlayback();
            });
    };
    private BrowserSurfaceHost browserSurface;
    private String pendingBrowserSession;
    private String revealingBrowserSession;
    private String pendingPetTask, revealingPetTask, pendingPetRequest, revealingPetRequest;
    private String pendingCompanion, revealingCompanion;
    private boolean pendingNotificationManagement;
    private long pendingVoiceWake;
    private String mobileCss;
    private static final Object UI_STATE_RESULT = new Object();
    private final java.util.concurrent.ExecutorService uiStateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
        task -> new Thread(task, "droy-ui-state"));
    private CapabilityCatalog capabilityCatalog;
    private String pushedInsets = "";
    private String activeThemeScheme;
    private int foreground = Color.rgb(25, 31, 39), background = Color.WHITE;
    private String loaded = "";
    private boolean chatVisible = true;
    private boolean imeVisible;
    private int safeTop, safeRight, safeBottom, safeLeft, imeBottom;
    private int systemGestureLeft, systemGestureRight;
    private boolean windowInsetsKnown;
    private boolean webUnavailable;
    private String webFailure;
    private String failedSession = "";
    private int selectedPage;
    private boolean visible;
    private boolean startingRequested;
    private long startRequestedAt;
    private boolean displayedRuntimeActive;
    private boolean uiReady;
    private String chatState = "";
    private long visualRequestId;
    private long uiNavigationId;
    private Runnable uiLoadTimeout;
    private boolean historyChanging;
    private Runnable unregisterBack;
    private Runnable registerBack;
    private boolean backFocusSynchronized;
    private static final java.util.concurrent.atomic.AtomicInteger visibleActivities = new java.util.concurrent.atomic.AtomicInteger();

    static boolean isSensitiveUiVisible() { return visibleActivities.get() > 0 || AudioPermissionActivity.isVisible() || ChatFileTransfers.isPickerOpen()
        || io.droyagent.app.media.MediaInputController.isPickerOpen() || io.droyagent.app.media.ShareReceiveActivity.isVisible(); }

    private final Runnable refresh = new Runnable() {
        public void run() {
            if (isDestroyed()) return;
            if (runtimeMessage != null) setTextIfChanged(runtimeMessage, NativeLocale.generatedStatus(RuntimeService.status));
            if (setupPreparationStatus != null) setTextIfChanged(setupPreparationStatus, NativeLocale.generatedStatus(RuntimeService.status));
            if (diagnosticDetails != null) setTextIfChanged(diagnosticDetails, diagnosticText());
            if (runtimeStart != null) runtimeStart.setVisibility(RuntimeService.canStart() && !historyChanging ? View.VISIBLE : View.GONE);
            if (chatVisible) {
                if (RuntimeService.ready) showReadyChat();
                else {
                    if (startingRequested && !RuntimeService.isStartingOrRunning() && SystemClock.elapsedRealtime() - startRequestedAt >= 2000) startingRequested = false;
                    if (displayedRuntimeActive != (startingRequested || RuntimeService.isStartingOrRunning())) showChat();
                }
            }
            handler.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setDecorFitsSystemWindows(false);
        // The WebView draws through both system bars. Its CSS consumes insets
        // once, including the keyboard; platform resizing would subtract twice.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(attributes);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        preferences = getSharedPreferences("product", MODE_PRIVATE);
        SetupManager.initialize(this);
        permissionSetup = new PermissionSetup(this, this::refreshWebState, saved);
        mediaInput = new io.droyagent.app.media.MediaInputController(this, this::notifyMediaInputChanged);
        mediaInput.restore(saved);
        preferences.registerOnSharedPreferenceChangeListener(microphonePolicyChanged);
        chatMicrophone = new ChatMicrophonePermission(this);
        chatCamera = new ChatCameraPermission(this);
        voiceAuthentication = new VoiceAuthentication(this);
        pendingBrowserSession = getIntent().getStringExtra("browserSessionId");
        pendingPetTask = getIntent().getStringExtra("petTaskId");
        pendingPetRequest = getIntent().getStringExtra("petRequestId");
        pendingCompanion = companionTarget(getIntent());
        withdrawResolvedQuestion();
        pendingNotificationManagement = getIntent().getBooleanExtra("notificationManagement", false);
        pendingVoiceWake = getIntent().getLongExtra("voiceWakeEventId", 0);
        content = new FrameLayout(this);
        chatContainer = new FrameLayout(this);
        browserSurface = new BrowserSurfaceHost(this, chatContainer, () -> web);
        content.addView(chatContainer, new FrameLayout.LayoutParams(-1, -1));
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setTag("droy-chat-loading");
        loadingOverlay.setContentDescription(NativeLocale.text("正在准备对话", "Preparing your conversation"));
        loadingOverlay.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        loadingOverlay.setClickable(true);
        if (!getSharedPreferences("installation", MODE_PRIVATE).getBoolean("completed", false)) {
            LinearLayout welcome = new LinearLayout(this);
            welcome.setOrientation(LinearLayout.VERTICAL); welcome.setGravity(Gravity.CENTER);
            welcome.setPadding(dp(24), dp(48), dp(24), dp(48));
            ImageView logo = new ImageView(this); logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
            welcome.addView(logo, new LinearLayout.LayoutParams(dp(80), dp(80)));
            welcome.addView(text(NativeLocale.text("你好，我是 Droy", "Hi, I'm Droy"), 26));
            welcome.addView(text(NativeLocale.text("正在准备本地运行环境，随后开始配置。", "Preparing the local runtime. Setup will begin shortly."), 16));
            setupPreparationStatus = text(NativeLocale.generatedStatus(RuntimeService.status), 14); welcome.addView(setupPreparationStatus);
            loadingOverlay.addView(welcome, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ProgressBar progress = new ProgressBar(this);
            loadingOverlay.addView(progress, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        }
        chatContainer.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));
        applyNativeTheme();
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            updateWindowInsets(insets);
            return insets;
        });
        setContentView(content);
        content.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback callback = this::handleBack;
            registerBack = () -> getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            registerBack.run();
            unregisterBack = () -> getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
        selectedPage = saved == null ? 0 : saved.getInt("selectedPage", 0);
        // Only opening a fresh app session starts the runtime. Polling, returning
        // from settings and Activity restoration must never undo an explicit stop.
        if (saved == null) startRuntime();
        if (selectedPage == 2 && pendingCompanion == null) showDiagnostics(); else showChat();
        handler.post(refresh);
        if (getIntent().hasExtra("audioCaptureId")) openAudioAuthorization(getIntent().getStringExtra("audioCaptureId"));
    }

    static void revealBrowser(Context context, String sessionId) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("browserSessionId", sessionId);
        context.startActivity(intent);
    }
    static void revealTask(Context context, String taskId) {
        context.startActivity(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("petTaskId", taskId));
    }
    static void revealQuestion(Context context, String taskId, String requestId) {
        if (!ToolProgress.hasPending(taskId, requestId)) return;
        context.startActivity(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("petTaskId", taskId).putExtra("petRequestId", requestId));
    }
    void withdrawResolvedQuestion() {
        if (pendingPetRequest != null && !ToolProgress.hasPending(pendingPetTask, pendingPetRequest)) {
            pendingPetTask = null; revealingPetTask = null; revealingPetRequest = null; pendingPetRequest = null;
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String companion = companionTarget(intent);
        if (companion != null) {
            pendingCompanion = companion; showChat(); revealPendingCompanion();
        }
        if (intent.hasExtra("voiceWakeEventId")) {
            pendingVoiceWake = intent.getLongExtra("voiceWakeEventId", 0);
            showChat(); revealPendingVoice();
        }
        if (intent.hasExtra("mediaImportId")) { showChat(); notifyMediaInputChanged(); }
        if (intent.hasExtra("audioCaptureId")) openAudioAuthorization(intent.getStringExtra("audioCaptureId"));
        if (intent.hasExtra("browserSessionId")) {
            pendingBrowserSession = intent.getStringExtra("browserSessionId"); showChat(); revealPendingBrowser();
        }
        if (intent.hasExtra("petTaskId")) {
            pendingPetTask = intent.getStringExtra("petTaskId");
            pendingPetRequest = intent.getStringExtra("petRequestId");
            withdrawResolvedQuestion();
            if (pendingPetTask != null) { showChat(); revealPendingPetTask(); }
        }
        if (intent.getBooleanExtra("notificationManagement", false)) {
            pendingNotificationManagement = true; showChat(); revealNotificationManagement();
        }
    }
    private static String companionTarget(Intent intent) {
        String id = intent.getStringExtra("companionId");
        return ProactiveNotifications.isCompanionId(id) ? id : null;
    }
    private void revealPendingCompanion() {
        if (pendingCompanion == null || web == null || !uiReady || pendingCompanion.equals(revealingCompanion)) return;
        String id = pendingCompanion; revealingCompanion = id;
        web.evaluateJavascript("(()=>{const companionId=" + JSONObject.quote(id) + ";"
            + "const done=ok=>window.DroyNative.postMessage(JSON.stringify({id:" + JSONObject.quote(java.util.UUID.randomUUID().toString())
            + ",action:'companionRevealResult',args:{companionId,ok}}));"
            + "Promise.resolve().then(()=>window.DroyMobile.openCompanion(companionId)).then(()=>done(true),()=>done(false))})()", null);
    }
    private void revealNotificationManagement() {
        if (!pendingNotificationManagement || web == null || !uiReady) return;
        pendingNotificationManagement = false;
        web.evaluateJavascript("window.DroyMobile.openNotificationManagement()", null);
    }
    private void revealPendingVoice() {
        if (pendingVoiceWake == 0 || web == null || !uiReady) return;
        long eventId = pendingVoiceWake;
        pendingVoiceWake = 0;
        if (!VoiceWake.get(this).validWakeEvent(eventId)) { VoiceRuntime.clearWakeNotification(this); return; }
        String target = "standard";
        VoiceRuntime.clearWakeNotification(this);
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent('droy-voice-wake',{detail:{eventId:"
            + eventId + ",target:" + JSONObject.quote(target) + "}}))", null);
    }
    private void revealPendingPetTask() {
        withdrawResolvedQuestion();
        if (pendingPetTask == null || web == null || !uiReady || pendingPetTask.equals(revealingPetTask)
                && java.util.Objects.equals(pendingPetRequest, revealingPetRequest)) return;
        if (pendingPetTask.isEmpty()) { pendingPetTask = null; return; }
        String id = pendingPetTask, request = pendingPetRequest; revealingPetTask = id; revealingPetRequest = request;
        web.evaluateJavascript("(()=>{const taskId=" + JSONObject.quote(id) + ",requestId=" + (request == null ? "null" : JSONObject.quote(request)) + ";"
            + "const done=ok=>window.DroyNative.postMessage(JSON.stringify({id:" + JSONObject.quote(java.util.UUID.randomUUID().toString())
            + ",action:'petRevealResult',args:{taskId,requestId,ok}}));"
            + "Promise.resolve().then(()=>window.DroyMobile.revealPetTask({taskId,requestId})).then(()=>done(true),()=>done(false))})()", null);
    }
    private void revealPendingBrowser() {
        if (pendingBrowserSession == null || web == null || !uiReady || pendingBrowserSession.equals(revealingBrowserSession)) return;
        String id = pendingBrowserSession; revealingBrowserSession = id;
        String quoted = JSONObject.quote(id);
        String acknowledgementId = JSONObject.quote(java.util.UUID.randomUUID().toString());
        web.evaluateJavascript("(()=>{const api=window.DroyMobile,sessionId=" + quoted + ";"
            + "const done=ok=>window.DroyNative.postMessage(JSON.stringify({id:" + acknowledgementId + ","
            + "action:'browserRevealResult',args:{sessionId,ok}}));"
            + "Promise.resolve().then(()=>{if(!api?.revealBrowser)throw new Error(" + JSONObject.quote(NativeLocale.text("浏览器页面尚未就绪", "The browser page is not ready yet")) + ");"
            + "return api.revealBrowser({sessionId})}).then(()=>done(true),error=>{try{api?.reportBrowserError(error)}finally{done(false)}})})()", null);
    }

    private void startRuntime() {
        if (historyChanging || !RuntimeService.canStart()) return;
        startingRequested = true; startRequestedAt = SystemClock.elapsedRealtime();
        try { startForegroundService(new Intent(this, RuntimeService.class)); }
        catch (RuntimeException failure) {
            startingRequested = false;
            RuntimeService.status = NativeLocale.text("启动失败：", "Startup failed: ") + failure.getClass().getSimpleName();
            showActionFailure(NativeLocale.text("引擎未启动", "Engine has not started"), failure);
        }
    }

    private void showChat() {
        selectedPage = 0; chatVisible = true;
        runtimeMessage = null; runtimeStart = null; diagnosticDetails = null;
        dismissNativePage();
        if (webUnavailable) { showWebViewUpgrade(); return; }
        if (RuntimeService.ready) {
            showReadyChat();
            return;
        }
        boolean active = startingRequested || RuntimeService.isStartingOrRunning();
        displayedRuntimeActive = active;
        if (active) {
            chatState = "loading";
            showLoading();
            return;
        }
        chatState = "stopped";
        hideLoading(false);
        LinearLayout page = page();
        page.addView(text(NativeLocale.text("引擎已停止", "Engine stopped"), 24));
        runtimeMessage = text(NativeLocale.generatedStatus(RuntimeService.status), 15); page.addView(runtimeMessage);
        runtimeStart = button(NativeLocale.text("启动并进入对话", "Start and open conversation"), view -> { startRuntime(); showChat(); });
        runtimeStart.setVisibility(!RuntimeService.canStart() || historyChanging ? View.GONE : View.VISIBLE); page.addView(runtimeStart);
        page.addView(button(NativeLocale.text("关于与诊断", "About and diagnostics"), view -> showDiagnostics()));
        replace(scroll(page));
    }

    private void showReadyChat() {
        if (webUnavailable || !RuntimeService.ready) return;
        startingRequested = false;
        String url = "http://127.0.0.1:" + RuntimeService.webPort + "/";
        String token = RuntimeService.webToken;
        String session = url + token;
        if (webFailure != null) {
            if (failedSession.equals(session)) {
                if (!chatState.equals("failure") || nativePage == null) showWebFailure();
                return;
            }
            webFailure = null;
        }
        if (web != null && loaded.equals(session)) {
            if (chatVisible && nativePage != null) dismissNativePage();
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { showWebViewUpgrade(); return; }
        discardWebView();
        dismissNativePage();
        chatState = "loading";
        displayedRuntimeActive = true;
        showLoading();
        final int sessionPort = RuntimeService.webPort;
        web = new WebView(this);
        web.setBackgroundColor(background);
        web.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) pushWindowInsets();
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setMediaPlaybackRequiresUserGesture(true);
        WebView fileOwner = web;
        chatFiles = new ChatFileTransfers(this, sessionPort,
            () -> isCurrentWebSession(fileOwner, session, sessionPort), this::showActionFailure);
        ChatFileTransfers transfers = chatFiles;
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                long navigation = uiNavigationId;
                java.util.function.BooleanSupplier current = () ->
                    isCurrentWebSession(fileOwner, session, sessionPort) && uiNavigationId == navigation
                        && fileOwner.getUrl() != null && isOwnUrl(Uri.parse(fileOwner.getUrl()), sessionPort);
                if (NativeUiProtocol.trustedCameraRequest(request.getOrigin().toString(), sessionPort, request.getResources()))
                    chatCamera.request(request, sessionPort, current);
                else chatMicrophone.request(request, sessionPort, current);
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                chatMicrophone.cancelled(request); chatCamera.cancelled(request);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!isCurrentWebSession(view, session, sessionPort) || view.getUrl() == null || !isOwnUrl(Uri.parse(view.getUrl()), sessionPort)) {
                    callback.onReceiveValue(null); return true;
                }
                return transfers.chooseFiles(callback, params);
            }
        });
        web.setDownloadListener((address, userAgent, disposition, mime, length) -> {
            if (!isCurrentWebSession(fileOwner, session, sessionPort)) return;
            // A download can start navigation without committing another page.
            // Keep the mounted conversation ready while Android chooses a file.
            markUiReady();
            transfers.download(address, disposition, mime);
        });
        WebViewCompat.addWebMessageListener(web, "DroyLocaleNative", java.util.Collections.singleton("http://127.0.0.1:" + sessionPort),
            (view, message, origin, mainFrame, reply) -> {
                if (!isCurrentWebSession(view, session, sessionPort)
                        || !NativeUiProtocol.trustedOrigin(origin.toString(), sessionPort, mainFrame)) return;
                JSONObject response = new JSONObject();
                try {
                    JSONObject request = new JSONObject(message.getData());
                    response.put("id", request.getInt("id"));
                    NativeLocale.adopt(request.getString("language"));
                    RuntimeService.refreshExecutionProgress();
                    AudioCaptureService.refreshLocale(this);
                    ProactiveNotifications.refreshLocale(this);
                    io.droyagent.app.history.HistoryLocationService.refreshLocale(this);
                    PetOverlay.refresh(this);
                    loadingOverlay.setContentDescription(NativeLocale.text("正在准备对话", "Preparing your conversation"));
                    response.put("ok", true);
                    reply.postMessage(response.toString());
                } catch (org.json.JSONException | IllegalArgumentException | IllegalStateException failure) {
                    try { response.put("ok", false).put("error", failure.getMessage()); reply.postMessage(response.toString()); }
                    catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
                    showActionFailure(NativeLocale.text("语言同步失败", "Language synchronization failed"), failure);
                }
            });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(web, NativeLocale.webBootstrapScript(),
                java.util.Collections.singleton("http://127.0.0.1:" + sessionPort));
        WebViewCompat.addWebMessageListener(web, "DroyNative", java.util.Collections.singleton("http://127.0.0.1:" + sessionPort),
            (view, message, sourceOrigin, mainFrame, reply) -> {
                if (!isCurrentWebSession(view, session, sessionPort)
                    || !NativeUiProtocol.trustedOrigin(sourceOrigin.toString(), sessionPort, mainFrame)) return;
                JSONObject response = new JSONObject();
                try {
                    JSONObject request = NativeUiProtocol.parse(message.getData());
                    response.put("id", request.getString("id"));
                    if (request.getString("action").equals("memoryArchiveSave")) {
                        if (ChatFileTransfers.isPickerOpen()) throw new IllegalStateException(NativeLocale.text("请先完成当前文件选择", "Finish the current file selection first"));
                        String address = NativeUiProtocol.memoryArchiveDownload(request.getJSONObject("args"),sessionPort);
                        markUiReady();
                        transfers.download(address,"attachment; filename=\"droy-memory.droy\"","application/octet-stream");
                        response.put("result",new JSONObject().put("status","select_destination")).put("ok",true);
                        reply.postMessage(response.toString()); return;
                    }
                    if (request.getString("action").equals("voiceAuthenticate")) {
                        voiceAuthentication.authenticate(() -> {
                            try {
                                response.put("result", new JSONObject().put("authorized", true)).put("ok", true);
                            } catch (Exception failure) {
                                try { response.put("ok", false).put("error", failure.getMessage()); }
                                catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                            }
                            if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString());
                        }, error -> {
                            try { response.put("ok", false).put("error", error); }
                            catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                            if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString());
                        });
                        return;
                    }
                    if (request.getString("action").equals("audioDictation")) {
                        JSONObject args = request.optJSONObject("args") == null ? new JSONObject() : request.getJSONObject("args");
                        new Thread(() -> {
                            try {
                                if (args.getString("operation").equals("claim")) {
                                    VoiceWake.get(this).pauseForVoice();
                                    AudioCapture.get(this).claimDictation();
                                } else if (args.getString("operation").equals("release")) {
                                    AudioCapture.get(this).releaseDictation();
                                } else throw new IllegalArgumentException(NativeLocale.text("无效的听写状态", "Invalid dictation state"));
                                response.put("result", JSONObject.NULL).put("ok", true);
                            } catch (Exception failure) {
                                try { response.put("ok", false).put("error", failure.getMessage()); }
                                catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                            }
                            runOnUiThread(() -> { if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString()); });
                        }, "droy-dictation-owner").start();
                        return;
                    }
                    if (request.getString("action").equals("mediaInput")) {
                        JSONObject args = request.optJSONObject("args") == null ? new JSONObject() : request.getJSONObject("args");
                        if (args.optString("operation").equals("pick")) {
                            response.put("result", mediaInput.request(args)).put("ok", true);
                            reply.postMessage(response.toString());
                        } else {
                            io.droyagent.app.media.MediaInputController owner = mediaInput;
                            // Metadata operations can wait behind a provider copy; keep the UI responsive.
                            new Thread(() -> {
                                try { response.put("result", owner.request(args)).put("ok", true); }
                                catch (Exception failure) {
                                    try { response.put("ok", false).put("error", failure.getMessage() == null ? NativeLocale.text("附件操作未完成", "Attachment operation did not finish") : failure.getMessage()); }
                                    catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                                }
                                runOnUiThread(() -> {
                                    if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString());
                                });
                            }, "droy-media-ui").start();
                        }
                        return;
                    }
                    if (request.getString("action").equals("audioCapture") || request.getString("action").equals("audioTranscription")) {
                        String action = request.getString("action");
                        JSONObject args = request.optJSONObject("args");
                        new Thread(() -> {
                            try {
                                JSONObject result = performAudioAction(action, args == null ? new JSONObject() : args);
                                response.put("result", result).put("ok", true);
                                if (action.equals("audioCapture") && result.optString("state").equals("awaiting_user"))
                                    runOnUiThread(() -> openAudioAuthorization(result.optString("captureId")));
                            } catch (Exception failure) {
                                try { response.put("ok", false).put("error", failure.getMessage() == null ? NativeLocale.text("音频操作未完成", "Audio operation did not finish") : failure.getMessage()); }
                                catch (Exception impossible) { throw new AssertionError(impossible); }
                            }
                            runOnUiThread(() -> { if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString()); });
                        }, "droy-audio-ui").start();
                        return;
                    }
                    if (request.getString("action").equals("browserPage")) {
                        browserSurface.request(request.getJSONObject("args"), value -> {
                            try { response.put("result", value).put("ok", true); } catch (Exception impossible) { throw new AssertionError(impossible); }
                            if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString());
                        }, error -> {
                            try { response.put("ok", false).put("error", error.getMessage() == null ? NativeLocale.text("浏览器操作未完成", "Browser operation did not finish") : error.getMessage()); } catch (Exception impossible) { throw new AssertionError(impossible); }
                            if (isCurrentWebSession(view, session, sessionPort)) reply.postMessage(response.toString());
                        });
                        return;
                    }
                    String action = request.getString("action");
                    JSONObject args = request.optJSONObject("args") == null ? new JSONObject() : request.getJSONObject("args");
                    if (action.equals("setPreference") && isDisplayPreference(args.getString("key"))) {
                        uiStateExecutor.execute(() -> {
                            try {
                                saveDisplayPreference(args);
                                runOnUiThread(() -> {
                                    if (isDestroyed()) return;
                                    try {
                                        applyDisplayPreference(args.optString("key"));
                                        enqueueUiState(view, session, sessionPort, response, reply, null);
                                    } catch (Exception failure) { replyUiFailure(view, session, sessionPort, response, reply, failure); }
                                });
                            } catch (Exception failure) {
                                replyUiFailure(view, session, sessionPort, response, reply, failure);
                            }
                        });
                        return;
                    }
                    if (action.equals("notificationManagement") || action.equals("proactiveNotifications") || action.equals("setBrandLogoBackgrounds")
                            || action.equals("setPreference") && java.util.Set.of("backgroundRuntimeEnabled", "notificationManagementEnabled").contains(args.optString("key"))) {
                        uiStateExecutor.execute(() -> {
                            try {
                                performUiAction(action, args);
                                runOnUiThread(() -> enqueueUiState(view, session, sessionPort, response, reply, null));
                            } catch (Exception failure) { replyUiFailure(view, session, sessionPort, response, reply, failure); }
                        });
                        return;
                    }
                    Object result = action.equals("getStateSection") ? UI_STATE_RESULT : performUiAction(action, args);
                    if (result == UI_STATE_RESULT) {
                        enqueueUiState(view, session, sessionPort, response, reply,
                            action.equals("getStateSection") ? args.getString("section") : null);
                        return;
                    }
                    response.put("result", result).put("ok", true);
                } catch (Exception failure) {
                    try { response.put("ok", false).put("error", failure.getMessage() == null ? NativeLocale.text("操作未完成", "Operation did not finish") : failure.getMessage()); }
                    catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
                }
                reply.postMessage(response.toString());
            });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(web, mobileLayoutScript(),
                java.util.Collections.singleton("http://127.0.0.1:" + sessionPort));
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String address, android.graphics.Bitmap favicon) {
                chatMicrophone.cancel();
                chatCamera.cancel();
                if (isCurrentWebSession(view, session, sessionPort)) beginUiLoad(view, session, sessionPort);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                boolean own = isOwnUrl(uri, sessionPort);
                if (own) return false;
                if (request.isForMainFrame() && request.hasGesture() && ChatFilePolicy.isExternalLink(uri.toString())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
                    catch (RuntimeException failure) { showActionFailure(NativeLocale.text("无法打开链接", "Unable to open the link"), failure); }
                }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && !isOwnUrl(uri, sessionPort))
                    // Cookies are shared across loopback ports. Block external
                    // subresources as well as navigation from this privileged UI.
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                if (isOwnUrl(uri, sessionPort) && "/droy/branding/installed-logo".equals(uri.getPath())
                        && "GET".equals(request.getMethod()) && !request.isForMainFrame()
                        && isCurrentWebSession(view, session, sessionPort)) {
                    try {
                        return new WebResourceResponse("image/png", null, 200, "OK",
                            java.util.Map.of("Cache-Control", "no-store"), getAssets().open("app-brand-logo.png"));
                    } catch (java.io.IOException failure) {
                        return new WebResourceResponse("text/plain", "UTF-8", 500, "Brand asset unavailable",
                            java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                    }
                }
                return null;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) failWeb(view, NativeLocale.text("对话页面连接中断，请重新加载。", "The conversation connection was interrupted. Please reload."), false);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) failWeb(view, NativeLocale.text("对话页面暂时无法加载（HTTP ", "The conversation could not be loaded (HTTP ") + response.getStatusCode() + NativeLocale.text("）。请重试；仍失败时可在关于与诊断中重新启动引擎。", "). Try again. If the problem continues, restart the engine in About and diagnostics."), false);
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                failWeb(view, NativeLocale.text("系统已关闭对话页面。已保存的记录仍保留，未发送的草稿可能丢失；重新加载后请核对任务状态。", "Android closed the conversation page. Saved history is preserved, but unsent drafts may be lost. Reload and check the task status."), true);
                return true;
            }
            @Override public void onPageFinished(WebView view, String ignored) {
                if (!isCurrentWebSession(view, session, sessionPort) || webFailure != null) return;
                view.evaluateJavascript("(function(){try{new Function('class C { static {} }');return typeof Promise.withResolvers==='function' && typeof Array.prototype.toSorted==='function';}catch(e){return false;}})()", result -> {
                    if (!isCurrentWebSession(view, session, sessionPort) || webFailure != null || isDestroyed()) return;
                    if ("true".equals(result)) { applyMobileLayout(view); updateDocumentTitle(); }
                    else showWebViewUpgrade();
                });
            }
        });
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true); cookies.setAcceptThirdPartyCookies(web, false);
        WebView target = web;
        loaded = session;
        chatContainer.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        beginUiLoad(target, session, sessionPort);
        // Cookies ignore ports, but DSH creates a persistent name per authority.
        // Retired runtime ports must not accumulate until even the login exceeds
        // the HTTP header limit. Other cookies in this WebView profile are kept.
        expireChatAuthCookies(cookies, url, target, session, sessionPort,
                ChatCookiePolicy.authCookieNames(cookies.getCookie(url)), 0, () ->
            cookies.setCookie(url, "droy_session=" + token + "; HttpOnly; SameSite=Strict; Path=/", success -> {
            if (!isCurrentWebSession(target, session, sessionPort) || isDestroyed() || webFailure != null) return;
            if (Boolean.TRUE.equals(success)) { cookies.flush(); target.loadUrl(url + "?token=" + token); }
            else failWeb(target, NativeLocale.text("无法建立本地会话，请重新加载；仍失败时可在关于与诊断中重新启动引擎。", "Unable to establish a local session. Reload, or restart the engine in About and diagnostics if the problem continues."), false);
        }));
        runtimeMessage = null; runtimeStart = null;
    }

    private void expireChatAuthCookies(CookieManager cookies, String url, WebView target, String session, int port,
            java.util.List<String> names, int index, Runnable onComplete) {
        if (!isCurrentWebSession(target, session, port) || isDestroyed() || webFailure != null) return;
        if (index == names.size()) { onComplete.run(); return; }
        cookies.setCookie(url, names.get(index) + "=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/", success -> {
            if (!isCurrentWebSession(target, session, port) || isDestroyed() || webFailure != null) return;
            if (Boolean.TRUE.equals(success)) expireChatAuthCookies(cookies, url, target, session, port, names, index + 1, onComplete);
            else failWeb(target, NativeLocale.text("无法更新本地登录状态，请重新加载对话。", "Unable to update local authentication. Please reload the conversation."), false);
        });
    }

    private boolean isCurrentWebSession(WebView view, String session, int port) {
        return web == view && RuntimeService.ready && port == RuntimeService.webPort
            && loaded.equals(session) && session.equals("http://127.0.0.1:" + port + "/" + RuntimeService.webToken);
    }

    private void beginUiLoad(WebView view, String session, int port) {
        if (browserSurface != null) browserSurface.hide();
        uiNavigationId++;
        uiReady = false;
        revealingBrowserSession = null;
        revealingCompanion = null;
        cancelUiLoadTimeout();
        if (chatVisible) showLoading();
        uiLoadTimeout = () -> {
            if (isCurrentWebSession(view, session, port) && !uiReady && webFailure == null)
                failWeb(view, NativeLocale.text("对话页面准备超时，请重新加载；仍失败时可在关于与诊断中查看原因。", "Conversation preparation timed out. Reload, or check About and diagnostics if the problem continues."), false);
        };
        handler.postDelayed(uiLoadTimeout, 45000);
    }

    private void cancelUiLoadTimeout() {
        if (uiLoadTimeout != null) handler.removeCallbacks(uiLoadTimeout);
        uiLoadTimeout = null;
    }

    private void markUiReady() {
        WebView target = web;
        String session = loaded;
        int port = RuntimeService.webPort;
        long navigation = uiNavigationId;
        if (target == null || uiReady || webFailure != null) return;
        // Client readiness includes the mounted mobile composer. Wait for its
        // rendered frame too; a completed HTTP document is still a loading UI.
        target.evaluateJavascript(mobileLayoutScript(), ignored -> {
            if (!isCurrentWebSession(target, session, port) || uiNavigationId != navigation || webFailure != null || isDestroyed()) return;
            target.postVisualStateCallback(++visualRequestId, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    if (!isCurrentWebSession(target, session, port) || uiNavigationId != navigation || webFailure != null || isDestroyed()) return;
                    uiReady = true; chatState = "ready";
                    cancelUiLoadTimeout(); hideLoading(true);
                    revealPendingBrowser();
                    revealPendingPetTask();
                    revealNotificationManagement();
                    revealPendingVoice();
                    revealPendingCompanion();
                    notifyMediaInputChanged();
                }
            });
        });
    }

    private void notifyMediaInputChanged() {
        runOnUiThread(() -> {
            if (web != null && uiReady && !isDestroyed())
                web.evaluateJavascript("window.dispatchEvent(new Event('droy-media-input-change'))", null);
        });
    }

    private void showLoading() {
        loadingOverlay.animate().cancel();
        loadingOverlay.setAlpha(1f);
        loadingOverlay.setVisibility(View.VISIBLE);
        if (web != null) web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void hideLoading(boolean animate) {
        loadingOverlay.animate().cancel();
        if (loadingOverlay.getVisibility() != View.VISIBLE) return;
        if (animate && chatVisible && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            loadingOverlay.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                loadingOverlay.setVisibility(View.GONE); loadingOverlay.setAlpha(1f);
                if (web != null) web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }).start();
        } else {
            loadingOverlay.setVisibility(View.GONE); loadingOverlay.setAlpha(1f);
            if (web != null) web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    private static boolean isOwnUrl(Uri uri, int port) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == port && uri.getUserInfo() == null;
    }

    private Object performUiAction(String action, JSONObject args) throws Exception {
        switch (action) {
            case "getState": return UI_STATE_RESULT;
            case "hideKeyboard": {
                NativeUiProtocol.noArguments(args);
                View focused = getCurrentFocus();
                View owner = focused != null ? focused : web;
                if (owner != null) {
                    android.view.inputmethod.InputMethodManager input = getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (input != null) input.hideSoftInputFromWindow(owner.getWindowToken(), 0);
                }
                return JSONObject.NULL;
            }
            case "configurePermissions": permissionSetup.configure(args.getString("mode")); return UI_STATE_RESULT;
            case "cancelPermissionSetup": permissionSetup.cancel(); return UI_STATE_RESULT;
            case "setupUpdate": SetupManager.update(this, args); PetOverlay.refresh(this); updateDocumentTitle(); return UI_STATE_RESULT;
            case "setupDeviceTest": return SetupVerification.start(this);
            case "setupRootTest":
                if (permissionSetup.active()) throw new IllegalStateException(NativeLocale.text("请先完成或停止当前授权", "Finish or stop the current authorization first"));
                return SetupRootVerification.start(this);
            case "setupComponent": {
                switch (args.getString("operation")) {
                    case "download": SetupComponents.begin(this); break;
                    case "install": SetupComponents.install(this); break;
                    case "open": SetupComponents.open(this); break;
                    case "cancel": SetupComponents.cancel(this); break;
                    default: throw new IllegalArgumentException(NativeLocale.text("不支持的组件安装操作", "Unsupported component installation operation"));
                }
                return UI_STATE_RESULT;
            }
            case "setBrandLogoBackgrounds": {
                String palettes = AppBranding.updateLogoBackgrounds(preferences.getString("brandLogoBackgrounds", "{}"),
                    args);
                if (!preferences.edit().putString("brandLogoBackgrounds", palettes).commit())
                    throw new IllegalStateException(NativeLocale.text("底色未保存，请重试", "Background color was not saved. Please try again"));
                return UI_STATE_RESULT;
            }
            case "setPreference": {
                String key = args.getString("key");
                if (key.equals("backgroundRuntimeEnabled")) {
                    BackgroundRuntime.setEnabled(this, NativeUiProtocol.booleanArgument(args, "value"));
                    return UI_STATE_RESULT;
                }
                if (key.equals("notificationManagementEnabled")) {
                    boolean enabled = NativeUiProtocol.booleanArgument(args, "value");
                    NotificationManagement.get(this).request(new JSONObject().put("operation", "configure").put("enabled", enabled), false);
                    return UI_STATE_RESULT;
                }
                if (isCapabilityPreference(key)) {
                    boolean enabled = NativeUiProtocol.booleanArgument(args, "value");
                    if (permissionSetup.active() && enabled) throw new IllegalStateException(NativeLocale.text("请先完成或停止当前授权", "Finish or stop the current authorization first"));
                    if (!enabled && permissionSetup.active()) permissionSetup.cancel();
                    SetupManager.update(this, new JSONObject().put("capabilities", new JSONObject()
                        .put(key, enabled)));
                    RuntimeService.memoryPermissionsChanged(this);
                    NotificationManagement.get(this).rescan();
                    if (enabled) permissionSetup.forPreference(key);
                    return UI_STATE_RESULT;
                }
                saveDisplayPreference(args);
                applyDisplayPreference(key);
                return UI_STATE_RESULT;
            }
            case "notificationManagement": {
                NotificationManagement.get(this).request(args, false);
                return UI_STATE_RESULT;
            }
            case "proactiveNotifications": {
                JSONObject configuration = new JSONObject(args.toString()).put("operation", "configure");
                ProactiveNotifications.get(this).request(configuration, false);
                return UI_STATE_RESULT;
            }
            case "notificationAppSettings": {
                String packageName = args.getString("packageName");
                getPackageManager().getApplicationInfo(packageName, 0);
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName));
                return JSONObject.NULL;
            }
            case "requestPermission":
                if (permissionSetup.active()) throw new IllegalStateException(NativeLocale.text("请先完成或停止当前授权", "Finish or stop the current authorization first"));
                if (!permissionSetup.requestIfNeeded(args.getString("name"))) requestSystemPermission(args.getString("name"));
                return JSONObject.NULL;
            case "audioAuthorize": openAudioAuthorization(args.getString("captureId")); return JSONObject.NULL;
            case "voiceAuthorize": openAudioAuthorization(args.getString("captureId")); return JSONObject.NULL;
            case "voiceCameraState":
                if (NativeUiProtocol.booleanArgument(args, "active"))
                    throw new IllegalStateException(NativeLocale.text("实时视频对话暂未开放", "Live video conversations are not available yet"));
                AudioCaptureService.setCameraActive(this, false);
                return JSONObject.NULL;
            case "audioDictation": {
                if (args.getString("operation").equals("claim")) AudioCapture.get(this).claimDictation();
                else if (args.getString("operation").equals("release")) AudioCapture.get(this).releaseDictation();
                else throw new IllegalArgumentException(NativeLocale.text("无效的听写状态", "Invalid dictation state"));
                return JSONObject.NULL;
            }
            case "audioPlay": playAudio(args.getString("captureId")); return new JSONObject().put("status", "opening");
            case "audioExport": exportAudio(args.getString("captureId")); return new JSONObject().put("status", "select_destination");
            case "audioImport": {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 504);
                return JSONObject.NULL;
            }
            case "previewPet": PetOverlay.get(this).preview(); return JSONObject.NULL;
            case "selectPet": PetOverlay.select(this, args.getString("id")); return UI_STATE_RESULT;
            case "petContext": PetOverlay.conversation(this, args.getString("taskId"), NativeUiProtocol.booleanArgument(args, "chatVisible")); return JSONObject.NULL;
            case "petQuestionActive": return new JSONObject().put("active",
                ToolProgress.hasPending(args.getString("taskId"), args.getString("requestId")));
            case "petRevealResult": {
                withdrawResolvedQuestion();
                String request = args.isNull("requestId") ? null : args.optString("requestId", null);
                if (args.getString("taskId").equals(pendingPetTask) && java.util.Objects.equals(request, pendingPetRequest)) {
                    pendingPetTask = null; revealingPetTask = null; revealingPetRequest = null; pendingPetRequest = null;
                    if (!NativeUiProtocol.booleanArgument(args, "ok") || !visible || !hasWindowFocus())
                        Toast.makeText(this, NativeLocale.text("这个任务暂时无法显示，请通过通知或对话列表查看", "This task cannot be displayed right now. Open it from the notification or conversation list"), Toast.LENGTH_LONG).show();
                }
                return JSONObject.NULL;
            }
            case "revokeFile": new io.droyagent.androiduse.FileGrantStore(this).revokeGrant(args.getString("id")); return UI_STATE_RESULT;
            case "pauseOperations": RuntimeService.pauseOperations(this); return UI_STATE_RESULT;
            case "resumeOperations": RuntimeService.resumeOperations(this); return UI_STATE_RESULT;
            case "openBrowser": {
                if (web == null || !uiReady) throw new IllegalStateException(NativeLocale.text("浏览器页面尚未就绪", "The browser page is not ready yet"));
                String argument = args.has("url") ? JSONObject.quote(args.getString("url")) : "undefined";
                web.evaluateJavascript("Promise.resolve().then(()=>window.DroyMobile.openBrowser(" + argument
                    + ")).catch(error=>window.DroyMobile?.reportBrowserError(error))", null);
                return new JSONObject().put("status", "opened");
            }
            case "openExternalBrowser": return ExternalBrowser.open(this, args.getString("url"));
            case "openSettingRestores": showSettingRestores(); return JSONObject.NULL;
            case "openDiagnostics": showDiagnostics(); return JSONObject.NULL;
            case "uiReady": markUiReady(); return JSONObject.NULL;
            case "companionRevealResult": {
                if (args.getString("companionId").equals(pendingCompanion)) {
                    pendingCompanion = null; revealingCompanion = null;
                    getIntent().removeExtra("companionId");
                    if (!NativeUiProtocol.booleanArgument(args, "ok"))
                        Toast.makeText(this, NativeLocale.text("伙伴聊天未能打开，请从消息列表重试", "The companion chat could not be opened. Try again from Messages"), Toast.LENGTH_LONG).show();
                }
                return JSONObject.NULL;
            }
            case "browserRevealResult": {
                if (args.getString("sessionId").equals(pendingBrowserSession)) {
                    pendingBrowserSession = null; revealingBrowserSession = null;
                    if (!NativeUiProtocol.booleanArgument(args, "ok"))
                        Toast.makeText(this, NativeLocale.text("浏览器页面未能打开，请稍后重试", "The browser page could not be opened. Please try again later"), Toast.LENGTH_LONG).show();
                }
                return JSONObject.NULL;
            }
            case "themeSync": {
                String theme = args.getString("preference"), scheme = args.getString("colorScheme");
                if (!(theme.equals("system") || theme.equals("light") || theme.equals("dark"))
                    || !(scheme.equals("light") || scheme.equals("dark"))) throw new IllegalArgumentException(NativeLocale.text("主题无效", "Invalid theme"));
                String color = args.has("backgroundColor") ? NativeUiProtocol.backgroundColorArgument(args) : null;
                android.content.SharedPreferences.Editor editor = preferences.edit()
                    .putString("themePreference", theme).putString("themeColorScheme", scheme)
                    .remove("darkTheme").remove("resetTheme");
                if (color == null) editor.remove("themeBackgroundColor"); else editor.putString("themeBackgroundColor", color);
                if (!editor.commit()) throw new IllegalStateException(NativeLocale.text("主题缓存未保存", "Theme cache was not saved"));
                activeThemeScheme = scheme;
                applyNativeTheme(); PetOverlay.refresh(this); return JSONObject.NULL;
            }
            default: throw new IllegalArgumentException(NativeLocale.text("不支持的界面操作", "Unsupported interface operation"));
        }
    }

    private synchronized CapabilityCatalog capabilityCatalog() {
        if (capabilityCatalog == null) capabilityCatalog = new CapabilityCatalog(this);
        return capabilityCatalog;
    }

    private static boolean isDisplayPreference(String key) {
        return AppBranding.isPreference(key) || java.util.Set.of("assistantName", "petName", "petEnabled",
            "petExternalDisplay", "petInternalDisplay", "browserEncryptedDns", "returnToDroyOnCompletion",
            "automationScreenDisplay").contains(key);
    }

    private void saveDisplayPreference(JSONObject args) throws Exception {
        String key = args.getString("key");
        android.content.SharedPreferences.Editor editor = preferences.edit();
        if (key.equals("assistantName") || key.equals("petName")) {
            Object raw = args.get("value");
            if (!(raw instanceof String)) throw new IllegalArgumentException(NativeLocale.text("助理名称无效", "Invalid assistant name"));
            String name = ((String) raw).trim();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 40) throw new IllegalArgumentException(NativeLocale.text("请输入 1–40 个字符", "Enter 1–40 characters"));
            editor.putString(key, name);
        } else if (AppBranding.isPreference(key)) {
            editor.putString(key, AppBranding.preference(key, args.get("value")));
        } else if (key.equals("petExternalDisplay") || key.equals("petInternalDisplay")) {
            editor.putString(key, NativeUiProtocol.petDisplayArgument(args));
        } else if (key.equals("browserEncryptedDns") || key.equals("petEnabled") || key.equals("returnToDroyOnCompletion")
                || key.equals("automationScreenDisplay")) {
            editor.putBoolean(key, NativeUiProtocol.booleanArgument(args, "value"));
        } else throw new IllegalArgumentException(NativeLocale.text("此设置不能从界面修改", "This setting cannot be changed from this interface"));
        if (!editor.commit()) throw new IllegalStateException(NativeLocale.text("设置未保存，请重试", "Settings were not saved. Please try again"));
    }

    private void applyDisplayPreference(String key) {
        updateDocumentTitle();
        if (java.util.Set.of("petEnabled", "petName", "petExternalDisplay", "petInternalDisplay").contains(key)) PetOverlay.refresh(this);
        if (key.equals("automationScreenDisplay")) AutomationScreenOverlay.refresh(this);
    }

    private void enqueueUiState(WebView view, String session, int port, JSONObject response,
            androidx.webkit.JavaScriptReplyProxy reply, String section) {
        // PermissionSetup owns an Activity queue; snapshot it on the main thread.
        if (isDestroyed()) return;
        final JSONObject permissionState;
        try { permissionState = permissionSetup.state(); }
        catch (Exception failure) { replyUiFailure(view, session, port, response, reply, failure); return; }
        uiStateExecutor.execute(() -> {
            try {
                JSONObject result = section == null ? uiState(permissionState) : switch (section) {
                    case "notificationManagement" -> new JSONObject().put("notificationManagement", NotificationManagement.get(this).state());
                    case "proactiveNotifications" -> new JSONObject().put("proactiveNotifications", ProactiveNotifications.get(this).status());
                    case "audio" -> new JSONObject().put("audioCapture", AudioCapture.get(this).uiStatus())
                        .put("audioTranscription", AudioTranscription.get(this).uiStatus());
                    default -> throw new IllegalArgumentException(NativeLocale.text("不支持的设置分类", "Unsupported settings section"));
                };
                response.put("result", result).put("ok", true);
                runOnUiThread(() -> { if (isCurrentWebSession(view, session, port)) reply.postMessage(response.toString()); });
            } catch (Exception failure) { replyUiFailure(view, session, port, response, reply, failure); }
        });
    }

    private void replyUiFailure(WebView view, String session, int port, JSONObject response,
            androidx.webkit.JavaScriptReplyProxy reply, Exception failure) {
        try { response.put("ok", false).put("error", failure.getMessage() == null
            ? NativeLocale.text("操作未完成", "Operation did not finish") : failure.getMessage()); }
        catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
        runOnUiThread(() -> { if (isCurrentWebSession(view, session, port)) reply.postMessage(response.toString()); });
    }

    private boolean isCapabilityPreference(String key) {
        JSONArray groups = capabilityCatalog().groups();
        for (int i = 0; i < groups.length(); i++) if (key.equals(groups.optJSONObject(i).optString("preference"))) return true;
        return false;
    }

    private JSONObject uiState(JSONObject permissionState) throws Exception {
        JSONArray groups = new JSONArray();
        JSONArray source = capabilityCatalog().groups();
        for (int i = 0; i < source.length(); i++) {
            JSONObject group = new JSONObject(source.getJSONObject(i).toString());
            group.put("enabled", ProductPermissions.allowed(preferences, group.getString("preference")));
            groups.put(group);
        }
        JSONObject capabilities = RuntimeService.capabilityStatus(this);
        JSONObject system = capabilities.optJSONObject("systemPermissions");
        if (system == null) { system = new JSONObject(); capabilities.put("systemPermissions", system); }
        boolean notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        system.put("brightness", Settings.System.canWrite(this)); system.put("runtimeNotifications", notifications);
        system.put("microphone", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
        system.put("camera", checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        system.put("overlay", Settings.canDrawOverlays(this));
        JSONObject hardwarePermissions = HardwarePermissionState.status(this);
        for (java.util.Iterator<String> keys = hardwarePermissions.keys(); keys.hasNext();) {
            String key = keys.next(); system.put(key, hardwarePermissions.get(key));
        }
        JSONObject result = new JSONObject().put("assistantName", AppBranding.name(this, preferences))
            .put("branding", AppBranding.state(this, preferences))
            .put("petEnabled", PetOverlay.enabled(this)).put("petName", PetOverlay.name(this))
            .put("petExternalDisplay", PetOverlay.displayMode(this, false))
            .put("petInternalDisplay", PetOverlay.displayMode(this, true))
            .put("petId", preferences.getString("petId", "default")).put("petLoadError", PetOverlay.loadError())
            .put("petPermissionGranted", Settings.canDrawOverlays(this))
            .put("automationScreenDisplay", preferences.getBoolean("automationScreenDisplay", true))
            .put("automationScreenDisplayReady", io.droyagent.androiduse.DroyAccessibilityService.isConnected(this) || Settings.canDrawOverlays(this))
            .put("automationScreenDisplayError", AutomationScreenOverlay.error())
            .put("returnToDroyOnCompletion", preferences.getBoolean("returnToDroyOnCompletion", false))
            .put("browserEncryptedDns", preferences.getBoolean("browserEncryptedDns", false))
            .put("groups", groups).put("capabilities", capabilities).put("systemPermissions", system)
            .put("fileGrants", new io.droyagent.androiduse.FileGrantStore(this).listGrants())
            .put("runtimeReady", RuntimeService.ready).put("runtimeStatus", NativeLocale.generatedStatus(RuntimeService.status))
            .put("toolsPaused", ProductPermissions.stopped(this)).put("runtimeNotifications", notifications)
            .put("notificationPermissionGranted", notifications);
        result.put("setup", SetupManager.uiStatus(this)).put("setupComponents", SetupComponents.status(this))
            .put("setupDeviceTest", SetupVerification.status()).put("setupRootTest", SetupRootVerification.status());
        result.put("permissionSetup", permissionState);
        JSONObject backgroundState = BackgroundRuntime.state(this);
        for (String key : new String[]{"backgroundRuntimeEnabled", "backgroundRuntimeRunning"})
            result.put(key, backgroundState.get(key));
        result.put("notificationManagementEnabled", NotificationManagement.enabled(this))
            .put("notificationManagement", NotificationManagement.get(this).state(false));
        result.put("proactiveNotifications", ProactiveNotifications.get(this).status(false));
        result.put("timeZone", ProductBridge.deviceTimeZone());
        result.put("audioCapture", AudioCapture.get(this).uiStatus());
        result.put("audioTranscription", AudioTranscription.get(this).uiStatus());
        if (preferences.contains("resetTheme")) result.put("themeReset", "system");
        else if (preferences.contains("darkTheme")) result.put("legacyTheme", preferences.getBoolean("darkTheme", false) ? "dark" : "light");
        return result;
    }

    private void requestSystemPermission(String name) {
        String[] hardwarePermissions = HardwarePermissionState.runtimePermissions(name, Build.VERSION.SDK_INT);
        if (hardwarePermissions != null) {
            boolean missing = false;
            for (String permission : hardwarePermissions)
                missing |= checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
            if (missing) requestPermissions(hardwarePermissions, 73);
            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        switch (name) {
            case "notifications": {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
                } else {
                    android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
                    android.app.NotificationChannel alerts = manager.getNotificationChannel("managed_alerts");
                    boolean channelDisabled = manager.areNotificationsEnabled() && alerts != null
                            && alerts.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE;
                    Intent settings = new Intent(channelDisabled ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    if (channelDisabled) settings.putExtra(Settings.EXTRA_CHANNEL_ID, "managed_alerts");
                    startActivity(settings);
                }
                break;
            }
            case "accessibility": startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
            case "appDetails": startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); break;
            case "aboutPhone": startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); break;
            case "development": {
                String destination = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
                    ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS : Settings.ACTION_DEVICE_INFO_SETTINGS;
                startActivity(new Intent(destination)); break;
            }
            case "webView": showWebViewUpgrade(); break;
            case "shizuku": io.droyagent.androiduse.ShizukuAccess.get(this).requestPermission(); break;
            case "calendar": requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 21); break;
            case "contacts": requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 22); break;
            case "microphone": requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 72); break;
            case "camera": requestPermissions(new String[]{Manifest.permission.CAMERA}, 74); break;
            case "locationSettings": startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); break;
            case "bluetoothSettings": startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); break;
            case "wifiSettings": startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); break;
            case "nfcSettings": startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); break;
            case "notificationAccess": startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); break;
            case "usage": startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName()))); break;
            case "brightness": startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); break;
            case "petOverlay": startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); break;
            case "file": selectFile(false); break;
            case "directory": selectFile(true); break;
            default: throw new IllegalArgumentException(NativeLocale.text("不支持的系统权限", "Unsupported system permission"));
        }
    }

    private void failWeb(WebView view, String message, boolean rendererGone) {
        if (web != view) return;
        failedSession = loaded; webFailure = message;
        cancelUiLoadTimeout(); hideLoading(false);
        if (rendererGone) discardWebView(); else view.stopLoading();
        if (chatVisible) showWebFailure();
    }

    private void discardWebView() {
        if (chatMicrophone != null) chatMicrophone.cancel();
        if (chatCamera != null) chatCamera.cancel();
        if (chatFiles != null) { chatFiles.close(); chatFiles = null; }
        WebView previous = web; web = null;
        uiReady = false;
        revealingBrowserSession = null;
        revealingPetTask = null; revealingPetRequest = null;
        cancelUiLoadTimeout();
        if (previous == null) return;
        if (previous.getParent() instanceof ViewGroup parent) parent.removeView(previous);
        previous.destroy();
    }

    private void showWebFailure() {
        chatState = "failure";
        hideLoading(false);
        LinearLayout page = page();
        page.addView(text(NativeLocale.text("对话页面需要重新加载", "The conversation page needs to reload"), 24)); page.addView(text(webFailure, 16));
        page.addView(text(NativeLocale.text("重新加载不会重新提交任务。任务可能仍在执行，可在关于与诊断中停止任务和引擎。", "Reloading does not resubmit the task. It may still be running. You can stop the task and engine in About and diagnostics."), 15));
        page.addView(button(NativeLocale.text("重新加载对话", "Reload conversation"), view -> { webFailure = null; failedSession = ""; loaded = ""; showChat(); }));
        page.addView(button(NativeLocale.text("关于与诊断", "About and diagnostics"), view -> showDiagnostics())); replace(scroll(page));
    }

    private void showWebViewUpgrade() {
        webUnavailable = true;
        cancelUiLoadTimeout(); hideLoading(false);
        if (!chatVisible) return;
        LinearLayout page = page(); page.addView(text(NativeLocale.text("请更新系统 WebView", "Update Android System WebView"), 24));
        page.addView(text(NativeLocale.text("当前系统浏览组件不支持对话页面所需功能。请更新 Android System WebView（部分手机使用 Chrome），然后重新打开 Droy。已有设置和记录会保留。", "Your system browser component does not support the conversation page. Update Android System WebView (Chrome on some phones), then reopen Droy. Existing settings and history are preserved."), 16));
        android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
        if (provider != null) {
            page.addView(text(NativeLocale.text("当前版本：", "Current version: ") + provider.versionName, 14));
            page.addView(button(NativeLocale.text("查看系统浏览组件", "View system browser component"), view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + provider.packageName)))));
        }
        page.addView(button(NativeLocale.text("更新后重试", "Try again after updating"), view -> { webUnavailable = false; loaded = ""; showChat(); }));
        page.addView(button(NativeLocale.text("关于与诊断", "About and diagnostics"), view -> showDiagnostics())); replace(scroll(page));
    }

    private void updateDocumentTitle() {
        if (web != null) web.evaluateJavascript("document.title=" + JSONObject.quote(AppBranding.name(this, preferences)), null);
    }

    private void applyNativeTheme() {
        String scheme = preferences.getString("themeColorScheme", "");
        String theme = preferences.getString("themePreference", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean dark = activeThemeScheme != null ? activeThemeScheme.equals("dark")
            : theme.equals("system") ? systemDark : theme.equals("dark");
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        foreground = dark ? Color.rgb(232, 236, 240) : Color.rgb(25, 31, 39);
        // Match the pinned DSH base palette before its first themeSync arrives.
        // A cached system palette is invalid when Android's scheme changed.
        background = dark ? Color.rgb(21, 21, 23) : Color.WHITE;
        String cached = preferences.getString("themeBackgroundColor", "");
        if (cached.matches("#[0-9A-Fa-f]{6}") && scheme.equals(dark ? "dark" : "light")) background = Color.parseColor(cached);
        if (content != null) content.setBackgroundColor(background);
        if (chatContainer != null) chatContainer.setBackgroundColor(background);
        if (loadingOverlay != null) loadingOverlay.setBackgroundColor(background);
        if (nativePage != null) {
            nativePage.setBackgroundColor(background);
            applyNativeForeground(nativePage);
        }
        if (web != null) web.setBackgroundColor(background);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(background));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarDividerColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        // Theme is also applied before setContentView; create the DecorView
        // before asking for its controller on Android's first Activity launch.
        window.getDecorView().setBackgroundColor(background);
        androidx.core.view.WindowInsetsControllerCompat controller = androidx.core.view.WindowCompat
            .getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    private void applyNativeForeground(View view) {
        if (view instanceof TextView label) label.setTextColor(foreground);
        if (view instanceof ViewGroup group)
            for (int index = 0; index < group.getChildCount(); index++) applyNativeForeground(group.getChildAt(index));
    }

    private String mobileLayoutScript() {
        if (mobileCss == null) {
            try (java.io.InputStream input = getAssets().open("mobile.css"); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                mobileCss = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException error) { throw new IllegalStateException(NativeLocale.text("缺少内置手机界面样式", "Built-in mobile interface styles are missing"), error); }
        }
        return String.format(java.util.Locale.ROOT, """
            (() => {
              if (!window.__droyApplyWindowInsets) {
                window.__droyApplyWindowInsets = (pixels) => {
                  if (pixels) window.__droyWindowInsets = pixels;
                  const root = document.documentElement, p = window.__droyWindowInsets;
                  if (!root || !p) return;
                  // Android pixels and CSS pixels can differ from the device's dp scale.
                  const width = root.clientWidth || window.innerWidth;
                  const scale = p.width > 0 && width > 0 ? width / p.width : 1 / (window.devicePixelRatio || 1);
                  window.__droyTouchConfig = { longPressMs: p.longPressMs,
                    touchSlopCssPx: p.touchSlop * scale, dpToCss: p.density * scale,
                    systemGestureLeftCssPx: p.systemGestureLeft * scale,
                    systemGestureRightCssPx: p.systemGestureRight * scale };
                  root.style.setProperty('--droy-touch-target', (48 * p.density * scale) + 'px');
                  root.style.setProperty('--droy-header-height', (56 * p.density * scale) + 'px');
                  root.style.setProperty('--droy-header-font-size', (18 * p.density * scale) + 'px');
                  for (const side of ['top', 'right', 'bottom', 'left'])
                    root.style.setProperty('--droy-safe-' + side, (p[side] * scale) + 'px');
                  root.style.setProperty('--droy-ime-bottom', (p.ime * scale) + 'px');
                  root.setAttribute('data-droy-ime-visible', p.ime > 0 ? 'true' : 'false');
                  root.style.setProperty('--droy-insets-ready', p.ready ? '1' : '0');
                  root.setAttribute('data-droy-insets-ready', p.ready ? 'true' : 'false');
                };
                const update = () => window.__droyApplyWindowInsets();
                window.addEventListener('resize', update);
                document.addEventListener('DOMContentLoaded', () => requestAnimationFrame(update), { once: true });
              }
              window.__droyWindowInsets = %s;
              const install = () => {
                const root = document.documentElement;
                if (!root) return false;
                root.setAttribute('data-droy-mobile', '0.2.1-rc.4');
                let sheet = document.getElementById('droy-mobile-layout');
                if (!sheet) {
                  sheet = document.createElement('style');
                  sheet.id = 'droy-mobile-layout';
                  (document.head || root).appendChild(sheet);
                }
                sheet.textContent = %s;
                window.__droyApplyWindowInsets();
                return true;
              };
              if (!install()) {
                const observer = new MutationObserver(() => { if (install()) observer.disconnect(); });
                observer.observe(document, { childList: true, subtree: true });
                document.addEventListener('DOMContentLoaded', () => { install(); observer.disconnect(); }, { once: true });
              }
            })()
            """, windowInsetsValues(), JSONObject.quote(mobileCss));
    }

    private void updateWindowInsets(WindowInsets insets) {
        windowInsetsKnown = true;
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        safeTop = bars.top; safeRight = bars.right; safeBottom = bars.bottom; safeLeft = bars.left;
        // These edges belong to Android Back; reserving them avoids closing a
        // row on pointercancel and then the drawer on the same system gesture.
        android.graphics.Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
        systemGestureLeft = gestures.left; systemGestureRight = gestures.right;
        imeVisible = insets.isVisible(WindowInsets.Type.ime());
        imeBottom = imeVisible ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
        applyNativePageInsets();
        pushWindowInsets();
    }

    private String windowInsetsValues() {
        int width = web == null ? 0 : web.getWidth();
        if (width <= 0 && content != null) width = content.getWidth();
        if (width <= 0) width = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        return "{top:" + safeTop + ",right:" + safeRight + ",bottom:" + safeBottom
            + ",left:" + safeLeft + ",ime:" + imeBottom + ",width:" + width + ",ready:" + windowInsetsKnown
            + ",density:" + getResources().getDisplayMetrics().density
            + ",systemGestureLeft:" + systemGestureLeft + ",systemGestureRight:" + systemGestureRight
            + ",longPressMs:" + android.view.ViewConfiguration.getLongPressTimeout()
            + ",touchSlop:" + android.view.ViewConfiguration.get(this).getScaledTouchSlop() + "}";
    }

    private void pushWindowInsets() {
        if (web == null) return;
        String values = windowInsetsValues();
        if (values.equals(pushedInsets)) return;
        pushedInsets = values;
        web.evaluateJavascript("window.__droyApplyWindowInsets?.(" + values + ")", null);
    }

    private void applyNativePageInsets() {
        if (nativePage != null) nativePage.setPadding(safeLeft, safeTop, safeRight, Math.max(safeBottom, imeBottom));
    }

    private void applyMobileLayout(WebView view) {
        pushedInsets = "";
        view.evaluateJavascript(mobileLayoutScript(), null);
    }

    // Kept for device fixtures; the ordinary entry is DSH's settings menu.
    private void showCapabilities() {
        showChat();
        if (web != null && RuntimeService.ready) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'droy-permissions'}}))", null);
    }

    private void selectFile(boolean tree) {
        Intent intent = new Intent(tree ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
        if (!tree) { intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/*"); }
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (tree) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, tree ? 502 : 501);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (permissionSetup != null && permissionSetup.onActivityResult(request)) return;
        if (voiceAuthentication != null && voiceAuthentication.onActivityResult(request, result, data)) return;
        if (mediaInput != null && mediaInput.onActivityResult(request, result, data)) return;
        if (request == 504) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri selected = data.getData();
            new Thread(() -> {
                java.io.File file = null;
                try {
                    java.io.File directory = new java.io.File(getFilesDir(), "audio-imports");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException(NativeLocale.text("无法创建音频导入目录", "Unable to create the audio import directory"));
                    file = new java.io.File(directory, java.util.UUID.randomUUID() + ".audio");
                    try (java.io.InputStream input = getContentResolver().openInputStream(selected);
                         java.io.OutputStream output = new java.io.FileOutputStream(file)) {
                        if (input == null) throw new java.io.IOException(NativeLocale.text("无法读取所选音频", "Unable to read the selected audio"));
                        byte[] buffer = new byte[65536]; long total = 0; int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > 256L * 1024 * 1024) throw new java.io.IOException(NativeLocale.text("请选择不超过 256 MiB 的音频文件", "Choose an audio file no larger than 256 MiB"));
                            output.write(buffer, 0, count);
                        }
                    }
                    RuntimeService.audioRequest(new JSONObject().put("operation", "transcribe").put("inputPath", file.getAbsolutePath()), "product-user-action");
                    runOnUiThread(this::refreshWebState);
                } catch (Exception failure) {
                    if (file != null) file.delete();
                    runOnUiThread(() -> showActionFailure(NativeLocale.text("音频转写未开始", "Audio transcription did not start"), failure));
                }
            }, "droy-audio-import").start();
            return;
        }
        if (request == 503) {
            java.io.File source = pendingAudioExport; pendingAudioExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || source == null) return;
            Uri target = data.getData();
            new Thread(() -> {
                try (java.io.InputStream input = new java.io.FileInputStream(source);
                     java.io.OutputStream output = getContentResolver().openOutputStream(target, "wt")) {
                    if (output == null) throw new java.io.IOException(NativeLocale.text("无法写入所选位置", "Unable to write to the selected location"));
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    runOnUiThread(() -> Toast.makeText(this, NativeLocale.text("录音已导出", "Recording exported"), Toast.LENGTH_SHORT).show());
                } catch (Exception failure) { runOnUiThread(() -> showActionFailure(NativeLocale.text("录音导出未完成", "Recording export did not finish"), failure)); }
            }, "droy-audio-export").start();
            return;
        }
        if (chatFiles != null && chatFiles.onActivityResult(request, result, data)) return;
        if ((request != 501 && request != 502) || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (request == 501) flags &= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
            new io.droyagent.androiduse.FileGrantStore(this).saveGrant(uri, "");
            refreshWebState();
        } catch (Exception failure) { showActionFailure(NativeLocale.text("文件授权未保存", "File access permission was not saved"), failure); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        HardwareRuntime.onPermissionsChanged(this);
        if (chatMicrophone != null) chatMicrophone.result(request);
        if (chatCamera != null) chatCamera.result(request);
        NotificationManagement.refreshAccess(this);
        if (permissionSetup != null) permissionSetup.onRuntimeResult(request, permissions);
        refreshWebState();
    }

    private void refreshWebState() {
        if (web != null && RuntimeService.ready && webFailure == null)
            web.evaluateJavascript("window.DroyMobile?.refresh?.()", null);
    }

    private JSONObject performAudioAction(String action, JSONObject args) throws Exception {
        if (action.equals("audioTranscription")) return RuntimeService.audioRequest(args, "product-user-action");
        return switch (args.getString("operation")) {
            case "start" -> RuntimeService.executeProductAction("audio_start", args);
            case "status" -> AudioCapture.get(this).uiStatus();
            case "stop" -> AudioCapture.get(this).stopFromUi(args.getString("captureId"));
            case "delete" -> { AudioCapture.get(this).deleteFromUi(args.getString("captureId")); yield AudioCapture.get(this).uiStatus(); }
            default -> throw new IllegalArgumentException(NativeLocale.text("不支持的音频操作", "Unsupported audio operation"));
        };
    }

    private void openAudioAuthorization(String captureId) {
        if (captureId == null || captureId.isEmpty()) return;
        startActivity(new Intent(this, AudioPermissionActivity.class).putExtra("captureId", captureId));
    }

    private void stopAudioPlayback() {
        android.media.MediaPlayer previous = audioPlayer; audioPlayer = null;
        AlertDialog previousDialog = audioPlaybackDialog; audioPlaybackDialog = null;
        if (previousDialog != null) previousDialog.dismiss();
        if (previous != null) previous.release();
    }

    private void stopChatMicrophone() {
        if (chatMicrophone != null) chatMicrophone.cancel();
        if (web != null) web.evaluateJavascript("window.DroyMobile?.stopMicrophone?.()", null);
    }

    private void playAudio(String captureId) throws Exception {
        java.io.File file = AudioCapture.get(this).fileForUi(captureId);
        stopAudioPlayback();
        android.media.MediaPlayer player = new android.media.MediaPlayer();
        audioPlayer = player;
        try {
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(file.getAbsolutePath());
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(NativeLocale.text("录音回放", "Recording playback"))
                .setMessage(NativeLocale.text("正在准备播放 ", "Preparing to play ") + file.getName()).setPositiveButton(NativeLocale.text("停止播放", "Stop playback"), null).create();
            audioPlaybackDialog = dialog;
            dialog.setOnDismissListener(ignored -> {
                if (audioPlaybackDialog == dialog) audioPlaybackDialog = null;
                if (audioPlayer == player) stopAudioPlayback();
            });
            player.setOnPreparedListener(ready -> { if (audioPlayer == ready) { dialog.setMessage(NativeLocale.text("正在播放 ", "Playing ") + file.getName()); ready.start(); } });
            player.setOnCompletionListener(ignored -> dialog.dismiss());
            player.setOnErrorListener((failed, what, extra) -> { dialog.dismiss(); showActionFailure(NativeLocale.text("录音播放失败", "Recording playback failed"), new IllegalStateException(NativeLocale.text("音频格式无法播放", "This audio format cannot be played"))); return true; });
            dialog.show(); player.prepareAsync();
        } catch (Exception failure) { stopAudioPlayback(); throw failure; }
    }

    private void exportAudio(String captureId) throws Exception {
        java.io.File file = AudioCapture.get(this).fileForUi(captureId);
        if (pendingAudioExport != null) throw new IllegalStateException(NativeLocale.text("请先完成当前文件选择", "Finish the current file selection first"));
        pendingAudioExport = file;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/wav").putExtra(Intent.EXTRA_TITLE, file.getName()), 503);
        } catch (RuntimeException failure) { pendingAudioExport = null; throw failure; }
    }

    private void showSettingRestores() {
        try {
            JSONArray records = new io.droyagent.androiduse.DeviceSettingsTools(this).listRestoreRecords();
            LinearLayout list = page();
            list.addView(text(NativeLocale.text("恢复前会核对当前值。若你后来手动调整过，会提示冲突。工具操作可用、设置读写开关开启时才可恢复。", "Current values are checked before restoration. Later manual changes are reported as conflicts. Tool access and settings read/write must be enabled to restore a change."), 15));
            if (records.length() == 0) list.addView(text(NativeLocale.text("还没有设置修改记录", "No settings changes recorded yet"), 15));
            for (int i = records.length() - 1; i >= 0; i--) {
                JSONObject item = records.getJSONObject(i); String id = item.getString("recordId");
                list.addView(text(item.optString("name") + "：" + item.optInt("before") + " → " + item.optInt("written") + " · " + item.optString("state"), 14));
                if (!item.optString("state").equals("available")) { list.addView(text(NativeLocale.text("此记录尚未确认或已恢复，请先查询当前设置", "This record is unconfirmed or already restored. Query the current setting first"), 13)); continue; }
                list.addView(button(NativeLocale.text("恢复这次调整", "Restore this change"), view -> new Thread(() -> {
                    try {
                        JSONObject outcome = RuntimeService.executeProductAction("settings_restore", new JSONObject().put("recordId", id));
                        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(NativeLocale.text("恢复结果", "Restoration result")).setMessage(outcome.optBoolean("verified") ? NativeLocale.text("已读取核实恢复后的值", "Restored values were read back and verified") : NativeLocale.text("尚未核实恢复，请检查当前设置", "Restoration is unverified. Check the current settings")).setPositiveButton(NativeLocale.text("关闭", "Close"), null).show());
                    } catch (Exception failure) { runOnUiThread(() -> showActionFailure(NativeLocale.text("恢复未完成", "Restoration did not finish"), failure)); }
                }, "droy-restore-setting").start()));
            }
            new AlertDialog.Builder(this).setTitle(NativeLocale.text("设置恢复记录", "Settings restoration history")).setView(scroll(list)).setPositiveButton(NativeLocale.text("关闭", "Close"), null).show();
        } catch (Exception failure) { showActionFailure(NativeLocale.text("记录读取失败", "Unable to read history"), failure); }
    }

    private String diagnosticText() {
        String logs = RuntimeService.diagnosticsSnapshot();
        String version;
        try {
            RuntimeRelease release = RuntimeInstaller.release(this);
            version = "Droy " + release.droyVersion + " · DSH " + release.dshVersion + NativeLocale.text(" · Android 适配 ", " · Android adapter ") + release.mobileVersion;
        } catch (Exception failure) {
            version = NativeLocale.text("Droy · 运行包版本无法读取（", "Droy · Unable to read runtime version (") + failure.getClass().getSimpleName() + "）";
        }
        return NativeLocale.generatedStatus(RuntimeService.status) + "\n\n" + (logs.startsWith("Droy ") ? logs : version + "\n" + logs);
    }

    private void showDiagnostics() {
        selectedPage = 2; chatVisible = false;
        runtimeMessage = null; runtimeStart = null;
        LinearLayout page = page();
        page.addView(diagnosticButton(NativeLocale.text("‹ 返回", "‹ Back"), view -> showChat()));
        TextView heading = text(NativeLocale.text("关于与诊断", "About and diagnostics"), 22);
        heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setPadding(0, dp(16), 0, dp(16)); page.addView(heading);
        page.addView(diagnosticSection(NativeLocale.text("运行状态", "Runtime status")));
        diagnosticDetails = text(diagnosticText(), 13); diagnosticDetails.setTextIsSelectable(true);
        diagnosticDetails.setLineSpacing(dp(4), 1); diagnosticDetails.setPadding(dp(14), dp(12), dp(14), dp(12));
        diagnosticDetails.setBackground(diagnosticSurface(false)); page.addView(diagnosticDetails);
        page.addView(diagnosticButton(NativeLocale.text("复制诊断", "Copy diagnostics"), view -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(NativeLocale.text("Droy 诊断", "Droy diagnostics"), diagnosticText()));
            Toast.makeText(this, NativeLocale.text("诊断已复制", "Diagnostics copied"), Toast.LENGTH_SHORT).show();
        }));
        page.addView(diagnosticSection(NativeLocale.text("引擎", "Engine")));
        runtimeStart = diagnosticButton(NativeLocale.text("启动并进入对话", "Start and open conversation"), view -> { startRuntime(); showChat(); });
        runtimeStart.setVisibility(!RuntimeService.canStart() || historyChanging ? View.GONE : View.VISIBLE); page.addView(runtimeStart);
        page.addView(diagnosticButton(NativeLocale.text("停止任务和引擎", "Stop task and engine"), view -> { startingRequested = false; RuntimeService.requestStop(this); }));
        page.addView(diagnosticSection(NativeLocale.text("数据与个性化", "Data and personalization")));
        page.addView(diagnosticButton(NativeLocale.text("恢复个性化设置", "Reset personalization"), view -> confirmResetPersonalization()));
        page.addView(diagnosticButton(NativeLocale.text("删除全部对话记录", "Delete all conversation history"), view -> confirmClearHistory()));
        replace(scroll(page));
    }

    private TextView diagnosticSection(String title) {
        TextView label = text(title, 14); label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, dp(24), 0, dp(10)); return label;
    }
    private android.graphics.drawable.GradientDrawable diagnosticSurface(boolean outline) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(outline ? background : mixDiagnosticColor(.045f)); shape.setCornerRadius(dp(10));
        if (outline) shape.setStroke(dp(1), mixDiagnosticColor(.15f)); return shape;
    }
    private int mixDiagnosticColor(float foregroundAmount) {
        float base = 1 - foregroundAmount;
        return Color.rgb(Math.round(Color.red(background) * base + Color.red(foreground) * foregroundAmount),
            Math.round(Color.green(background) * base + Color.green(foreground) * foregroundAmount),
            Math.round(Color.blue(background) * base + Color.blue(foreground) * foregroundAmount));
    }
    private Button diagnosticButton(String title, View.OnClickListener listener) {
        Button control = button(title, listener); control.setAllCaps(false); control.setTextSize(14);
        control.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); control.setMinHeight(dp(48));
        control.setMinimumHeight(dp(48)); control.setPadding(dp(14), dp(12), dp(14), dp(12));
        control.setStateListAnimator(null); control.setElevation(0);
        control.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(mixDiagnosticColor(.12f)), diagnosticSurface(true), null));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(8);
        control.setLayoutParams(layout); return control;
    }

    private void confirmResetPersonalization() {
        new AlertDialog.Builder(this).setTitle(NativeLocale.text("恢复个性化设置？", "Reset personalization?"))
            .setMessage(NativeLocale.text("页面名称、品牌文案、标志底色和浏览器加密 DNS 将恢复默认，主题改为跟随系统。伙伴姓名与形象、权限、Android Use 暂停状态、模型配置和历史记录会保留。", "The page name, brand copy, logo background and encrypted browser DNS will reset, and the theme will follow the system. Companion name and appearance, permissions, Android Use pause state, model settings and history are preserved."))
            .setNegativeButton(NativeLocale.text("取消", "Cancel"), null).setPositiveButton(NativeLocale.text("恢复", "Reset"), (dialog, which) -> {
                if (!preferences.edit().remove("assistantName").remove("browserEncryptedDns").remove("darkTheme")
                    .remove("brandSidebarLabel").remove("brandHeroBadge").remove("brandWelcomeTemplate").remove("brandComposerTemplate")
                    .remove("brandLogoBackgrounds")
                    .putString("themePreference", "system").remove("themeColorScheme").remove("themeBackgroundColor")
                    .putString("resetTheme", "system").commit()) {
                    showActionFailure(NativeLocale.text("恢复未完成", "Restoration did not finish"), new IllegalStateException(NativeLocale.text("设置保存失败", "Unable to save settings"))); return;
                }
                activeThemeScheme = null;
                applyNativeTheme(); updateDocumentTitle();
                if (web != null && RuntimeService.ready && webFailure == null)
                    web.evaluateJavascript("window.DroyMobile?.resetPersonalization?.()", null);
                showDiagnostics();
                Toast.makeText(this, RuntimeService.ready
                    ? NativeLocale.text("名称和网络设置已恢复，主题正在同步", "Name and network settings reset. Synchronizing the theme")
                    : NativeLocale.text("名称和网络设置已恢复，主题将在下次启动时同步", "Name and network settings reset. The theme will synchronize on next launch"), Toast.LENGTH_LONG).show();
            }).show();
    }

    private void confirmClearHistory() {
        if (historyChanging) return;
        new AlertDialog.Builder(this).setTitle(NativeLocale.text("删除全部对话记录？", "Delete all conversation history?"))
            .setMessage(NativeLocale.text("将先停止任务和引擎，再删除所有会话、截图附件及缓存计算产物，无法恢复。模型配置、API Key、名称和主题会保留。", "The task and engine will stop, then all conversations, screenshot attachments and cached computation results will be permanently deleted. Model settings, API keys, names and theme are preserved."))
            .setNegativeButton(NativeLocale.text("取消", "Cancel"), null).setPositiveButton(NativeLocale.text("删除记录", "Delete history"), (dialog, which) -> clearHistoryAfterStop()).show();
    }

    private void clearHistoryAfterStop() {
        if (historyChanging) return;
        historyChanging = true; startingRequested = false; RuntimeService.requestStop(this);
        new Thread(() -> {
            boolean acquired = false;
            try {
                long deadline = SystemClock.elapsedRealtime() + 20000;
                do {
                    acquired = RuntimeService.beginHistoryChange();
                    if (acquired) break;
                    Thread.sleep(100);
                } while (SystemClock.elapsedRealtime() < deadline);
                if (!acquired) throw new java.io.IOException(NativeLocale.text("引擎未能完成停止，请重试", "The engine did not finish stopping. Please try again"));
                HistoryData.clear(new java.io.File(getFilesDir(), "dsh-home").toPath()); ComputeTools.clearJobs(this);
                ProductBridge.clearSequenceReceipts(this);
                runOnUiThread(() -> {
                    // DSH theme lives in its server settings; clearing browser data
                    // is only for references to the removed conversation artifacts.
                    WebStorage.getInstance().deleteAllData(); discardWebView(); loaded = ""; webFailure = null; failedSession = "";
                    Toast.makeText(this, NativeLocale.text("对话记录已删除，模型配置已保留", "Conversation history deleted. Model settings preserved"), Toast.LENGTH_LONG).show();
                    if (selectedPage == 2) showDiagnostics(); else showChat();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> showActionFailure(NativeLocale.text("删除未完成", "Deletion did not finish"), failure));
            } finally {
                if (acquired) RuntimeService.endHistoryChange();
                runOnUiThread(() -> { historyChanging = false; if (selectedPage == 2) showDiagnostics(); });
            }
        }, "droy-history-clear").start();
    }

    // API 30–32 dispatch through this method; API 33+ uses the registered platform callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (imeVisible) {
            getWindow().getInsetsController().hide(WindowInsets.Type.ime()); return;
        }
        if (!chatVisible) { showChat(); return; }
        if (web == null || webFailure != null || !RuntimeService.ready) { moveTaskToBack(true); return; }
        WebView target = web;
        target.evaluateJavascript("Boolean(window.DroyMobile?.handleBack?.())", handled -> {
            if (!isDestroyed() && web == target && !"true".equals(handled)) moveTaskToBack(true);
        });
    }

    private void showActionFailure(String title, Exception failure) {
        if (isDestroyed() || isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(failure.getMessage()).setPositiveButton(NativeLocale.text("关闭", "Close"), null).show();
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout page() { LinearLayout view = column(); view.setPadding(dp(20), dp(16), dp(20), dp(24)); return view; }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(foreground); view.setPadding(0, 0, 0, dp(12)); return view; }
    private Button button(String name, View.OnClickListener listener) { Button view = new Button(this); view.setText(name); view.setTextColor(foreground); view.setOnClickListener(listener); return view; }
    private ScrollView scroll(View view) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(view); return scroll; }
    private void replace(View view) {
        if (nativePage != null) content.removeView(nativePage);
        nativePage = view;
        nativePage.setBackgroundColor(background);
        applyNativePageInsets();
        chatContainer.setVisibility(View.INVISIBLE);
        chatContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }
    private void dismissNativePage() {
        if (nativePage != null) { content.removeView(nativePage); nativePage = null; }
        chatContainer.setVisibility(View.VISIBLE);
        chatContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void setTextIfChanged(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    @Override protected void onStart() {
        super.onStart(); if (!visible) { visible = true; visibleActivities.incrementAndGet(); }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        // Android 16 can migrate a hidden IME's callback after onCreate. Reorder
        // once after that migration, without taking priority over a visible IME.
        if (Build.VERSION.SDK_INT == 36 && hasFocus && !backFocusSynchronized && registerBack != null
                && insets != null && !insets.isVisible(WindowInsets.Type.ime())) {
            registerBack.run();
            backFocusSynchronized = true;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (permissionSetup != null) permissionSetup.resume();
        HardwareRuntime.onPermissionsChanged(this);
        io.droyagent.androiduse.AccessibilityRecovery.get(this).onAppOpened(
            (permissionSetup == null || !permissionSetup.active()) && ProductPermissions.allowed(preferences, "allowRoot"));
        NotificationManagement.get(this).rescan();
        applyNativeTheme(); pushWindowInsets(); refreshWebState();
        if (browserSurface != null) browserSurface.resume();
        notifyMediaInputChanged();
        new Thread(() -> VoiceRuntime.resume(this), "droy-wake-resume").start();
    }
    @Override protected void onPause() { if (permissionSetup != null) permissionSetup.pause(); if (browserSurface != null) browserSurface.pause(); super.onPause(); }
    @Override protected void onStop() {
        stopChatMicrophone();
        if (visible) { visible = false; visibleActivities.decrementAndGet(); } super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("selectedPage", selectedPage);
        if (mediaInput != null) mediaInput.onSaveInstanceState(out);
        if (permissionSetup != null) permissionSetup.save(out);
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        stopAudioPlayback();
        if (permissionSetup != null) permissionSetup.close();
        if (voiceAuthentication != null) voiceAuthentication.cancel();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(microphonePolicyChanged);
        if (visible) { visible = false; visibleActivities.decrementAndGet(); }
        if (unregisterBack != null) unregisterBack.run();
        if (browserSurface != null) browserSurface.close();
        if (mediaInput != null) mediaInput.close();
        uiStateExecutor.shutdown();
        handler.removeCallbacksAndMessages(null); discardWebView(); super.onDestroy();
    }
}

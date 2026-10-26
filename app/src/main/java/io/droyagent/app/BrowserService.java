package io.droyagent.app;

import android.app.Service;
import android.content.*;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated webpage contents embedded in the existing DSH browser viewport. */
public final class BrowserService extends Service {
    static final int EXECUTE = 1, CANCEL = 2, ATTACH = 3, DETACH = 4;
    private static BrowserService connected;
    private static boolean profileInitialized;
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> calls = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService uiWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService addresses = Executors.newSingleThreadExecutor();
    private BrowserNetworkProxy proxy;
    private Messenger messenger;
    private String active;

    static BrowserService current() { return connected; }
    static synchronized void initializeProfile() {
        if (!profileInitialized) { WebView.setDataDirectorySuffix("browser"); profileInitialized = true; }
    }
    @Override public void onCreate() {
        super.onCreate(); initializeProfile(); connected = this;
        messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            Bundle data = message.getData(); String id = data.getString("id", "");
            NativeLocale.mirrorProcessLanguage(data.getString("language"));
            if (id.isEmpty() || id.length() > 80) return true;
            if (message.what == CANCEL) { AtomicBoolean call = calls.get(id); if (call != null) call.set(true); return true; }
            if (message.replyTo == null) return true;
            if (message.what == ATTACH || message.what == DETACH) { surface(message); return true; }
            if (message.what != EXECUTE) return true;
            AtomicBoolean cancelled = new AtomicBoolean();
            if (calls.putIfAbsent(id, cancelled) != null) return true;
            Messenger response = message.replyTo;
            String action = data.getString("action", ""), args = data.getString("args", "{}");
            (action.equals("browser_ui") ? uiWorker : worker).execute(() -> {
                Bundle result = new Bundle(); result.putString("id", id);
                try {
                    if (args.length() > 32768) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器请求过大", "Browser request is too large"));
                    JSONObject value = execute(action, new JSONObject(args), () -> {
                        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    });
                    result.putString("result", value.toString());
                } catch (Exception error) { failure(result, error); }
                finally {
                    calls.remove(id);
                    result.putString("status", "{}"); reply(response, result);
                }
            }); return true;
        }));
    }
    JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        if (action.equals("browser_ui")) return ui(args, cancellation);
        if (action.equals("browser_status")) {
            JSONArray list = BrowserSession.onMain(cancellation, () -> {
                JSONArray values = new JSONArray(); for (Tab tab : tabs.values()) values.put(tab.page.frame()); return values;
            });
            JSONObject result = new JSONObject().put("tabs", list).put("sessionOpen", list.length() > 0);
            if (args.has("sessionId")) return selected(args, false, cancellation).page.frame().put("tabs", list);
            return result.put("activeSessionId", active == null ? JSONObject.NULL : active);
        }
        Tab tab = selected(args, action.equals("browser_open"), cancellation);
        if (action.equals("browser_open")) {
            long until = SystemClock.elapsedRealtime() + 5000;
            while (!tab.page.ready && tab.page.pageError == null && SystemClock.elapsedRealtime() < until) { cancellation.check(); Thread.sleep(40); }
        }
        JSONObject result = tab.page.execute(action, args, cancellation);
        if (action.equals("browser_open")) BrowserSession.onMain(cancellation, () -> { active = tab.page.id; return null; });
        return result;
    }
    private Tab selected(JSONObject args, boolean create, Cancellation cancellation) throws Exception {
        return BrowserSession.onMain(cancellation, () -> {
            String id = args.optString("sessionId", "");
            if (id.isEmpty()) {
                if (create) id = "agent-" + UUID.randomUUID();
                else if (active != null && tabs.containsKey(active)) id = active;
                else if (tabs.size() == 1) id = tabs.keySet().iterator().next();
                else throw new ActionException("BROWSER_SESSION_REQUIRED", io.droyagent.app.NativeLocale.text("请根据浏览器标签列表指定 sessionId", "Specify sessionId using the browser tab list"));
            }
            validateId(id);
            Tab tab = tabs.get(id);
            if (tab == null && create) tab = create(id);
            if (tab == null) throw new ActionException("NO_BROWSER_SESSION", io.droyagent.app.NativeLocale.text("指定的浏览器标签已关闭", "The specified browser tab has closed"));
            return tab;
        });
    }
    private JSONObject ui(JSONObject args, Cancellation cancellation) throws Exception {
        String id = args.getString("tabId"), operation = args.getString("operation"); validateId(id);
        if (operation.equals("close")) return BrowserSession.onMain(cancellation, () -> {
            Tab tab = tabs.remove(id); if (tab != null) tab.close();
            if (id.equals(active)) active = null;
            return new JSONObject().put("sessionId", id).put("sessionOpen", false);
        });
        Tab tab = BrowserSession.onMain(cancellation, () -> {
            Tab found = tabs.get(id);
            if (found == null && (operation.equals("attach") || operation.equals("navigate"))) found = create(id);
            if (found == null && operation.equals("state")) return null;
            if (found == null) throw new ActionException("NO_BROWSER_SESSION", io.droyagent.app.NativeLocale.text("浏览器标签已关闭，请重新打开", "Browser tab closed. Please reopen it"));
            return found;
        });
        if (tab == null) return new JSONObject().put("sessionId", id).put("sessionOpen", false).put("url", "").put("title", "")
            .put("loading", false).put("canGoBack", false).put("canGoForward", false).put("error", JSONObject.NULL).put("revision", 0);
        if (operation.equals("navigate")) {
            long until = SystemClock.elapsedRealtime() + 5000;
            while (!tab.page.ready && tab.page.pageError == null && SystemClock.elapsedRealtime() < until) { cancellation.check(); Thread.sleep(40); }
            tab.page.navigate(args.getString("url"), cancellation);
        }
        else if (Set.of("back", "forward", "reload").contains(operation)) tab.page.history(operation, cancellation);
        else if (operation.equals("attach")) active = id;
        else if (operation.equals("detach")) { /* Visibility is owned by the surface host, not a takeover mode. */ }
        else if (!operation.equals("state")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的浏览器页面操作", "Unsupported browser page operation"));
        return tab.page.frame();
    }
    private Tab create(String id) throws Exception {
        if (tabs.size() >= 20) throw new ActionException("BROWSER_TAB_LIMIT", io.droyagent.app.NativeLocale.text("请先关闭不需要的浏览器标签", "Close unneeded browser tabs first"));
        if (proxy == null) proxy = new BrowserNetworkProxy(host -> BrowserDns.resolve(this, host));
        Tab tab = new Tab(id); tabs.put(id, tab); return tab;
    }
    private void surface(Message message) {
        Bundle data = message.getData(), result = new Bundle(); result.putString("id", data.getString("id"));
        SurfaceControlViewHost.SurfacePackage localSurface = null;
        try {
            String id = data.getString("tabId", ""); validateId(id);
            Tab tab = tabs.get(id);
            if (tab == null && message.what == ATTACH) tab = create(id);
            if (tab == null) throw new ActionException("NO_BROWSER_SESSION", io.droyagent.app.NativeLocale.text("浏览器标签已关闭", "Browser tab closed"));
            if (message.what == DETACH) tab.detach();
            else {
                int width = data.getInt("width"), height = data.getInt("height");
                if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("网页显示尺寸无效", "Invalid web display dimensions"));
                tab.attach(data.getBinder("hostToken"), data.getInt("displayId"), width, height);
                localSurface = tab.host.getSurfacePackage(); result.putParcelable("surface", localSurface); active = id;
            }
            result.putString("result", tab.page.frame().toString());
        } catch (Exception error) { failure(result, error); }
        try { reply(message.replyTo, result); } finally { if (localSurface != null) localSurface.release(); }
    }
    static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,180}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器标签标识无效", "Invalid browser tab ID"));
    }
    private static void failure(Bundle result, Exception error) {
        result.putString("errorCode", error instanceof ActionException ? ((ActionException) error).getCode() : error instanceof InterruptedException ? "CANCELLED" : "BROWSER_REJECTED");
        result.putString("errorMessage", error instanceof InterruptedException ? io.droyagent.app.NativeLocale.text("浏览器操作已停止；已发生的动作不会撤销", "Browser operation stopped. Actions already performed will not be undone") : error.getMessage() == null ? io.droyagent.app.NativeLocale.text("浏览器操作未完成", "Browser operation did not complete") : error.getMessage());
    }
    private static void reply(Messenger target, Bundle result) {
        Message response = Message.obtain(); response.setData(result);
        try { target.send(response); } catch (RemoteException ignored) { /* The page owner has left. */ }
    }
    private final class Tab {
        final WebView view;
        final BrowserPage page;
        final BrowserEgress egress;
        SurfaceControlViewHost host;
        FrameLayout root;
        IBinder hostToken;
        Tab(String id) throws Exception {
            view = new WebView(BrowserService.this) {
                @Override public boolean onTouchEvent(MotionEvent event) {
                    page.userGesture(event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                    return super.onTouchEvent(event);
                }
            };
            page = new BrowserPage(BrowserService.this, id, view, false);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setMediaPlaybackRequiresUserGesture(true); settings.setGeolocationEnabled(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
            settings.setSafeBrowsingEnabled(true); settings.setSaveFormData(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
            view.setBackgroundColor(Color.WHITE);
            view.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
                @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
                @Override public boolean onShowFileChooser(WebView browser, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) { callback.onReceiveValue(null); return true; }
            });
            view.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView browser, WebResourceRequest request) {
                    try {
                        String url = request.getUrl().toString(); BrowserSession.UrlPolicy.parse(url);
                        if (!request.isForMainFrame() || !request.getMethod().equals("GET")) return false;
                        long navigation = page.beginNavigation();
                        addresses.execute(() -> {
                            try {
                                BrowserSession.UrlPolicy.validatePublic(BrowserService.this, url);
                                new Handler(Looper.getMainLooper()).post(() -> { if (page.acceptsNavigation(navigation)) view.loadUrl(url); });
                            } catch (Exception denied) { if (page.acceptsNavigation(navigation)) page.failed(io.droyagent.app.NativeLocale.text("网页地址无法验证或解析", "Web address could not be verified or resolved")); }
                        }); return true;
                    } catch (Exception rejected) { page.failed(io.droyagent.app.NativeLocale.text("网页试图打开非网页或受保护地址", "The page tried to open a non-web or protected address")); return true; }
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView browser, WebResourceRequest request) { return filter(request); }
                @Override public void onPageStarted(WebView browser, String url, android.graphics.Bitmap icon) { page.started(); }
                @Override public void onPageFinished(WebView browser, String url) { if (url.equals(view.getUrl())) page.finished(); }
                @Override public void onReceivedError(WebView browser, WebResourceRequest request, WebResourceError error) { if (request.isForMainFrame() && request.getUrl().toString().equals(view.getUrl())) page.failed(io.droyagent.app.NativeLocale.text("网页加载失败：", "Web page load failed: ") + error.getErrorCode()); }
                @Override public void onReceivedSslError(WebView browser, SslErrorHandler handler, android.net.http.SslError error) { handler.cancel(); page.failed(io.droyagent.app.NativeLocale.text("网页证书校验失败", "Web certificate verification failed")); }
                @Override public void onReceivedHttpAuthRequest(WebView browser, HttpAuthHandler handler, String host, String realm) { handler.cancel(); }
                @Override public void onReceivedClientCertRequest(WebView browser, ClientCertRequest request) { request.cancel(); }
                @Override public boolean onRenderProcessGone(WebView browser, RenderProcessGoneDetail detail) { page.failed(io.droyagent.app.NativeLocale.text("网页渲染进程已退出，请关闭标签后重试", "The web renderer exited. Close the tab and retry")); close(); return true; }
            });
            ServiceWorkerController workers = ServiceWorkerController.getInstance();
            workers.getServiceWorkerWebSettings().setBlockNetworkLoads(true);
            workers.getServiceWorkerWebSettings().setAllowContentAccess(false); workers.getServiceWorkerWebSettings().setAllowFileAccess(false);
            workers.setServiceWorkerClient(new ServiceWorkerClient() { @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) { return filter(request); } });
            view.setDownloadListener((url, agent, disposition, mime, length) -> page.failed(io.droyagent.app.NativeLocale.text("下载未自动执行；请使用文件导出保存选定内容", "Download was not started automatically. Use file export to save selected content")));
            egress = new BrowserEgress(view, proxy, false);
            egress.prepare(() -> page.ready = true, error -> page.failed(error));
        }
        private WebResourceResponse filter(WebResourceRequest request) {
            try { BrowserSession.UrlPolicy.parse(request.getUrl().toString()); return null; }
            catch (Exception denied) { return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0])); }
        }
        void attach(IBinder token, int displayId, int width, int height) {
            if (host != null && Objects.equals(token, hostToken)) { host.relayout(width, height); return; }
            detach(); hostToken = token;
            Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
            if (display == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("网页显示设备不可用", "Web display device is unavailable"));
            host = new SurfaceControlViewHost(BrowserService.this, display, token);
            root = new FrameLayout(BrowserService.this); root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            host.setView(root, width, height);
        }
        void detach() {
            page.userGesture(false);
            if (root != null) root.removeView(view);
            if (host != null) host.release();
            root = null; host = null; hostToken = null;
        }
        void close() { if (page.closed) return; detach(); page.closed = true; page.invalidate(); egress.close(); view.destroy(); }
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() {
        for (AtomicBoolean call : calls.values()) call.set(true);
        worker.shutdownNow(); uiWorker.shutdownNow(); addresses.shutdownNow();
        for (Tab tab : tabs.values()) tab.close(); tabs.clear();
        if (proxy != null) proxy.close(); connected = null; super.onDestroy();
    }
}

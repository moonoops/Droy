package io.droyagent.app;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.function.*;

/** Places the isolated page surface inside DSH's measured content viewport. */
final class BrowserSurfaceHost implements AutoCloseable {
    private final Activity activity;
    private final FrameLayout parent;
    private final Supplier<WebView> chat;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SurfaceView surface;
    private String tabId;
    private long epoch;
    private boolean resumed = true;
    private volatile boolean closed;
    private int width, height;
    private final IBinder visibilityToken = new Binder();
    BrowserSurfaceHost(Activity activity, FrameLayout parent, Supplier<WebView> chat) { this.activity = activity; this.parent = parent; this.chat = chat; }
    void request(JSONObject args, Consumer<JSONObject> success, Consumer<Exception> failure) {
        try {
            String operation = args.getString("operation"), id = args.getString("tabId"); BrowserService.validateId(id);
            if (operation.equals("attach")) place(id, args.getJSONObject("viewport"));
            else if ((operation.equals("detach") || operation.equals("close")) && id.equals(tabId)) hide();
            worker.execute(() -> {
                try {
                    JSONObject result = new BrowserSession(activity).executeRemote("browser_ui", args, () -> { if (closed) throw new InterruptedException(); });
                    activity.runOnUiThread(() -> {
                        if (closed) return;
                        if (operation.equals("attach")) attachSurface();
                        success.accept(result);
                    });
                } catch (Exception error) { activity.runOnUiThread(() -> { if (!closed) failure.accept(error); }); }
            });
        } catch (Exception error) {
            // A rejected replacement must not leave the previous tab's page
            // displayed beneath the new tab's controls.
            if ("attach".equals(args.optString("operation"))) hide();
            failure.accept(error);
        }
    }
    private void place(String id, JSONObject viewport) throws Exception {
        WebView web = chat.get(); if (web == null) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("内置浏览器页面尚未就绪", "Built-in browser page is not ready"));
        double left = viewport.getDouble("left"), top = viewport.getDouble("top"), w = viewport.getDouble("width"), h = viewport.getDouble("height");
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器显示区域无效", "Invalid browser display area"));
        double viewportWidth = viewport.getDouble("viewportWidth");
        if (!Double.isFinite(viewportWidth) || viewportWidth <= 0 || web.getWidth() <= 0)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器页面尺寸尚未就绪", "Browser page dimensions are not ready"));
        // Use the renderer's viewport measurement; WebView's cached scale can
        // lag rotation or layout changes on the separate rendering thread.
        double scale = web.getWidth() / viewportWidth;
        double pixelLeft = left * scale + web.getLeft(), pixelTop = top * scale + web.getTop();
        double pixelRight = (left + w) * scale + web.getLeft(), pixelBottom = (top + h) * scale + web.getTop();
        // Validate before integer conversion. The two-pixel allowance only
        // covers CSS/native edge rounding, including fractional negative edges.
        if (!Double.isFinite(pixelRight) || !Double.isFinite(pixelBottom)
                || pixelLeft < Math.max(0, web.getLeft()) - 2 || pixelTop < Math.max(0, web.getTop()) - 2
                || pixelRight > Math.min(parent.getWidth(), web.getRight()) + 2
                || pixelBottom > Math.min(parent.getHeight(), web.getBottom()) + 2)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器显示区域超出当前页面", "Browser display area exceeds the current page"));
        int x = (int) Math.round(pixelLeft), y = (int) Math.round(pixelTop);
        width = (int) Math.round(pixelRight) - x; height = (int) Math.round(pixelBottom) - y;
        if (width < 1 || height < 1) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器显示区域无效", "Invalid browser display area"));
        if (!id.equals(tabId)) { hide(); tabId = id; }
        if (surface == null) {
            SurfaceView created = new SurfaceView(activity); surface = created;
            created.setContentDescription(io.droyagent.app.NativeLocale.text("网页内容", "Web content")); created.setFocusable(true); created.setFocusableInTouchMode(true);
            created.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) { attachSurface(); }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { attachSurface(); }
                @Override public void surfaceDestroyed(SurfaceHolder holder) { epoch++; }
            });
            parent.addView(created, Math.min(1, parent.getChildCount()));
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height); params.leftMargin = x; params.topMargin = y;
        surface.setLayoutParams(params); surface.setVisibility(resumed ? View.VISIBLE : View.INVISIBLE);
        BrowserUiProtection.publish(activity, visibilityToken, resumed);
    }
    private void attachSurface() {
        if (closed || !resumed || surface == null || tabId == null || !surface.getHolder().getSurface().isValid()) return;
        SurfaceView target = surface; String id = tabId; long generation = ++epoch;
        Bundle data = new Bundle(); data.putString("tabId", id); data.putBinder("hostToken", target.getHostToken());
        data.putInt("width", width); data.putInt("height", height); data.putInt("displayId", target.getDisplay().getDisplayId());
        worker.execute(() -> {
            try {
                Bundle response = BrowserSession.request(activity, BrowserService.ATTACH, data, () -> { if (closed) throw new InterruptedException(); });
                SurfaceControlViewHost.SurfacePackage content = response.getParcelable("surface");
                activity.runOnUiThread(() -> {
                    if (content == null) return;
                    if (closed || !resumed || generation != epoch || surface != target || !id.equals(tabId)) { content.release(); return; }
                    target.setChildSurfacePackage(content);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (closed || generation != epoch) return;
                    WebView web = chat.get();
                    if (web != null) web.evaluateJavascript("window.DroyMobile?.reportBrowserError(" + JSONObject.quote(error.getMessage() == null ? io.droyagent.app.NativeLocale.text("网页显示失败，请重新打开标签", "Web page display failed. Please reopen the tab") : error.getMessage()) + ")", null);
                });
            }
        });
    }
    void resume() { resumed = true; if (surface != null) { BrowserUiProtection.publish(activity, visibilityToken, true); surface.setVisibility(View.VISIBLE); attachSurface(); } }
    void pause() { resumed = false; BrowserUiProtection.publish(activity, visibilityToken, false); if (surface != null) surface.setVisibility(View.INVISIBLE); epoch++; }
    void hide() {
        BrowserUiProtection.publish(activity, visibilityToken, false);
        epoch++; SurfaceView old = surface; String id = tabId; surface = null; tabId = null;
        if (old != null) parent.removeView(old);
        if (id != null && !worker.isShutdown()) worker.execute(() -> {
            try { Bundle args = new Bundle(); args.putString("tabId", id); BrowserSession.request(activity, BrowserService.DETACH, args, () -> {}); }
            catch (Exception ignored) { /* A vanished surface has no remaining input path. */ }
        });
    }
    @Override public void close() { hide(); closed = true; worker.shutdown(); }
}

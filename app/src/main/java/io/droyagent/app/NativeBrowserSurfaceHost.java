package io.droyagent.app;

import android.app.Activity;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import io.droyagent.androiduse.ActionException;
import org.json.JSONObject;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/** Native controls embed the same isolated BrowserService tabs used by tools. */
public final class NativeBrowserSurfaceHost extends FrameLayout implements AutoCloseable {
    public interface Listener {
        void onState(JSONObject state);
        void onError(String code);
    }
    private final Activity activity;
    // BrowserService has one SurfaceControlViewHost per tab. Serializing only
    // surface traffic prevents a departing Activity from detaching its successor.
    private static final ExecutorService SURFACES = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, NativeBrowserSurfaceHost> SURFACE_OWNERS = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final IBinder visibilityToken = new Binder();
    private final Set<String> pendingReads = ConcurrentHashMap.newKeySet();
    private SurfaceView surface;
    private SurfaceControlViewHost.SurfacePackage content;
    private Listener listener;
    private String tabId;
    private long generation;
    private boolean resumed = true;
    private volatile boolean closed;

    public NativeBrowserSurfaceHost(Activity activity) {
        super(activity); this.activity = activity;
        setClipChildren(true); setClipToPadding(true);
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public void clearTab() { if (!closed) detachSurface(); }
    public void selectTab(String id) {
        if (closed) return;
        try { BrowserService.validateId(id); }
        catch (Exception error) { report(error); return; }
        if (id.equals(tabId)) { attachSurface(); return; }
        detachSurface(); tabId = id; SURFACE_OWNERS.put(id, this);
        SurfaceView created = new SurfaceView(activity); surface = created;
        created.setContentDescription(getResources().getString(R.string.native_tools_web_content));
        created.setFocusable(true); created.setFocusableInTouchMode(true);
        created.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { if (surface == created) attachSurface(); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { if (surface == created) attachSurface(); }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { if (surface == created) { generation++; publish(false); releaseContent(); } }
        });
        addView(created, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        created.setVisibility(resumed ? View.VISIBLE : View.INVISIBLE);
    }
    /** BrowserService validates navigations and preserves its URL/DNS policy. */
    public void request(String operation, String id, String url) {
        if (closed) return;
        try {
            if (!Set.of("list", "state", "navigate", "back", "forward", "reload", "close").contains(operation))
                throw new IllegalArgumentException("INVALID_BROWSER_OPERATION");
            if (!operation.equals("list")) BrowserService.validateId(id);
            if (operation.equals("navigate")) BrowserSession.UrlPolicy.parse(url);
            if (operation.equals("close") && id.equals(tabId)) detachSurface();
            JSONObject args = operation.equals("list") ? new JSONObject() : new JSONObject().put("operation", operation).put("tabId", id);
            if (operation.equals("navigate")) args.put("url", url);
            String readKey = Set.of("list", "state").contains(operation) ? operation + ":" + id : null;
            if (readKey != null && !pendingReads.add(readKey)) return;
            worker.execute(() -> {
                try {
                    JSONObject state = new BrowserSession(activity).executeRemote(operation.equals("list") ? "browser_status" : "browser_ui", args,
                        () -> { if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException(); });
                    activity.runOnUiThread(() -> { if (!closed && listener != null) listener.onState(state); });
                } catch (Exception error) { activity.runOnUiThread(() -> report(error)); }
                finally { if (readKey != null) pendingReads.remove(readKey); }
            });
        } catch (Exception error) { report(error); }
    }
    public void resume() {
        if (closed) return; resumed = true;
        if (surface != null) { surface.setVisibility(View.VISIBLE); attachSurface(); }
    }
    public void pause() {
        resumed = false; generation++; publish(false);
        if (surface != null) surface.setVisibility(View.INVISIBLE);
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth || height != oldHeight) attachSurface();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (resumed) attachSurface(); }
    @Override protected void onDetachedFromWindow() { pause(); detachSurface(); super.onDetachedFromWindow(); }
    private void attachSurface() {
        if (closed || !resumed || !isAttachedToWindow() || getWindowVisibility() != View.VISIBLE || surface == null || tabId == null
            || !surface.getHolder().getSurface().isValid() || surface.getDisplay() == null || getWidth() < 1 || getHeight() < 1) return;
        if (getWidth() > 16384 || getHeight() > 16384) { report(new IllegalArgumentException("INVALID_BROWSER_SIZE")); return; }
        SurfaceView target = surface; String id = tabId; long epoch = ++generation;
        Bundle args = new Bundle(); args.putString("tabId", id); args.putBinder("hostToken", target.getHostToken());
        args.putInt("displayId", target.getDisplay().getDisplayId()); args.putInt("width", getWidth()); args.putInt("height", getHeight());
        SURFACES.execute(() -> {
            try {
                if (closed || SURFACE_OWNERS.get(id) != this) return;
                Bundle response = BrowserSession.request(activity, BrowserService.ATTACH, args,
                    () -> { if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException(); });
                SurfaceControlViewHost.SurfacePackage next = response.getParcelable("surface");
                JSONObject state = new JSONObject(response.getString("result", "{}"));
                activity.runOnUiThread(() -> {
                    if (next == null) { report(new IllegalStateException("BROWSER_SURFACE_UNAVAILABLE")); return; }
                    if (closed || !resumed || epoch != generation || surface != target || !id.equals(tabId) || !isAttachedToWindow() || SURFACE_OWNERS.get(id) != this) { next.release(); return; }
                    releaseContent(); content = next; target.setChildSurfacePackage(next); publish(true);
                    if (listener != null) listener.onState(state);
                });
            } catch (Exception error) { activity.runOnUiThread(() -> { if (epoch == generation) report(error); }); }
        });
    }
    private void releaseContent() { if (content != null) { content.release(); content = null; } }
    private void publish(boolean visible) {
        try { BrowserUiProtection.publish(activity, visibilityToken, visible && resumed && !closed && isAttachedToWindow()); }
        catch (Exception error) { if (visible) { if (surface != null) surface.setVisibility(View.INVISIBLE); report(error); } }
    }
    private void report(Exception error) {
        if (closed || listener == null || error instanceof InterruptedException) return;
        String code = error instanceof ActionException ? ((ActionException) error).getCode() : "NATIVE_BROWSER_FAILED";
        listener.onError(code);
    }
    private void detachSurface() {
        generation++; publish(false); releaseContent();
        SurfaceView old = surface; String id = tabId; surface = null; tabId = null;
        if (old != null) removeView(old);
        boolean owned = id != null && SURFACE_OWNERS.remove(id, this);
        if (owned) SURFACES.execute(() -> {
            if (SURFACE_OWNERS.containsKey(id)) return;
            try { Bundle args = new Bundle(); args.putString("tabId", id); BrowserSession.request(activity, BrowserService.DETACH, args, () -> {}); }
            catch (Exception ignored) { /* The removed SurfaceView no longer accepts input. */ }
        });
    }
    @Override public void close() { if (closed) return; detachSurface(); closed = true; listener = null; worker.shutdown(); }
}

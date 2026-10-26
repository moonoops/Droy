package io.droyagent.app;

import android.content.*;
import android.os.*;
import android.app.Application;
import android.webkit.WebView;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import org.json.JSONObject;
import java.net.*;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;

/** Routes tools to the same browser tabs presented by the DSH sidebar. */
final class BrowserSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile JSONObject remoteStatus;
    private static BrowserPage fixture;
    private final Context context;
    BrowserSession(Context context) { this.context = context.getApplicationContext(); }
    static void attach(Object owner, WebView view, boolean ignored) { fixture = new BrowserPage(view.getContext(), "fixture", view, true); }
    static void detach(WebView view) { if (fixture != null && fixture.view == view) { fixture.closed = true; fixture.invalidate(); fixture = null; } }
    static void userInteraction() { if (fixture != null) fixture.invalidate(); }
    static JSONObject status() {
        try { return remoteStatus == null ? new JSONObject().put("sessionState", "unknown") : new JSONObject(remoteStatus.toString()).put("statusIsCached", true); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (action.equals("browser_open") && args.optString("target", "internal").equals("external"))
            return ExternalBrowser.open(context, args.getString("url"), cancellation);
        if (Application.getProcessName().endsWith(":browser")) return executeLocal(action, args, cancellation);
        JSONObject result = executeRemote(action, args, cancellation);
        if (action.equals("browser_open")) MainActivity.revealBrowser(context, result.getString("sessionId"));
        return result;
    }
    JSONObject executeLocal(String action, JSONObject args, Cancellation cancellation) throws Exception {
        if (fixture != null) return fixture.execute(action, args, cancellation);
        BrowserService service = BrowserService.current();
        if (service == null) throw new ActionException("NO_BROWSER_SESSION", io.droyagent.app.NativeLocale.text("浏览器服务尚未启动", "Browser service has not started"));
        return service.execute(action, args, cancellation);
    }
    private static CompletableFuture<Messenger> serviceFuture;
    private static ServiceConnection serviceConnection;
    private static Context serviceContext;
    private static final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Bundle>> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private static synchronized CompletableFuture<Messenger> connection(Context context) {
        if (serviceFuture != null) return serviceFuture;
        serviceContext = context.getApplicationContext();
        CompletableFuture<Messenger> future = new CompletableFuture<>(); serviceFuture = future;
        serviceConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { future.complete(new Messenger(binder)); }
            @Override public void onServiceDisconnected(ComponentName name) {
                ActionException lost = new ActionException("BROWSER_SESSION_LOST", io.droyagent.app.NativeLocale.text("网页进程已断开，请核实后重新打开页面", "The web process disconnected. Please check the result and reopen the page"));
                for (CompletableFuture<Bundle> request : pending.values()) request.completeExceptionally(lost);
                release();
            }
            @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
            @Override public void onNullBinding(ComponentName name) { future.completeExceptionally(new ActionException("BROWSER_UNAVAILABLE", io.droyagent.app.NativeLocale.text("网页服务不可用", "Web service is unavailable"))); release(); }
        };
        if (!serviceContext.bindService(new Intent(serviceContext, BrowserService.class), serviceConnection, Context.BIND_AUTO_CREATE)) {
            future.completeExceptionally(new ActionException("BROWSER_UNAVAILABLE", io.droyagent.app.NativeLocale.text("网页服务未能启动", "Web service could not start"))); release();
        }
        return future;
    }
    static synchronized void release() {
        if (serviceConnection != null && serviceContext != null) {
            try { serviceContext.unbindService(serviceConnection); } catch (IllegalArgumentException ignored) { /* Binding was rejected. */ }
        }
        serviceConnection = null; serviceContext = null; serviceFuture = null; remoteStatus = null;
    }
    static Bundle request(Context context, int operation, Bundle args, Cancellation cancellation) throws Exception {
        CompletableFuture<Bundle> future = new CompletableFuture<>();
        String id = UUID.randomUUID().toString(); args.putString("language", NativeLocale.active()); args.putString("id", id); pending.put(id, future);
        Messenger service = null; boolean received = false;
        try {
            service = await(connection(context).thenApply(value -> value), cancellation, 5000);
            Message request = Message.obtain(null, operation); request.setData(args);
            request.replyTo = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                if (id.equals(message.getData().getString("id"))) future.complete(message.getData()); return true;
            }));
            cancellation.check(); service.send(request);
            Bundle result = await(future, cancellation, Math.max(15000, args.getLong("timeoutMs", 15000) + 10000));
            received = true;
            if (result.containsKey("errorCode")) throw new ActionException(result.getString("errorCode"), result.getString("errorMessage"));
            return result;
        } finally {
            pending.remove(id);
            if (!received && service != null) {
                Message cancel = Message.obtain(null, BrowserService.CANCEL); Bundle data = new Bundle(); data.putString("id", id); cancel.setData(data);
                try { service.send(cancel); } catch (RemoteException ignored) { /* No live executor remains. */ }
            }
            if (!future.isDone()) future.cancel(false);
        }
    }
    JSONObject executeRemote(String action, JSONObject args, Cancellation cancellation) throws Exception {
        Bundle data = new Bundle(); data.putString("action", action); data.putString("args", args.toString()); data.putLong("timeoutMs", args.optLong("timeoutMs", 15000));
        Bundle response = request(context, BrowserService.EXECUTE, data, cancellation);
        JSONObject result = new JSONObject(response.getString("result", "{}"));
        remoteStatus = result; return result;
    }
    static <T> T onMain(Cancellation cancellation, Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) { cancellation.check(); return action.call(); }
        CompletableFuture<T> future = new CompletableFuture<>();
        MAIN.post(() -> { try { if (!future.isDone()) { cancellation.check(); future.complete(action.call()); } } catch (Exception error) { future.completeExceptionally(error); } });
        return await(future, cancellation, 5000);
    }
    static <T> T await(CompletableFuture<T> future, Cancellation cancellation, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        try {
            while (true) {
                cancellation.check();
                if (SystemClock.elapsedRealtime() >= until) throw new ActionException("BROWSER_TIMEOUT", io.droyagent.app.NativeLocale.text("浏览器没有及时返回；操作结果未知，请先观察，不要重复提交", "The browser did not respond in time. The result is unknown; observe before submitting again"));
                try { return future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException retry) { /* Recheck stop and current authorization. */ }
                catch (ExecutionException error) { if (error.getCause() instanceof Exception) throw (Exception) error.getCause(); throw error; }
            }
        } finally { if (!future.isDone()) future.cancel(false); }
    }
    /** Also used for subresources; local runtime pages must never enter this WebView. */
    static final class UrlPolicy {
        static URI parse(String value) throws Exception {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("网页地址无效或过长", "Invalid or oversized web address"));
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器仅允许不含登录凭据的 HTTP/HTTPS 网页地址", "The browser accepts only HTTP/HTTPS URLs without login credentials"));
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            if (host.contains("%") || uri.getPort() == 0 || uri.getPort() > 65535 || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa") || host.endsWith("."))
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器不允许访问本机或内网地址", "The browser cannot access local or private network addresses"));
            // Literal addresses can be rejected synchronously, including redirect callbacks.
            if (host.contains(":") || host.matches("[0-9.]+") || host.matches("0[xX][0-9a-fA-F]+")) {
                if (!isPublicAddress(InetAddress.getByName(host))) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器不允许访问本机或内网地址", "The browser cannot access local or private network addresses"));
            }
            return uri;
        }
        static void validatePublic(String value) throws Exception {
            URI uri = parse(value);
            validateAddresses(InetAddress.getAllByName(uri.getHost()));
        }
        static void validatePublic(Context context, String value) throws Exception {
            URI uri = parse(value);
            validateAddresses(BrowserDns.resolve(context, uri.getHost()));
        }
        private static void validateAddresses(InetAddress[] addresses) {
            if (addresses.length == 0) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("网页地址无法解析", "Unable to resolve web address"));
            for (InetAddress address : addresses) if (!isPublicAddress(address)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("浏览器不允许访问本机或内网地址", "The browser cannot access local or private network addresses"));
        }
        static boolean isPublicAddress(InetAddress address) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int a = bytes[0] & 255, b = bytes[1] & 255;
                return a != 0 && a != 10 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && (b == 168 || b == 0))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && (bytes[2] & 255) == 100)))
                    && !(a == 192 && (b == 2 || (b == 88 && (bytes[2] & 255) == 99)))
                    && !(a == 203 && b == 0 && (bytes[2] & 255) == 113);
            }
            // Global unicast only: rejects unique-local, link-local, mapped/compatible and transition addresses.
            if (bytes.length != 16 || (bytes[0] & 0xe0) != 0x20) return false;
            return !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x02)
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01 && (bytes[2] & 255) < 2)
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                && !((bytes[0] & 255) == 0x3f && ((bytes[1] & 255) == 0xfe || (bytes[1] & 255) == 0xff));
        }
    }

}

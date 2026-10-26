package io.droyagent.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.HashMap;
import java.util.Map;

/** Same-app webpage policy and actual embedded-surface visibility, independent of service importance. */
public final class BrowserUiProtection extends ContentProvider {
    private static final Map<IBinder, IBinder.DeathRecipient> WINDOWS = new HashMap<>();
    static synchronized boolean isVisible() { return !WINDOWS.isEmpty(); }
    static boolean isVisible(Context context) { return isVisible(); }

    static void publish(Context context, IBinder window, boolean visible) {
        Bundle data = new Bundle(); data.putBinder("window", window); data.putBoolean("visible", visible);
        Bundle acknowledged = context.getContentResolver().call(Uri.parse("content://" + context.getPackageName() + ".browser-ui"), "visibility", null, data);
        if (acknowledged == null) throw new SecurityException(io.droyagent.app.NativeLocale.text("浏览器界面保护未确认", "Browser UI protection was not confirmed"));
    }

    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException(io.droyagent.app.NativeLocale.text("仅限应用自身", "Only the app itself may access this"));
        if ("dns-policy".equals(method)) {
            Bundle policy = new Bundle();
            policy.putBoolean("encryptedDns", getContext().getSharedPreferences("product", Context.MODE_PRIVATE)
                .getBoolean("browserEncryptedDns", false));
            return policy;
        }
        if ("theme-policy".equals(method)) {
            android.content.SharedPreferences settings = getContext().getSharedPreferences("product", Context.MODE_PRIVATE);
            Bundle theme = new Bundle();
            theme.putString("preference", settings.getString("themePreference", "system"));
            theme.putString("colorScheme", settings.getString("themeColorScheme", ""));
            theme.putString("backgroundColor", settings.getString("themeBackgroundColor", ""));
            return theme;
        }
        if (!"visibility".equals(method) || extras == null || extras.getBinder("window") == null)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("无效的浏览器窗口状态", "Invalid browser window state"));
        IBinder token = extras.getBinder("window");
        synchronized (BrowserUiProtection.class) {
            IBinder.DeathRecipient previous = WINDOWS.remove(token);
            if (previous != null) token.unlinkToDeath(previous, 0);
            if (extras.getBoolean("visible")) {
                IBinder.DeathRecipient death = () -> { synchronized (BrowserUiProtection.class) { WINDOWS.remove(token); } };
                try { token.linkToDeath(death, 0); WINDOWS.put(token, death); }
                catch (RemoteException dead) { /* A dead browser has no visible controls. */ }
            }
        }
        return Bundle.EMPTY;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

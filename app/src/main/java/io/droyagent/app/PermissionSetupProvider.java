package io.droyagent.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import io.droyagent.androiduse.DroyAccessibilityService;

/** A local ADB setup entry; ordinary apps and browser content cannot change these choices. */
public final class PermissionSetupProvider extends ContentProvider {
    static boolean allowedUid(int caller, int ownUid) {
        return caller == 0 || caller == 2000 || caller == ownUid;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!allowedUid(Binder.getCallingUid(), Process.myUid())) throw new SecurityException(io.droyagent.app.NativeLocale.text("仅限本机 ADB 设置与应用自身", "Only local ADB setup and the app itself may access this"));
        if (method != null && method.startsWith("setup-")) {
            try {
                org.json.JSONObject state;
                switch (method) {
                    case "setup-status": state = SetupManager.status(getContext()); break;
                    case "setup-configure":
                        if (arg == null || arg.length() > 24576) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装配置无效或过大", "Invalid or oversized installation configuration"));
                        if (!arg.matches("[A-Za-z0-9_-]+={0,2}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装配置编码无效", "Invalid installation configuration encoding"));
                        byte[] bytes = android.util.Base64.decode(arg, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
                        String json = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                        state = SetupManager.update(getContext(), new org.json.JSONObject(json)); break;
                    case "setup-verify": state = SetupManager.verifyFromInstaller(getContext()); break;
                    case "setup-complete": state = SetupManager.completeFromInstaller(getContext()); break;
                    default: throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的安装设置操作", "Unsupported installation settings operation"));
                }
                Bundle result = new Bundle();
                result.putInt("schemaVersion", 1);
                result.putString("setupJson", state.toString());
                return result;
            } catch (IllegalArgumentException | IllegalStateException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装设置未完成：", "Installation setup incomplete: ") + failure.getClass().getSimpleName()); }
        }
        if ("enable-all".equals(method)) ProductPermissions.enableAll(getContext());
        else if (!"status".equals(method)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的权限设置操作", "Unsupported permission setup operation"));
        Bundle result = new Bundle();
        result.putInt("schemaVersion", 1);
        result.putBoolean("enabled", ProductPermissions.allEnabled(getContext()));
        result.putBoolean("stopped", ProductPermissions.stopped(getContext()));
        result.putBoolean("accessibilityConnected", DroyAccessibilityService.isConnected(getContext()));
        io.droyagent.androiduse.AccessibilityRecovery recovery = io.droyagent.androiduse.AccessibilityRecovery.get(getContext());
        result.putBoolean("accessibilityEnabled", recovery.enabled());
        result.putBoolean("accessibilityRecoveryAuthorized", recovery.authorized());
        result.putBoolean("accessibilityRecoveryReady", recovery.maintenanceReady());
        result.putString("accessibilityRecoveryStatus", recovery.status());
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

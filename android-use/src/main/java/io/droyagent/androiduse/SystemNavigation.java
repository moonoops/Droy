package io.droyagent.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import java.util.List;

final class SystemNavigation {
    static final List<String> GLOBAL_OPERATIONS = List.of("home", "recents", "notifications", "quick_settings");
    static final List<String> SETTINGS_PAGES = List.of("app_details", "privacy", "battery_saver", "usage_access", "wifi");

    private SystemNavigation() {}

    static int globalId(Object operation) throws ActionException {
        if (!(operation instanceof String)) throw new ActionException("invalid_argument", "operation must name a system action");
        return switch ((String) operation) {
            case "home" -> AccessibilityService.GLOBAL_ACTION_HOME;
            case "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS;
            case "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
            case "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
            default -> throw new ActionException("invalid_argument", "operation must be home, recents, notifications or quick_settings");
        };
    }

    static String page(Object value) throws ActionException {
        if (!(value instanceof String) || !SETTINGS_PAGES.contains(value))
            throw new ActionException("invalid_argument", "page must be app_details, privacy, battery_saver, usage_access or wifi");
        return (String) value;
    }

    static String settingsAction(String page) throws ActionException {
        return switch (page) {
            case "app_details" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
            case "privacy" -> Settings.ACTION_PRIVACY_SETTINGS;
            case "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS;
            case "usage_access" -> Settings.ACTION_USAGE_ACCESS_SETTINGS;
            case "wifi" -> Settings.ACTION_WIFI_SETTINGS;
            default -> throw new ActionException("invalid_argument", "Unsupported settings page");
        };
    }

    static Intent settingsIntent(Context context, String page, String packageName) throws ActionException {
        Intent intent = new Intent(settingsAction(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (page.equals("app_details")) intent.setData(Uri.fromParts("package", packageName, null));
        // Android's public action determines the destination; restrict resolution
        // to a system handler so a third-party app cannot impersonate Settings.
        List<ResolveInfo> matches = context.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY);
        for (ResolveInfo match : matches) {
            if (match.activityInfo != null && match.activityInfo.enabled && match.activityInfo.exported)
                return intent.setClassName(match.activityInfo.packageName, match.activityInfo.name);
        }
        return null;
    }
}

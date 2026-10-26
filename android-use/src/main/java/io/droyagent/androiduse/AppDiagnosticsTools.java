package io.droyagent.androiduse;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.UserManager;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public SDK app queries; product authorization and task cancellation wrap each call. */
public final class AppDiagnosticsTools {
    private final Context context;

    public AppDiagnosticsTools(Context context) { this.context = context.getApplicationContext(); }

    public JSONObject capabilities() throws Exception {
        boolean usage = usageAllowed();
        return new JSONObject()
                .put("app_info", new JSONObject().put("available", true).put("scope", "current_user_visible_packages"))
                .put("app_exit_info", new JSONObject().put("available", true)
                        .put("otherAppsAvailable", permission(Manifest.permission.DUMP)).put("crossUidPermission", "android.permission.DUMP"))
                .put("network_usage", new JSONObject().put("available", usage)
                        .put("reason", usage ? JSONObject.NULL : "usage_permission_required"));
    }

    public JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        JSONObject result;
        switch (action) {
            case "app_info": result = appInfo(args, cancellation); break;
            case "app_exit_info": result = exitInfo(args, cancellation); break;
            case "network_usage": result = networkUsage(args, cancellation); break;
            default: throw new ActionException("unsupported_action", "Unsupported application query: " + action);
        }
        cancellation.check();
        return result;
    }

    private JSONObject appInfo(JSONObject args, Cancellation cancellation) throws Exception {
        PackageManager manager = context.getPackageManager();
        String target = args.has("packageName") ? DiagnosticsValidation.packageName(args.get("packageName")) : "";
        String query = PersonalDataTools.search(args.opt("query"), false).toLowerCase(Locale.ROOT);
        String permission = DiagnosticsValidation.permission(args.opt("permission"));
        if (!target.isEmpty() && (!query.isEmpty() || !permission.isEmpty()))
            throw new ActionException("invalid_argument", "packageName cannot be combined with query or permission filters");
        int limit = PersonalDataTools.limit(args.opt("limit"), 20, 100);
        int offset = args.has("offset") ? ActionValidation.integer(args.get("offset"), "offset") : 0;
        if (offset < 0 || (!target.isEmpty() && args.has("offset")))
            throw new ActionException("invalid_argument", "offset must be non-negative and applies only to list queries");
        List<PackageInfo> packages = target.isEmpty() ? manager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                : List.of(packageInfo(target));
        packages = new ArrayList<>(packages);
        packages.sort(Comparator.comparing(info -> info.packageName));
        JSONArray items = new JSONArray();
        int matching = 0;
        PersonalDataTools.TextBudget text = new PersonalDataTools.TextBudget();
        for (PackageInfo info : packages) {
            cancellation.check();
            if (!DiagnosticsValidation.requestsPermission(info.requestedPermissions, permission)) continue;
            String label = info.applicationInfo == null ? info.packageName : manager.getApplicationLabel(info.applicationInfo).toString();
            if (!query.isEmpty() && !info.packageName.toLowerCase(Locale.ROOT).contains(query)
                    && !label.toLowerCase(Locale.ROOT).contains(query)) continue;
            matching++;
            if (matching <= offset) continue;
            if (items.length() == limit || text.exhausted()) continue;
            text.beginItem();
            JSONObject item = new JSONObject().put("packageName", text.take(info.packageName, 255))
                    .put("label", text.take(label, 200)).put("versionName", info.versionName == null ? JSONObject.NULL : text.take(info.versionName, 200))
                    .put("versionCode", info.getLongVersionCode()).put("firstInstallTimeMs", info.firstInstallTime)
                    .put("lastUpdateTimeMs", info.lastUpdateTime);
            ApplicationInfo app = info.applicationInfo;
            item.put("uid", app == null ? JSONObject.NULL : app.uid)
                    .put("targetSdk", app == null ? JSONObject.NULL : app.targetSdkVersion)
                    .put("enabled", app == null ? JSONObject.NULL : app.enabled)
                    .put("systemApp", app == null ? JSONObject.NULL : (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            JSONArray permissions = new JSONArray();
            int count = info.requestedPermissions == null ? 0 : info.requestedPermissions.length;
            int emitted = 0;
            for (int i = 0; i < count; i++) {
                cancellation.check();
                if (text.exhausted()) break;
                String name = text.take(info.requestedPermissions[i], 255);
                if (text.itemTruncated && name.length() != info.requestedPermissions[i].length()) break;
                Object granted = info.requestedPermissionsFlags == null || i >= info.requestedPermissionsFlags.length ? JSONObject.NULL
                        : (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                permissions.put(new JSONObject().put("name", name).put("granted", granted));
                emitted++;
            }
            item.put("permissions", permissions).put("permissionCount", count).put("permissionsTruncated", emitted < count)
                    .put("contentTruncated", text.itemTruncated || emitted < count);
            items.put(item);
        }
        int next = offset + items.length();
        return result("android_package_manager", items, limit, matching > next || text.truncated)
                .put("totalMatches", matching).put("scope", "current_user_visible_packages")
                .put("offset", offset).put("nextOffset", next < matching && items.length() > 0 ? next : JSONObject.NULL)
                .put("permissionMeaning", "Declared and currently granted permissions do not prove actual access; foreground, AppOps and other restrictions may still apply");
    }

    private JSONObject exitInfo(JSONObject args, Cancellation cancellation) throws Exception {
        String target = DiagnosticsValidation.packageName(args.opt("packageName"));
        PackageInfo targetInfo = packageInfo(target);
        int uid = targetInfo.applicationInfo.uid;
        if (uid != android.os.Process.myUid() && !permission(Manifest.permission.DUMP))
            throw new ActionException("dump_permission_required", "Reading another UID's exit records requires android.permission.DUMP");
        int limit = PersonalDataTools.limit(args.opt("limit"), 10, 50);
        boolean traces = DiagnosticsValidation.bool(args.opt("includeTrace"), "includeTrace", false);
        int traceMax = args.has("traceMaxBytes") ? ActionValidation.integer(args.get("traceMaxBytes"), "traceMaxBytes") : 16384;
        if (traceMax < 1 || traceMax > 65536) throw new ActionException("invalid_argument", "traceMaxBytes must be between 1 and 65536");
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new ActionException("exit_info_unavailable", "Android activity service is unavailable");
        List<ApplicationExitInfo> exits;
        try { exits = manager.getHistoricalProcessExitReasons(target, 0, limit + 1); }
        catch (SecurityException denied) { throw new ActionException("dump_permission_required", "Android did not permit reading these exit records"); }
        cancellation.check();
        JSONArray items = new JSONArray();
        PersonalDataTools.TextBudget text = new PersonalDataTools.TextBudget();
        int traceBudget = traceMax;
        for (ApplicationExitInfo exit : exits) {
            cancellation.check();
            if (items.length() == limit || text.exhausted()) break;
            text.beginItem();
            JSONObject item = new JSONObject().put("timestampMs", exit.getTimestamp()).put("reasonCode", exit.getReason())
                    .put("reason", reasonName(exit.getReason())).put("pid", exit.getPid()).put("uid", exit.getRealUid())
                    .put("processName", text.take(exit.getProcessName(), 255)).put("description", text.take(exit.getDescription(), 2000))
                    .put("exitStatus", exit.getStatus()).put("importance", exit.getImportance())
                    .put("pssKb", exit.getPss() == 0 ? JSONObject.NULL : exit.getPss())
                    .put("rssKb", exit.getRss() == 0 ? JSONObject.NULL : exit.getRss()).put("contentTruncated", text.itemTruncated);
            if (traces) {
                if (traceBudget == 0) item.put("trace", new JSONObject().put("status", "omitted").put("reason", "query_trace_budget"));
                else {
                    JSONObject trace = trace(exit, traceBudget, cancellation);
                    traceBudget -= trace.optInt("bytes", 0);
                    item.put("trace", trace);
                }
            }
            items.put(item);
        }
        return result("android_application_exit_info", items, limit, exits.size() > items.length() || text.truncated)
                .put("packageName", target).put("uid", uid).put("retention", "recent_system_ring_buffer")
                .put("meaning", "System-recorded process exit reason; a cause code or diagnostic description is not a proven root cause or a guaranteed Java stack trace");
    }

    private JSONObject trace(ApplicationExitInfo exit, int maximum, Cancellation cancellation) throws Exception {
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) return new JSONObject().put("status", "unavailable").put("reason", "not_retained");
            byte[] bytes = readTrace(input, maximum, cancellation);
            boolean truncated = bytes.length > maximum;
            if (truncated) bytes = java.util.Arrays.copyOf(bytes, maximum);
            boolean nativeCrash = nativeTraceFormat(Build.VERSION.SDK_INT, exit.getReason());
            return new JSONObject().put("status", "available").put("bytes", bytes.length).put("truncated", truncated)
                    .put("format", nativeCrash ? "tombstone_proto" : "anr_text")
                    .put("encoding", nativeCrash ? "base64" : "utf8")
                    .put("data", nativeCrash ? Base64.encodeToString(bytes, Base64.NO_WRAP) : new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException unavailable) {
            return new JSONObject().put("status", "unavailable").put("reason", "trace_read_failed");
        } catch (SecurityException denied) {
            return new JSONObject().put("status", "unavailable").put("reason", "trace_permission_required");
        }
    }

    static byte[] readTrace(InputStream input, int maximum, Cancellation cancellation) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        while (output.size() <= maximum) {
            cancellation.check();
            int read = input.read(block, 0, Math.min(block.length, maximum + 1 - output.size()));
            if (read < 0) break;
            output.write(block, 0, read);
        }
        cancellation.check();
        return output.toByteArray();
    }

    static boolean nativeTraceFormat(int sdk, int reason) {
        // Native tombstones were added in API 31. Earlier records may retain a
        // previous ANR trace even when the eventual exit was a native crash.
        return sdk >= 31 && reason == ApplicationExitInfo.REASON_CRASH_NATIVE;
    }

    private JSONObject networkUsage(JSONObject args, Cancellation cancellation) throws Exception {
        if (!usageAllowed()) throw new ActionException("usage_permission_required", "Allow usage access in Android settings");
        String target = DiagnosticsValidation.packageName(args.opt("packageName"));
        int uid = packageInfo(target).applicationInfo.uid;
        long start = DiagnosticsValidation.timestamp(args.opt("startMs"), "startMs");
        long end = DiagnosticsValidation.timestamp(args.opt("endMs"), "endMs");
        PersonalDataTools.validateRange(start, end, PersonalDataTools.MAX_RANGE_MS);
        if (end > System.currentTimeMillis()) throw new ActionException("invalid_argument", "Network range must not end in the future");
        String type = DiagnosticsValidation.choice(args.opt("networkType"), "networkType", "all", Set.of("all", "wifi", "mobile"));
        int limit = PersonalDataTools.limit(args.opt("limit"), 100, 500);
        UserManager users = context.getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) throw new ActionException("user_locked", "Unlock the Android user after restart before querying statistics");
        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        if (manager == null) throw new ActionException("network_usage_unavailable", "Android network statistics service is unavailable");
        JSONArray items = new JSONArray();
        long rx = 0, tx = 0, first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        int count = 0;
        for (String network : type.equals("all") ? List.of("wifi", "mobile") : List.of(type)) {
            cancellation.check();
            NetworkStats stats;
            try { stats = manager.queryDetailsForUid(network.equals("wifi") ? ConnectivityManager.TYPE_WIFI : ConnectivityManager.TYPE_MOBILE, null, start, end, uid); }
            catch (SecurityException denied) { throw new ActionException("usage_permission_required", "Android did not permit reading these network statistics"); }
            if (stats == null) throw new ActionException("network_usage_unavailable", "Android did not return network statistics");
            try (stats) {
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                while (stats.hasNextBucket()) {
                    cancellation.check();
                    if (!stats.getNextBucket(bucket)) throw new ActionException("network_usage_unavailable", "Android could not read a network statistics bucket");
                    rx += bucket.getRxBytes(); tx += bucket.getTxBytes(); count++;
                    first = Math.min(first, bucket.getStartTimeStamp()); last = Math.max(last, bucket.getEndTimeStamp());
                    if (items.length() < limit) items.put(new JSONObject().put("networkType", network)
                            .put("bucketStartMs", bucket.getStartTimeStamp()).put("bucketEndMs", bucket.getEndTimeStamp())
                            .put("rxBytes", bucket.getRxBytes()).put("txBytes", bucket.getTxBytes()).put("state", bucket.getState())
                            .put("metered", bucket.getMetered()).put("roaming", bucket.getRoaming()));
                }
            }
        }
        cancellation.check();
        if (!usageAllowed()) throw new ActionException("usage_permission_required", "Usage access was revoked while reading");
        String[] uidPackages = context.getPackageManager().getPackagesForUid(uid);
        return result("android_network_stats", items, limit, count > items.length()).put("packageName", target).put("uid", uid)
                .put("uidPackages", uidPackages == null ? new JSONArray() : new JSONArray(uidPackages))
                .put("sharedUid", uidPackages != null && uidPackages.length > 1)
                .put("range", new JSONObject().put("startMs", start).put("endMs", end))
                .put("returnedBucketRange", count == 0 ? JSONObject.NULL : new JSONObject().put("startMs", first).put("endMs", last))
                .put("totals", new JSONObject().put("rxBytes", rx).put("txBytes", tx).put("bucketCount", count))
                .put("totalsScope", "all_matching_system_buckets_including_rows_omitted_by_limit")
                .put("rangePrecision", "historical_buckets_not_realtime")
                .put("meaning", "UID-level recorded traffic, not domains or payload; shared UID traffic cannot be attributed to one package, and bucket gaps do not prove no network activity");
    }

    private PackageInfo packageInfo(String target) throws ActionException {
        try { return context.getPackageManager().getPackageInfo(target, PackageManager.GET_PERMISSIONS); }
        catch (PackageManager.NameNotFoundException missing) {
            throw new ActionException("app_not_visible", "Package is not visible to this Android user; this does not prove it is uninstalled");
        }
    }

    private boolean permission(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    private boolean usageAllowed() {
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);
        return manager != null && manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private static JSONObject result(String source, JSONArray items, int limit, boolean truncated) throws Exception {
        return new JSONObject().put("status", "queried").put("source", source).put("observedAtMs", System.currentTimeMillis())
                .put("items", items).put("count", items.length()).put("limit", limit).put("truncated", truncated).put("readOnly", true);
    }

    static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory";
            case ApplicationExitInfo.REASON_CRASH: return "java_crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency_died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            case ApplicationExitInfo.REASON_FREEZER: return "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "package_state_change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "package_updated";
            default: return "unknown";
        }
    }
}

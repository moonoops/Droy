package io.droyagent.androiduse;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-demand system diagnostics through fixed, read-only App-UID commands. */
public final class SystemDiagnosticsTools {
    private final Context context;

    public SystemDiagnosticsTools(Context context) { this.context = context.getApplicationContext(); }

    public JSONObject capabilities() throws Exception {
        String reason = missingPermission();
        JSONObject status = new JSONObject().put("available", reason == null)
                .put("reason", reason == null ? "ready" : reason)
                .put("backend", "app_uid_dumpsys").put("requires", new JSONArray(List.of("DUMP", "PACKAGE_USAGE_STATS", "usage_access")))
                .put("formatVerifiedOnQuery", true);
        return new JSONObject().put("privacy_usage", new JSONObject(status.toString()))
                .put("battery_usage", new JSONObject(status.toString()));
    }

    public JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (!action.equals("privacy_usage") && !action.equals("battery_usage"))
            throw new ActionException("unsupported_action", "Unknown system diagnostic query");
        String missing = missingPermission();
        if (missing != null) throw new ActionException(missing,
                "System diagnostics require DUMP, the PACKAGE_USAGE_STATS grant from explicit PC setup, and Android usage access");
        JSONObject result = action.equals("privacy_usage") ? privacy(args, cancellation) : battery(args, cancellation);
        cancellation.check();
        if (missingPermission() != null) throw new ActionException("diagnostics_permission_required", "Diagnostic access was revoked while querying");
        return result;
    }

    private JSONObject privacy(JSONObject args, Cancellation cancellation) throws Exception {
        String pkg = args.has("packageName") ? packageName(args.get("packageName")) : null;
        if (pkg != null) application(pkg);
        Object selected = args.has("resource") ? args.get("resource") : "all";
        if (!(selected instanceof String) || !List.of("camera", "microphone", "all").contains(selected))
            throw new ActionException("invalid_argument", "resource must be camera, microphone or all");
        String resource = (String) selected;
        int limit = args.has("limit") ? ActionValidation.integer(args.get("limit"), "limit") : 50;
        if (limit < 1 || limit > 100) throw new ActionException("invalid_argument", "limit must be between 1 and 100");
        List<Map<String, Object>> items = new ArrayList<>();
        boolean truncated = false;
        List<String> ops = resource.equals("all") ? List.of("CAMERA", "RECORD_AUDIO")
                : List.of(resource.equals("camera") ? "CAMERA" : "RECORD_AUDIO");
        long startedAt = System.currentTimeMillis();
        for (String op : ops) {
            String output = run(privacyCommand(pkg, op, Process.myUid() / 100000), 1024 * 1024, 5000, cancellation);
            DiagnosticParsing.Privacy parsed = DiagnosticParsing.privacy(output, pkg, Process.myUid() / 100000,
                    limit, Locale.getDefault(), TimeZone.getDefault(), cancellation);
            items.addAll(parsed.items);
            truncated |= parsed.truncated;
        }
        items.sort(DiagnosticParsing.PRIVACY_ORDER);
        truncated |= items.size() > limit;
        JSONArray records = new JSONArray();
        for (int index = 0; index < Math.min(items.size(), limit); index++) records.put(json(items.get(index)));
        return new JSONObject().put("status", "queried").put("source", "android_appops_dumpsys")
                .put("capturedAtMs", System.currentTimeMillis()).put("queryStartedAtMs", startedAt)
                .put("userId", Process.myUid() / 100000)
                .put("packageName", pkg == null ? JSONObject.NULL : pkg).put("resource", resource)
                .put("granularity", "latest_recorded_access_and_rejection_per_uid_state_and_attribution")
                .put("historyComplete", false).put("retentionStartMs", JSONObject.NULL)
                .put("timeZone", TimeZone.getDefault().getID()).put("items", records).put("count", records.length())
                .put("limit", limit).put("truncated", truncated).put("readOnly", true)
                .put("meaning", "Records prove reported resource access or rejection, not captured content or data upload; no record does not prove no access");
    }

    private JSONObject battery(JSONObject args, Cancellation cancellation) throws Exception {
        String pkg = packageName(args.opt("packageName"));
        int uid = application(pkg).uid;
        String output = run(batteryCommand(pkg, uid), 128 * 1024, 11000, cancellation);
        JSONObject values = json(DiagnosticParsing.battery(output, uid, cancellation));
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        long capturedAt = System.currentTimeMillis();
        return new JSONObject().put("status", "queried").put("source", "android_batterystats_text")
                .put("capturedAtMs", capturedAt).put("packageName", pkg).put("uid", uid)
                .put("uidPackages", packages == null ? new JSONArray() : new JSONArray(Arrays.asList(packages)))
                .put("sharedUid", packages != null && packages.length > 1).put("attribution", "uid")
                .put("period", new JSONObject().put("kind", "system_since_charged_or_statistics_reset")
                        .put("startMs", values.opt("statisticsStartMs")).put("capturedAtMs", capturedAt))
                .put("statistics", values).put("readOnly", true)
                .put("missingValues", "null means the system did not expose that field; omitted UID records are not zero-filled")
                .put("meaning", "System energy estimates and activity counters for this UID, not a precise measurement or a custom requested time interval");
    }

    static List<String> privacyCommand(String pkg, String op, int user) throws ActionException {
        if (!op.equals("CAMERA") && !op.equals("RECORD_AUDIO"))
            throw new ActionException("invalid_argument", "Unsupported diagnostic resource");
        String filter = privacyFilter(pkg, user);
        // AppOps --package resolves packages across users and can demand additional
        // cross-user privileges. Keep a current-user-only stream without that flag.
        String command = "set -o pipefail; /system/bin/dumpsys -t 4 appops --op " + op
                + " | /system/bin/sed -E -n '" + filter + "'";
        return List.of("/system/bin/sh", "-c", command);
    }

    static String privacyFilter(String pkg, int user) throws ActionException {
        if (user < 0) throw new ActionException("invalid_argument", "user must be nonnegative");
        if (pkg != null) packageName(pkg);
        String uidPattern = user == 0 ? "(u0(ai|[ais])[0-9]+|[0-9]{1,5})"
                : "(u" + user + "(ai|[ais])[0-9]+|" + user + "[0-9]{5})";
        String packagePattern = pkg == null ? "[A-Za-z][A-Za-z0-9_.]*" : pkg.replace(".", "\\.");
        return String.format(Locale.ROOT, """
                :top
                /^Current AppOps Service state:$/p
                /Permission [Dd]enial|DUMP TIMEOUT|Unknown option|Can.t find service/p
                /^[[:space:]]*Uid /{
                  /^  Uid (u[0-9]+(ai|[ais])[0-9]+|[0-9]+):$/!{
                    s/.*/DROY_DIAGNOSTIC_CONTEXT_UNSUPPORTED/
                    p
                    b
                  }
                  /^  Uid %s:$/!b
                  p
                  :uid
                  n
                  /^[[:space:]]*Uid /b top
                  :package
                  /^[[:space:]]*Package /{
                    /^    Package [A-Za-z][A-Za-z0-9_.]*:$/!{
                      s/.*/DROY_DIAGNOSTIC_CONTEXT_UNSUPPORTED/
                      p
                      b
                    }
                    /^    Package %s:$/!b uid
                    p
                    :body
                    n
                    /^[[:space:]]*Uid /b top
                    /^[[:space:]]*Package /b package
                    /^[[:space:]]*(CAMERA|RECORD_AUDIO|[A-Z][A-Z0-9_]*[[:space:]]*\\()/ {
                      /^      (CAMERA|RECORD_AUDIO) \\([^)]*\\):[[:space:]]*$/!{
                        s/.*/DROY_DIAGNOSTIC_CONTEXT_UNSUPPORTED/
                        p
                        b
                      }
                    }
                    /^([^ ]|  [^ ]|    [^ ])/b top
                    /^      /p
                    b body
                  }
                  /^([^ ]|  [^ ])/b top
                  b uid
                }
                """, uidPattern, packagePattern);
    }

    static List<String> batteryCommand(String pkg, int uid) throws ActionException {
        packageName(pkg);
        if (uid < 0) throw new ActionException("invalid_argument", "uid must be nonnegative");
        // The machine-friendly and proto exports enumerate MATCH_ANY_USER packages.
        // Text export resolves only this package in the calling Android user.
        String command = "set -o pipefail; /system/bin/dumpsys -t 10 batterystats --charged " + pkg
                + " | /system/bin/sed -E -n '" + batteryFilter(uid) + "'";
        return List.of("/system/bin/sh", "-c", command);
    }

    static String batteryFilter(int uid) throws ActionException {
        if (uid < 0) throw new ActionException("invalid_argument", "uid must be nonnegative");
        int user = uid / 100000, app = uid % 100000;
        String label = uid < 10000 ? Integer.toString(uid)
                : "u" + user + (app >= 99000 ? "i" + (app - 99000) : app >= 10000 ? "a" + (app - 10000) : "s" + app);
        String target = "(" + label + "|" + uid + ")";
        return String.format(Locale.ROOT, """
                :top
                /^Statistics since last charge:$/p
                /Permission [Dd]enial|Security exception|DUMP TIMEOUT|Unknown option|Unknown package|Can.t find service/p
                /^  (Start clock time:|Time on battery:|Total run time:)/p
                /^  Estimated power use \\(mAh\\):$/ {
                  p
                  :energy
                  n
                  /^$/b top
                  /^ *(Uid|UID) %s: /p
                  b energy
                }
                /^  %s:$/ {
                  p
                  :uid
                  n
                  /^([^ ]|  [^ ])/b top
                  /^    (Total cpu time:|TOTAL wake:)/p
                  b uid
                }
                """, target, target);
    }

    private String run(List<String> command, int limit, long timeoutMs, Cancellation cancellation) throws Exception {
        CommandRunner.Result result;
        try { result = CommandRunner.run(command, limit, timeoutMs, cancellation); }
        catch (ActionException error) {
            if (error.getCode().equals("root_timeout"))
                throw new ActionException("diagnostic_timeout", "Read-only system diagnostic query timed out", error);
            if (error.getCode().equals("root_output_error"))
                throw new ActionException("diagnostic_output_limit", "System diagnostic output could not be read within its limit", error);
            throw error;
        }
        String output = new String(result.stdout, StandardCharsets.UTF_8);
        DiagnosticParsing.serviceOutput(output);
        if (result.exitCode != 0) throw new ActionException("diagnostic_query_failed", "The system diagnostic command did not complete successfully");
        return output;
    }

    private ApplicationInfo application(String pkg) throws Exception {
        try { return context.getPackageManager().getApplicationInfo(pkg, 0); }
        catch (PackageManager.NameNotFoundException missing) {
            throw new ActionException("app_not_found", "The application is not visible in the current Android user");
        }
    }

    private String missingPermission() {
        if (context.checkSelfPermission("android.permission.DUMP") != PackageManager.PERMISSION_GRANTED)
            return "dump_permission_required";
        if (context.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") != PackageManager.PERMISSION_GRANTED)
            return "usage_stats_permission_required";
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops == null) return "usage_permission_required";
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_DEFAULT)
            return "usage_permission_required";
        return null;
    }

    private static String packageName(Object value) throws ActionException {
        return "android".equals(value) ? "android" : ActionValidation.packageName(value);
    }

    private static JSONObject json(Map<String, Object> values) throws Exception {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> entry : values.entrySet())
            result.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
        return result;
    }
}

package io.droyagent.androiduse;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Launcher-visible application lookup; Android visibility is deliberately not bypassed. */
public final class AppTools {
    private final Context context;
    public AppTools(Context context) { this.context = context.getApplicationContext(); }

    public JSONObject find(JSONObject args, Cancellation cancellation) throws Exception {
        String query = query(args.opt("query"), "query");
        int limit = args.has("limit") ? ActionValidation.integer(args.get("limit"), "limit") : 10;
        if (limit < 1 || limit > 30) throw new ActionException("invalid_argument", "limit must be between 1 and 30");
        List<App> matches = matches(query, cancellation);
        JSONArray items = new JSONArray();
        for (int index = 0; index < Math.min(limit, matches.size()); index++) items.put(matches.get(index).json());
        return new JSONObject().put("status", matches.isEmpty() ? "not_found" : matches.size() == 1 ? "unique" : "ambiguous")
                .put("query", query).put("apps", items).put("totalMatches", matches.size())
                .put("truncated", matches.size() > limit).put("visibility", "visible_launcher_apps")
                .put("visibilityNote", "Android may hide installed apps; no match does not prove an app is uninstalled");
    }

    List<App> matches(String query, Cancellation cancellation) throws Exception {
        String normalized = query.toLowerCase(Locale.ROOT);
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, App> byPackage = new HashMap<>();
        for (ResolveInfo resolve : context.getPackageManager().queryIntentActivities(intent, 0)) {
            cancellation.check();
            if (resolve.activityInfo == null || !resolve.activityInfo.enabled || !resolve.activityInfo.exported) continue;
            String packageName = resolve.activityInfo.packageName;
            CharSequence label = resolve.loadLabel(context.getPackageManager());
            String name = label == null ? packageName : label.toString();
            String normalizedName = name.toLowerCase(Locale.ROOT);
            String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
            if (!normalizedName.contains(normalized) && !normalizedPackage.contains(normalized)) continue;
            int rank = normalizedPackage.equals(normalized) ? 0 : normalizedName.equals(normalized) ? 1 : 2;
            App app = new App(packageName, name, rank);
            App previous = byPackage.get(packageName);
            if (previous == null || app.rank < previous.rank) byPackage.put(packageName, app);
        }
        List<App> matches = new ArrayList<>(byPackage.values());
        matches.sort(Comparator.comparingInt((App value) -> value.rank).thenComparing(value -> value.label)
                .thenComparing(value -> value.packageName));
        return matches;
    }

    static String query(Object value, String name) throws ActionException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()
                || ((String) value).length() > 200 || ((String) value).indexOf('\0') >= 0) {
            throw new ActionException("invalid_argument", name + " must contain 1 to 200 characters without NUL");
        }
        return ((String) value).trim();
    }

    static final class App {
        final String packageName;
        final String label;
        final int rank;
        App(String packageName, String label, int rank) { this.packageName = packageName; this.label = label; this.rank = rank; }
        JSONObject json() throws Exception { return new JSONObject().put("packageName", packageName).put("label", label); }
    }
}

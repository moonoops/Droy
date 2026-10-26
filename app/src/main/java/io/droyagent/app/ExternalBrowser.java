package io.droyagent.app;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import org.json.JSONObject;
import java.util.*;

/** Select a general browser handler so an App Link cannot reopen the target App. */
final class ExternalBrowser {
    static JSONObject open(Context context, String url) throws Exception { return open(context, url, () -> {}); }
    static JSONObject open(Context context, String url, io.droyagent.androiduse.Cancellation cancellation) throws Exception {
        BrowserSession.UrlPolicy.parse(url);
        return BrowserSession.onMain(cancellation, () -> {
            PackageManager packages = context.getPackageManager();
            Intent generic = new Intent(Intent.ACTION_VIEW, Uri.parse("https://droy-browser-probe.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
            int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER;
            List<ResolveInfo> candidates = packages.queryIntentActivities(generic, flags);
            Map<String, Intent> choices = new LinkedHashMap<>();
            for (ResolveInfo candidate : candidates) {
                // A browser accepts the scheme without a domain-specific authority filter.
                if (candidate.activityInfo == null || candidate.filter == null || candidate.filter.countDataAuthorities() != 0) continue;
                String name = candidate.activityInfo.packageName;
                choices.put(name, new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(name));
            }
            if (choices.isEmpty()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("没有可用的系统浏览器，请先安装或启用浏览器", "No system browser is available. Please install or enable one"));
            ResolveInfo preferred = packages.resolveActivity(generic, PackageManager.MATCH_DEFAULT_ONLY);
            String preferredPackage = preferred == null || preferred.activityInfo == null ? null : preferred.activityInfo.packageName;
            Intent view = choices.get(preferredPackage);
            if (view == null && choices.size() == 1) view = choices.values().iterator().next();
            if (view == null) {
                List<Intent> entries = new ArrayList<>(choices.values()); Intent first = entries.remove(0);
                view = Intent.createChooser(first, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, entries.toArray(new android.os.Parcelable[0]));
            }
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); cancellation.check(); context.startActivity(view);
            return new JSONObject().put("status", "navigation_dispatched").put("target", "external").put("goalVerified", false)
                .put("message", io.droyagent.app.NativeLocale.text("已交给系统浏览器，请核实页面是否打开", "Handed off to the system browser. Please check whether the page opened"));
        });
    }
}

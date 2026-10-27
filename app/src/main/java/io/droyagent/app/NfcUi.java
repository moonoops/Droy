package io.droyagent.app;

import android.app.Activity;
import android.graphics.Color;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.droyagent.androiduse.HardwareSupport;

final class NfcUi {
    private NfcUi() {}
    static boolean dark(Activity activity) { return ProductPermissions.preferences(activity).getString("themeColorScheme", "light").equals("dark"); }
    static LinearLayout body(Activity activity) {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density); body.setPadding(padding, padding, padding, padding);
        return body;
    }
    static TextView text(Activity activity, int resource, int size) {
        return text(activity, HardwareSupport.text(activity, resource), size);
    }
    static TextView text(Activity activity, CharSequence copy, int size) {
        TextView result = new TextView(activity); result.setText(copy); result.setTextSize(size);
        result.setTextColor(dark(activity) ? Color.WHITE : Color.rgb(25,31,39));
        int space = Math.round(12 * activity.getResources().getDisplayMetrics().density); result.setPadding(0, space, 0, space);
        return result;
    }
    static void show(Activity activity, LinearLayout body) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setForceDarkAllowed(false);
        scroll.setBackgroundColor(dark(activity) ? Color.rgb(21,21,23) : Color.WHITE); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            scroll.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            android.view.WindowInsetsController controls = v.getWindowInsetsController();
            if (controls != null) controls.setSystemBarsAppearance(dark(activity) ? 0 : lightBars, lightBars);
            return insets;
        });
        activity.setContentView(scroll);
        scroll.requestApplyInsets();
    }
}

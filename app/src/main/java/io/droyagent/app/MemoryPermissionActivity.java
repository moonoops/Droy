package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit permission entry; receiving a permission never turns recording on. */
public final class MemoryPermissionActivity extends Activity {
    private static final int LOCATION = 84;
    private TextView status;
    private String source;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        source = getIntent().getStringExtra("source");
        if (!java.util.Set.of("location", "backgroundLocation", "accessibility").contains(source == null ? "" : source)) { finish(); return; }
        getWindow().setDecorFitsSystemWindows(false);
        boolean dark = ProductPermissions.preferences(this).getString("themeColorScheme", "light").equals("dark");
        int foreground = dark ? android.graphics.Color.WHITE : android.graphics.Color.rgb(25,31,39);
        int background = dark ? android.graphics.Color.rgb(21,21,23) : android.graphics.Color.WHITE;
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density); body.setPadding(padding,padding,padding,padding);
        TextView title = new TextView(this); title.setText(source.equals("accessibility") ? io.droyagent.app.NativeLocale.text("手机活动记录权限", "Phone activity recording permission") : io.droyagent.app.NativeLocale.text("位置记录权限", "Location recording permission"));
        title.setTextSize(22); title.setTextColor(foreground); body.addView(title);
        status = new TextView(this); status.setTextSize(16); status.setTextColor(foreground); status.setPadding(0,padding,0,padding); body.addView(status);
        if (source.equals("accessibility")) {
            button(body, io.droyagent.app.NativeLocale.text("打开无障碍设置", "Open accessibility settings"), () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        } else {
            button(body, io.droyagent.app.NativeLocale.text("允许访问位置", "Allow location access"), () -> requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION));
            button(body, io.droyagent.app.NativeLocale.text("管理后台位置权限", "Manage background location permission"), () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
            button(body, io.droyagent.app.NativeLocale.text("打开系统定位设置", "Open system location settings"), () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        }
        button(body, io.droyagent.app.NativeLocale.text("返回记忆设置", "Return to memory settings"), this::finish);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(background); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            android.view.WindowInsetsController controls = view.getWindowInsetsController();
            if (controls != null) controls.setSystemBarsAppearance(dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
    }
    private void button(LinearLayout body, String label, Runnable action) {
        Button button = new Button(this); button.setText(label);
        button.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(view -> action.run()); body.addView(button);
    }
    @Override protected void onResume() { super.onResume(); updateStatus(); MemoryRuntime.get(this).activityResumed(this); }
    @Override protected void onPause() { MemoryRuntime.get(this).activityPaused(this); super.onPause(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if (request == LOCATION) { updateStatus(); MemoryRuntime.get(this).activityResumed(this); }
    }
    private void updateStatus() {
        if (status == null) return;
        if (source.equals("accessibility")) {
            status.setText(io.droyagent.app.NativeLocale.text("活动记录使用已授权的无障碍服务。系统授权与记忆中的记录开关分别管理；授权后请返回记忆设置选择应用和记录内容。", "Activity recording uses the authorized accessibility service. System permission and memory recording switches are managed separately. After granting permission, return to memory settings to choose apps and recorded content."));
            return;
        }
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        status.setText(io.droyagent.app.NativeLocale.text("当前位置权限：", "Current location permission: ") + (fine ? io.droyagent.app.NativeLocale.text("精确位置", "Precise location") : coarse ? io.droyagent.app.NativeLocale.text("大致位置", "Approximate location") : io.droyagent.app.NativeLocale.text("未授权", "Not granted"))
            + io.droyagent.app.NativeLocale.text("\n后台位置权限：", "\nBackground location permission: ") + (background ? io.droyagent.app.NativeLocale.text("已允许", "Allowed") : io.droyagent.app.NativeLocale.text("未允许", "Not allowed"))
            + io.droyagent.app.NativeLocale.text("\n\n后台位置可在应用权限中选择“始终允许”。你也可以只使用系统允许的前台位置记录。\n\n授权不会自动开启记录，请返回记忆设置单独开启位置记录。", "\n\nFor background location, choose “Allow all the time” in app permissions. You can also use only foreground location recording allowed by the system.\n\nGranting permission does not start recording. Return to memory settings to enable location recording separately."));
    }
}

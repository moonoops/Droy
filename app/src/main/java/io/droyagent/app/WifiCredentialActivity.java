package io.droyagent.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.droyagent.androiduse.HardwareSupport;
import io.droyagent.androiduse.WifiCredentialStore;

/** The password remains in the local form and encrypted storage, outside model tool arguments. */
public final class WifiCredentialActivity extends Activity {
    private EditText password;
    private boolean dark;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!allowed()) { finish(); return; }
        dark = ProductPermissions.preferences(this).getString("themeColorScheme", "light").equals("dark");
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24); body.setPadding(padding, padding, padding, padding);
        body.addView(text(io.droyagent.androiduse.R.string.wifi_credential_title, 24));
        body.addView(text(io.droyagent.androiduse.R.string.wifi_credential_help, 16));
        EditText name = input(io.droyagent.androiduse.R.string.wifi_credential_name, 80);
        body.addView(name);
        password = input(io.droyagent.androiduse.R.string.wifi_credential_password, 63);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        body.addView(password);
        TextView status = text(io.droyagent.androiduse.R.string.wifi_credential_help, 16);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button save = button(io.droyagent.androiduse.R.string.wifi_credential_save);
        save.setOnClickListener(view -> {
            if (!allowed()) { password.setText(""); finish(); return; }
            try {
                new WifiCredentialStore(this).save(name.getText().toString(), password.getText().toString());
                password.setText(""); password.setEnabled(false); name.setEnabled(false); save.setEnabled(false);
                status.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.wifi_credential_saved));
            } catch (IllegalArgumentException invalid) {
                status.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.wifi_credential_invalid));
            } catch (Exception failure) {
                status.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.wifi_credential_failed));
            }
        });
        body.addView(save); body.addView(status);
        Button back = button(io.droyagent.androiduse.R.string.wifi_credential_back);
        back.setOnClickListener(view -> { password.setText(""); finish(); }); body.addView(back);
        getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body);
        scroll.setBackgroundColor(dark ? Color.rgb(21,21,23) : Color.WHITE);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!allowed()) { if (password != null) password.setText(""); finish(); }
    }

    @Override protected void onStop() {
        if (password != null) password.setText("");
        super.onStop();
    }

    private boolean allowed() {
        return !ProductPermissions.stopped(this)
                && ProductPermissions.allowed(ProductPermissions.preferences(this), "allowWifiRead")
                && ProductPermissions.allowed(ProductPermissions.preferences(this), "allowWifiControl");
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(int resource, int size) {
        TextView view = new TextView(this); view.setText(HardwareSupport.text(this, resource)); view.setTextSize(size);
        view.setTextColor(dark ? Color.WHITE : Color.rgb(25,31,39)); view.setPadding(0, dp(12), 0, dp(12)); return view;
    }
    private EditText input(int resource, int maximum) {
        EditText field = new EditText(this); field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});
        String label = HardwareSupport.text(this, resource); field.setHint(label); field.setContentDescription(label);
        field.setTextColor(dark ? Color.WHITE : Color.rgb(25,31,39));
        field.setHintTextColor(dark ? Color.LTGRAY : Color.DKGRAY); return field;
    }
    private Button button(int resource) {
        Button button = new Button(this); button.setText(HardwareSupport.text(this, resource)); button.setMinHeight(dp(48));
        if (dark) { button.setTextColor(Color.WHITE); button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(49,53,59))); }
        return button;
    }
}

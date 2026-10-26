package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Completes only a previously requested capture while a real Activity is visible. */
public final class AudioPermissionActivity extends Activity {
    private static final int MICROPHONE = 81, PROJECTION = 82;
    private String captureId;
    private boolean waiting, completed;
    private boolean visible;
    private static final java.util.concurrent.atomic.AtomicInteger visibleActivities=new java.util.concurrent.atomic.AtomicInteger();
    private TextView message;

    static boolean isVisible() { return visibleActivities.get()>0; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setDecorFitsSystemWindows(false);
        captureId = getIntent().getStringExtra("captureId");
        waiting = saved != null && saved.getBoolean("waiting");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        boolean dark=ProductPermissions.preferences(this).getString("themeColorScheme","light").equals("dark");
        layout.setBackgroundColor(dark?android.graphics.Color.rgb(21,21,23):android.graphics.Color.WHITE);
        message = new TextView(this); message.setTextSize(18);
        message.setTextColor(dark?android.graphics.Color.WHITE:android.graphics.Color.rgb(25,31,39));
        message.setText(io.droyagent.app.NativeLocale.text("正在准备音频采集授权…", "Preparing audio capture authorization…")); layout.addView(message);
        Button cancel = new Button(this); cancel.setText(io.droyagent.app.NativeLocale.text("取消采集", "Cancel capture"));
        cancel.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        cancel.setOnClickListener(view -> { deny(); finish(); }); layout.addView(cancel);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(dark?android.graphics.Color.rgb(21,21,23):android.graphics.Color.WHITE);
        scroll.addView(layout);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            android.view.WindowInsetsController controls=view.getWindowInsetsController();
            if(controls!=null) controls.setSystemBarsAppearance(dark?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                |android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!waiting && !completed) continueAuthorization();
    }

    @Override protected void onStart() { super.onStart(); if(!visible){ visible=true; visibleActivities.incrementAndGet(); } }
    @Override protected void onStop() { if(visible){ visible=false; visibleActivities.decrementAndGet(); } super.onStop(); }

    private void continueAuthorization() {
        try {
            JSONObject pending = AudioCapture.get(this).pendingAuthorization();
            if (pending == null || captureId == null || !captureId.equals(pending.optString("captureId"))) {
                completed = true; finish(); return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                message.setText(io.droyagent.app.NativeLocale.text("音频采集需要系统麦克风权限。授权后将继续本次采集。", "Audio capture requires system microphone permission. This capture will continue once permission is granted."));
                waiting = true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE); return;
            }
            if (pending.optString("source").equals("playback")) {
                message.setText(io.droyagent.app.NativeLocale.text("请选择系统允许的捕获范围。本次仅采集音频。", "Choose the capture scope allowed by the system. Only audio will be captured this time."));
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                waiting = true; startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION);
            } else {
                AudioCapture.get(this).authorize(this, captureId);
                completed = true; finish();
            }
        } catch (Exception failure) { fail(failure); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != MICROPHONE) return;
        waiting = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) continueAuthorization();
        else { deny(); message.setText(io.droyagent.app.NativeLocale.text("未授予麦克风权限，采集未开始。可返回音频设置重新发起。", "Microphone permission was not granted, so capture did not start. Return to audio settings to try again.")); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PROJECTION) return;
        waiting = false;
        if (result != RESULT_OK || data == null) { deny(); finish(); return; }
        try { AudioCapture.get(this).authorizeProjection(this, captureId, result, data); completed = true; finish(); }
        catch (Exception failure) { fail(failure); }
    }

    private void fail(Exception failure) {
        deny(); message.setText(failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("音频采集未能开始，请返回后重试。", "Audio capture could not start. Please go back and retry.") : failure.getMessage());
    }

    private void deny() {
        if (completed) return;
        completed = true;
        if (captureId != null) {
            try { AudioCapture.get(this).denyAuthorization(captureId); }
            catch (Exception failure) { android.widget.Toast.makeText(this, io.droyagent.app.NativeLocale.text("采集取消状态未能保存，请在音频设置中停止", "Capture cancellation could not be saved. Please stop capture in audio settings"), android.widget.Toast.LENGTH_LONG).show(); }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("waiting", waiting); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        if(visible){visible=false;visibleActivities.decrementAndGet();}
        if (isFinishing()) deny(); super.onDestroy();
    }
}

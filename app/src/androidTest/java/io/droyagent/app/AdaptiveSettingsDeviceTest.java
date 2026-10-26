package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdaptiveSettingsDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;

    @Before public void open() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks before settings validation", RuntimeService.canManageHistory());
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
    }

    @After public void close() throws Exception {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.runOnMainSync(RuntimeService::requestStop);
        DeviceUi.await("Settings validation left the engine running", 15000, RuntimeService::canManageHistory);
    }

    @Test public void optionalShizukuRowIsReadableAndItsActionResponds() throws Exception {
        ui.openPermissions();
        String selector = "[data-droy-permission=shizuku]";
        Rect target = ui.visibleWebBounds(selector);
        assertTrue("Permission button must provide a 48 dp touch target: " + target,
            target.width() > 0 && target.height() >= 48 * context.getResources().getDisplayMetrics().density - 1);
        JSONObject layout = (JSONObject) ui.evaluate("(()=>{const b=document.querySelector('[data-droy-permission=shizuku]'),row=b.closest('.droy-system-row'),r=row.getBoundingClientRect();"
            + "const text=[...row.querySelectorAll('.droy-row-title,.droy-status,button')].every(e=>{const t=document.createRange();t.selectNodeContents(e);return [...t.getClientRects()].every(x=>x.left>=r.left-1&&x.right<=r.right+1&&x.top>=r.top-1&&x.bottom<=r.bottom+1)});"
            + "return {textFits:text,horizontalOverflow:document.documentElement.scrollWidth>document.documentElement.clientWidth+1,title:row.querySelector('.droy-row-title').textContent}})()");
        assertTrue(layout.toString(), layout.getBoolean("textFits"));
        assertFalse(layout.toString(), layout.getBoolean("horizontalOverflow"));
        assertEquals("Shizuku（可选）", layout.getString("title"));
        capture("shizuku-row");
        ui.tap(selector);
        DeviceUi.await("Permission action left the settings unusable", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-permission=shizuku]')&&!document.querySelector('[data-droy-permission=shizuku]').disabled"));
        ui.evaluate("window.DroyMobile.handleBack()");
        DeviceUi.await("Back did not close permission settings", 5000,
            () -> ui.truth("!document.querySelector('[data-droy-permission=shizuku]')"));
    }

    private void capture(String name) throws Exception {
        ui.evaluate("window.__droyAdaptivePainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__droyAdaptivePainted=true))");
        DeviceUi.await("Permission row has not painted", 5000, () -> ui.truth("window.__droyAdaptivePainted"));
        java.util.concurrent.CountDownLatch painted = new java.util.concurrent.CountDownLatch(1);
        android.webkit.WebView current = ui.web();
        instrumentation.runOnMainSync(() -> current.postVisualStateCallback(0, new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        assertTrue(painted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        File folder = new File(context.getExternalFilesDir(null), "adaptive-settings-evidence");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}

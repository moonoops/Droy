package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real settings entry, text geometry, touch and saved-choice checks for memory tools. */
@RunWith(AndroidJUnit4.class)
public final class ProcessMemoryUiDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private SharedPreferences settings;
    private DeviceUi ui;
    private boolean hadWriteChoice;
    private boolean writeChoice;
    private boolean ownsRuntime;
    private boolean hadRuntimeStopChoice;
    private boolean runtimeStopChoice;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop active tasks before memory settings UI validation", RuntimeService.canManageHistory());
        ownsRuntime = true;
        settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        hadWriteChoice = settings.contains("allowMemoryWrite");
        writeChoice = ProductPermissions.allowed(settings, "allowMemoryWrite");
        hadRuntimeStopChoice = settings.contains(BackgroundRuntime.STOPPED);
        runtimeStopChoice = settings.getBoolean(BackgroundRuntime.STOPPED, false);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
    }

    @After public void tearDown() throws Exception {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        if (ownsRuntime) {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Memory settings test left the engine active", 15000, RuntimeService::canManageHistory);
        }
        if (settings != null) {
            SharedPreferences.Editor restore = settings.edit();
            if (hadWriteChoice) restore.putBoolean("allowMemoryWrite", writeChoice);
            else restore.remove("allowMemoryWrite");
            if (hadRuntimeStopChoice) restore.putBoolean(BackgroundRuntime.STOPPED, runtimeStopChoice);
            else restore.remove(BackgroundRuntime.STOPPED);
            assertTrue(restore.commit());
            RuntimeService.memoryPermissionsChanged(context);
        }
    }

    @Test public void memoryRowsAreReadableAndTrustedTouchPersistsTheWriteChoice() throws Exception {
        ui.openPermissions();
        CapabilityCatalog catalog = new CapabilityCatalog(context);
        assertEquals(catalog.groups().length(), ((Number) ui.evaluate(
            "document.querySelectorAll('[data-droy-preference]').length")).intValue());
        assertRow("allowMemoryRead", catalog.group("memoryRead").getString("label"));
        assertRow("allowMemoryWrite", catalog.group("memoryWrite").getString("label"));

        String switchSelector = "[data-droy-preference='allowMemoryWrite'] [role='switch']";
        if (!checked(switchSelector)) {
            ui.tap(switchSelector);
            awaitWrite(true, switchSelector);
        }
        capture("memory-permissions-on");
        ui.tap(switchSelector);
        awaitWrite(false, switchSelector);
        capture("memory-permissions-off");
        ui.tap(switchSelector);
        awaitWrite(true, switchSelector);

        assertRow("allowRoot", catalog.group("root").getString("label"));
        assertTrue("The Root description must include memory access", ui.truth(
            "(()=>{const e=document.querySelector('[data-droy-preference=allowRoot]');"
            + "return e.closest('.droy-row').querySelector('.droy-description').textContent.includes('进程')})()"));
        assertFalse("Old Root-only-screen copy contradicts the new capability", ui.truth(
            "document.querySelector('[data-droy-settings-options]').textContent.includes('Root 仅增强截图、坐标点击、滑动和返回')"));
        capture("root-memory-description");

        JSONArray groups = catalog.groups();
        String lastPreference = groups.getJSONObject(groups.length() - 1).getString("preference");
        assertRow(lastPreference, groups.getJSONObject(groups.length() - 1).getString("label"));
        Rect last = ui.visibleWebBounds("[data-droy-preference='" + lastPreference + "'] [role='switch']");
        assertFalse("The last capability must be reachable by scrolling", last.isEmpty());
        assertTrue("Settings must not require horizontal scrolling", ui.truth(
            "document.documentElement.scrollWidth<=document.documentElement.clientWidth+1"));
        capture("last-capability-reachable");
    }

    private void assertRow(String preference, String title) throws Exception {
        String selector = "[data-droy-preference='" + preference + "'] [role='switch']";
        ui.visibleWebBounds(selector);
        ui.evaluate("document.querySelector(" + JSONObject.quote(selector)
            + ").closest('.droy-row').scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});true");
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const control=document.querySelector("
            + JSONObject.quote(selector) + "),row=control.closest('.droy-row'),r=row.getBoundingClientRect();"
            + "const readable=[...row.querySelectorAll('.droy-row-title,.droy-description')].every(e=>{"
            + "const range=document.createRange();range.selectNodeContents(e);const s=getComputedStyle(e);"
            + "return s.textOverflow!=='ellipsis'&&[...range.getClientRects()].every(x=>x.left>=r.left-1&&x.right<=r.right+1&&x.top>=r.top-1&&x.bottom<=r.bottom+1)});"
            + "return {title:row.querySelector('.droy-row-title').textContent,textFits:readable,width:r.width,height:r.height}})()");
        assertEquals(title, geometry.getString("title"));
        assertTrue("Memory permission text is clipped: " + geometry, geometry.getBoolean("textFits"));
    }

    private boolean checked(String selector) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-checked')==='true'");
    }

    private void awaitWrite(boolean value, String selector) throws Exception {
        DeviceUi.await("A trusted memory permission touch did not persist", 5000,
            () -> settings.contains("allowMemoryWrite")
                && ProductPermissions.allowed(settings, "allowMemoryWrite") == value && checked(selector) == value);
    }

    private void capture(String name) throws Exception {
        ui.evaluate("window.__droyMemoryPainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__droyMemoryPainted=true))");
        DeviceUi.await("Memory permission rows have not painted", 5000, () -> ui.truth("window.__droyMemoryPainted"));
        CountDownLatch painted = new CountDownLatch(1);
        WebView web = ui.web();
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        assertTrue("Settings paint did not complete", painted.await(5, TimeUnit.SECONDS));
        File folder = new File(context.getExternalFilesDir(null), "process-memory-evidence");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { screenshot.recycle(); }
    }
}

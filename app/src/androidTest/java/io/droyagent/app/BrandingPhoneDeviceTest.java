package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Inspects the installed phone UI without submitting a turn or changing user settings. */
@RunWith(AndroidJUnit4.class)
public final class BrandingPhoneDeviceTest {
    private static final String LAUNCHER = "[data-droy-sidebar] [data-droy-settings-launcher]";
    private static final String[] USER_KEYS = {"assistantName", "brandSidebarLabel", "brandHeroBadge", "brandWelcomeTemplate",
        "brandComposerTemplate", "brandLogoBackgrounds", "petId", "petName", "petEnabled", "petExternalDisplay", "petInternalDisplay"};
    private Instrumentation instrumentation;
    private Context context;
    private DeviceUi ui;

    @Test public void installedPhoneShowsConfiguredBrandAndKeepsExistingSettings() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertFalse("Wait for the current task to finish before phone UI inspection", ToolProgress.current(context).getBoolean("active"));
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, Object> before = userPreferences(preferences);
        JSONObject brand = AppBranding.state(context, preferences);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        ui.hideKeyboard();
        DeviceUi.await("The phone UI did not gain focus", 5000, () -> ui.nativeNavigationState().getBoolean("activityFocus"));
        WebView originalWeb = ui.web();
        assertEquals(brand.getString("name"), ui.evaluate("window.DroyBranding.getSnapshot().name"));
        if (ui.truth("!!document.querySelector('[data-droy-brand-welcome]')")) {
            assertEquals(ui.evaluate("window.DroyBranding.getSnapshot().welcome"),
                ui.evaluate("document.querySelector('[data-droy-brand-welcome]').textContent"));
        }
        report("DROY_PHONE_COMPOSER " + ui.evaluate("(()=>{const e=document.querySelector('[data-placeholder]');return {present:!!e,visible:!!e?.getClientRects().length,editable:e?.getAttribute('contenteditable'),placeholder:e?.getAttribute('data-placeholder'),configured:window.DroyBranding.getSnapshot().composer}})()"));
        capture("conversation");
        ui.tap("[data-droy-menu]");
        DeviceUi.await("The phone drawer did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        assertEquals(AppBranding.name(context, preferences), ui.evaluate("document.querySelector('[data-droy-brand-name]').textContent"));
        assertTrue("Sidebar must contain only the logo and name", ui.truth("!document.querySelector('[data-droy-brand-label],[data-droy-brand-credit]')"));
        assertTrue("Logo and name must share their vertical center", ui.truth("(()=>{const a=document.querySelector('[data-droy-sidebar] [data-droy-brand-mark]').getBoundingClientRect(),b=document.querySelector('[data-droy-brand-name]').getBoundingClientRect();return Math.abs(a.top+a.height/2-b.top-b.height/2)<1})()"));
        DeviceUi.await("The actual phone brand image did not load", 10000,
            () -> ui.truth("(()=>{const images=[...document.querySelectorAll('[data-droy-sidebar] [data-droy-brand-mark]')].filter(e=>e.getClientRects().length);return images.length>0&&images.every(e=>e.complete&&e.naturalWidth>0)})()"));
        capture("drawer");
        openSettings();
        assertTrue("The upstream login/feedback entries remain visible", ui.truth("![...document.querySelectorAll('[role=menuitem]')].filter(e=>e.getClientRects().length).some(e=>/登录|意见反馈|Sign in|Feedback|Contact us/i.test(e.textContent))"));
        capture("settings-launcher");
        DeviceUi.await("Settings did not open on the phone", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The brand settings entry is unavailable", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-brand-settings] summary')"));
        ui.tap("[data-droy-brand-settings] summary");
        DeviceUi.await("The brand details did not expand", 5000,
            () -> ui.truth("document.querySelector('[data-droy-brand-settings] details').open"));
        assertEquals(brand.getString("welcomeTemplate"), ui.evaluate("document.querySelector('#droy-brandWelcomeTemplate').value"));
        assertEquals(brand.getString("composerTemplate"), ui.evaluate("document.querySelector('#droy-brandComposerTemplate').value"));
        assertEquals(brand.getString("heroBadge"), ui.evaluate("document.querySelector('#droy-brandHeroBadge').value"));
        ui.visibleWebBounds("[data-droy-brand-settings] .droy-brand-previews");
        DeviceUi.await("The phone logo previews did not decode", 10000,
            () -> ui.truth("[...document.querySelectorAll('[data-droy-brand-settings] .droy-brand-previews img')].length===2&&[...document.querySelectorAll('[data-droy-brand-settings] .droy-brand-previews img')].every(e=>e.complete&&e.naturalWidth>0)"));
        assertTrue("Phone dual previews must retain one artwork and geometry", ui.truth("(()=>{const [a,b]=document.querySelectorAll('[data-droy-brand-settings] .droy-brand-previews img');return a.src===b.src&&a.width===b.width&&a.height===b.height&&getComputedStyle(a).borderRadius===getComputedStyle(b).borderRadius&&getComputedStyle(a).backgroundColor!==getComputedStyle(b).backgroundColor})()"));
        capture("logo-previews");
        for (String mode : new String[]{"light", "dark"}) {
            String selector = "#droy-logo-background-" + mode;
            ui.tap(selector);
            DeviceUi.await("The phone color field did not accept touch", 5000,
                () -> ui.truth("document.activeElement===document.querySelector(" + JSONObject.quote(selector) + ")"));
            ui.hideKeyboard();
            ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").blur()");
        }
        ui.visibleWebBounds(".droy-logo-background-settings .droy-actions");
        assertTrue("Inspecting colors must not create a pending edit", ui.truth("document.querySelector('[data-droy-logo-background-save]').disabled"));
        capture("logo-controls");
        capture("brand-settings");
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings did not return to the phone conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertSame("Brand inspection replaced the conversation WebView", originalWeb, ui.web());
        assertEquals("Read-only phone inspection changed saved brand or companion settings", before, userPreferences(preferences));
    }

    private void openSettings() throws Exception {
        try {
            assertTrue("Could not focus the visible phone settings launcher", ui.truth("(()=>{const e=document.querySelector("
                + JSONObject.quote(LAUNCHER) + ");if(!e||e.closest('[inert]'))return false;e.focus({preventScroll:true});return document.activeElement===e})()"));
            JSONObject[] previous = {null};
            DeviceUi.await("The phone settings launcher is moving or obscured", 5000, () -> {
                JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(LAUNCHER)
                    + ");const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {left:r.left,top:r.top,width:r.width,height:r.height,hit:!!hit&&(e===hit||e.contains(hit))}})()");
                boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
                previous[0] = current;
                return stable && current.getBoolean("hit");
            });
            ui.tap(LAUNCHER);
            DeviceUi.await("The direct settings button did not open settings", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        } catch (AssertionError failure) {
            capture("settings-launcher-failure");
            throw new AssertionError(failure.getMessage() + "; launcher=" + ui.evaluate("(()=>{const e=document.querySelector("
                + JSONObject.quote(LAUNCHER) + ");return {expanded:e?.getAttribute('aria-expanded'),rect:e?.getBoundingClientRect().toJSON(),roles:[...document.querySelectorAll('[role=menu],[role=menuitem],[role=dialog]')].map(e=>({role:e.getAttribute('role'),visible:!!e.getClientRects().length,inert:!!e.closest('[inert]')}))}})()"), failure);
        }
    }

    private void capture(String stage) throws Exception {
        instrumentation.getUiAutomation().waitForIdle(200, 5000);
        DeviceUi.await("The phone screenshot surface is still animating", 5000,
            () -> ui.truth("document.getAnimations().every(a=>a.playState!=='running'||a.effect?.getTiming().iterations===Infinity)"));
        ui.evaluate("window.__phoneBrandPainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__phoneBrandPainted=true))");
        DeviceUi.await("Phone brand screenshot did not reach a painted frame", 5000, () -> ui.truth("window.__phoneBrandPainted"));
        android.webkit.WebView view = ui.web();
        java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> view.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long id) {
                view.postOnAnimation(() -> {
                    view.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    view.invalidate();
                });
            }
        }));
        assertTrue("Phone brand frame did not commit", committed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try {
            File folder = new File(context.getExternalFilesDir(null), "branding-ui-evidence");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            File output = File.createTempFile("phone-" + stage + "-", ".png", folder);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            report("DROY_BRAND_EVIDENCE " + output.getAbsolutePath());
        } finally { bitmap.recycle(); }
    }

    private void report(String message) { Bundle status = new Bundle(); status.putString("stream", "\n" + message + "\n"); instrumentation.sendStatus(2, status); }

    private static Map<String, Object> userPreferences(SharedPreferences preferences) {
        Map<String, ?> all = preferences.getAll();
        Map<String, Object> result = new HashMap<>();
        for (String key : USER_KEYS) if (all.containsKey(key)) result.put(key, all.get(key));
        return result;
    }
}

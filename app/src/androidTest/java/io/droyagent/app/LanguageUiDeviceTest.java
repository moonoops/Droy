package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real General settings language menu and native acknowledgement; no model request. */
@RunWith(AndroidJUnit4.class)
public final class LanguageUiDeviceTest {
    @Test public void languageMenuUpdatesNativeCopyAndPersistsAcrossReload() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue("Language fixtures require a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        assertTrue("Finish tasks and stop the engine before language tests", RuntimeService.canManageHistory());
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, Object> previous = new HashMap<>();
        String[] keys = {"languagePreference", "petEnabled", "backgroundRuntimeEnabled", "themePreference",
            "themeColorScheme", "themeBackgroundColor", "darkTheme", "resetTheme"};
        for (String key : keys) if (preferences.contains(key)) previous.put(key, preferences.getAll().get(key));
        File home = new File(context.getFilesDir(), "dsh-home");
        File backup = new File(context.getFilesDir(), "language-home-backup-" + UUID.randomUUID());
        boolean hadHome = home.exists();
        if (hadHome) assertTrue(home.renameTo(backup));
        assertTrue(home.mkdirs());
        MainActivity activity = null;
        try {
            assertTrue(preferences.edit().putString("languagePreference", "en").putBoolean("petEnabled", false)
                .putBoolean("backgroundRuntimeEnabled", false).commit());
            activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            DeviceUi ui = new DeviceUi(instrumentation, activity);
            ui.awaitPage();
            DeviceUi.await("English installation preference did not reach DSH", 10000,
                () -> ui.truth("document.documentElement.lang==='en'"));
            openGeneral(ui);
            android.os.Bundle arguments = InstrumentationRegistry.getArguments();
            String theme = arguments.getString("layoutTheme", "light");
            assertTrue("Unknown layoutTheme", theme.equals("light") || theme.equals("dark"));
            ui.tap("[data-droy-theme-choice=" + theme + "]");
            DeviceUi.await("The real theme control did not apply", 10000,
                () -> theme.equals(preferences.getString("themePreference", ""))
                    && ui.truth("document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
            if ("true".equals(arguments.getString("layoutMaxTextFont"))) {
                ui.tap("[data-droy-font-expander]");
                String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
                for (int step = 0; step < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); step++) {
                    String before = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
                    ui.tap(increase);
                    DeviceUi.await("The font touch did not apply", 5000,
                        () -> !before.equals(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()")));
                }
                assertTrue("The text font control did not reach its maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
            }
            select(ui, "中文");
            DeviceUi.await("Chinese selection was not acknowledged by native shell", 10000,
                () -> "zh".equals(preferences.getString("languagePreference", ""))
                    && ui.truth("document.documentElement.lang.startsWith('zh')"));
            assertEquals("停止任务", NativeLocale.text("停止任务", "Stop task"));
            assertTrue(ui.truth("document.querySelector('[data-droy-settings-panel]').textContent.includes('权限')"));
            capture(instrumentation, context, ui, "zh-general");
            inspectRelatedSettings(instrumentation, context, ui, "zh");
            openGeneral(ui);
            select(ui, "English");
            DeviceUi.await("English selection did not refresh native settings text", 10000,
                () -> "en".equals(preferences.getString("languagePreference", ""))
                    && ui.truth("document.documentElement.lang==='en'&&document.querySelector('[data-droy-settings-panel]').textContent.includes('Permissions')"));
            assertEquals("Stop task", NativeLocale.text("停止任务", "Stop task"));
            capture(instrumentation, context, ui, "en-general");
            inspectRelatedSettings(instrumentation, context, ui, "en");
            // A real reload reads Host persistence and rebuilds the native bootstrap.
            ui.evaluate("location.reload()");
            ui.awaitPage();
            DeviceUi.await("Reload lost the accepted language", 10000,
                () -> ui.truth("document.documentElement.lang==='en'"));
            assertEquals("en", NativeLocale.bootstrap().getString("active"));
        } finally {
            if (activity != null) {
                MainActivity finish = activity;
                instrumentation.runOnMainSync(finish::finish);
            }
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Language test left the engine running", 20000, RuntimeService::canManageHistory);
            HistoryData.deleteTree(home.toPath());
            if (hadHome) assertTrue("Keep the backup if restoration fails", backup.renameTo(home));
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : keys) {
                Object value = previous.get(key);
                if (value instanceof String text) restore.putString(key, text);
                else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                else restore.remove(key);
            }
            assertTrue(restore.commit());
        }
    }

    private static void openGeneral(DeviceUi ui) throws Exception {
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("General settings did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private static void select(DeviceUi ui, String label) throws Exception {
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-settings-panel] button[aria-haspopup=menu]')].find(e=>['中文','English'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-test-language-selector','');return true})()"));
        ui.tap("[data-test-language-selector]");
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&e.textContent.trim()==="
            + JSONObject.quote(label) + ");if(!e)return false;e.setAttribute('data-test-language-option','');return true})()"));
        ui.tap("[data-test-language-option]");
    }

    private static void inspectRelatedSettings(Instrumentation instrumentation, Context context, DeviceUi ui, String language) throws Exception {
        ui.tap("[data-droy-action=openPetStudio]");
        DeviceUi.await("The companion editor did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-pet-studio]')"));
        ui.evaluate("document.querySelector('[data-droy-pet-studio]').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-companion");
        ui.evaluate("document.querySelector('.droy-pet-form').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-companion-form");
        ui.tap("[data-droy-settings-section=droy-permissions]");
        DeviceUi.await("The permission catalog did not load", 10000,
            () -> ui.truth("document.querySelectorAll('[data-droy-preference]').length>20"));
        ui.evaluate("document.querySelector('[data-droy-permissions]').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-permissions");
        ui.evaluate("(()=>{const rows=[...document.querySelectorAll('[data-droy-preference]')];rows[rows.length-1].scrollIntoView({block:'center',behavior:'instant'})})()");
        capture(instrumentation, context, ui, language + "-permissions-last");
        ui.tap("[data-droy-settings-section=droy-memory]");
        DeviceUi.await("Memory settings did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-memory-root]')"));
        ui.evaluate("document.querySelector('[data-droy-memory]').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-memory");
        assertTrue("Only the memory settings page should be exposed", ui.truth("!document.querySelector('[data-droy-memory-tab]')&&!!document.querySelector('.droy-memory-settings')"));
        ui.evaluate("document.querySelector('.droy-memory-settings').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-memory-settings");
        ui.tap("[data-droy-settings-section=droy-voice]");
        DeviceUi.await("Voice settings did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-settings]')"));
        ui.evaluate("document.querySelector('[data-droy-voice-settings]').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-voice");
        ui.evaluate("document.querySelector('[data-droy-voice-settings]>section:last-of-type').scrollIntoView({block:'start',behavior:'instant'})");
        capture(instrumentation, context, ui, language + "-voice-last");
    }

    private static void capture(Instrumentation instrumentation, Context context, DeviceUi ui, String name) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "language-ui-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String label = InstrumentationRegistry.getArguments().getString("languageEvidenceLabel", "default");
        assertTrue("Invalid screenshot label", label.matches("[A-Za-z0-9_-]{1,64}"));
        DeviceUi.await("The settings transition did not settle", 5000, () -> ui.truth(
            "![...document.querySelectorAll('[role=menu]')].some(e=>e.getClientRects().length)"
            + "&& !document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getComputedTiming().iterations!==Infinity)"));
        java.util.concurrent.CountDownLatch drawn = new java.util.concurrent.CountDownLatch(1);
        android.webkit.WebView rendered = ui.web();
        instrumentation.runOnMainSync(() -> rendered.postVisualStateCallback(System.nanoTime(), new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) {
                // WebView's callback promises the next draw. Let that frame be
                // submitted before UiAutomation reads the window buffer.
                rendered.postInvalidateOnAnimation();
                rendered.postOnAnimation(() -> rendered.postOnAnimation(drawn::countDown));
            }
        }));
        assertTrue("WebView did not commit the translated frame", drawn.await(5, java.util.concurrent.TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory, label + "-" + name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }
}

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

/** Isolated settings validation: actual language controls, hardware rows and touchscreen input. */
@RunWith(AndroidJUnit4.class)
public class HardwareUiDeviceTest {
    @Test public void hardwareRowsAndSystemEntriesWorkInBothLanguages() throws Exception {
        assertTrue("Use the dedicated emulator for settings fixtures", Build.FINGERPRINT.contains("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue(RuntimeService.canManageHistory());
        SharedPreferences preferences = ProductPermissions.preferences(context);
        Map<String, ?> previous = new HashMap<>(preferences.getAll());
        File home = new File(context.getFilesDir(), "dsh-home");
        File backup = new File(context.getFilesDir(), "hardware-ui-backup-" + UUID.randomUUID());
        boolean hadHome = home.exists();
        if (hadHome) assertTrue(home.renameTo(backup));
        assertTrue(home.mkdirs());
        MainActivity activity = null;
        SharedPreferences installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        Boolean completed = installation.contains("completed") ? installation.getBoolean("completed", false) : null;
        try {
            assertTrue(installation.edit().putBoolean("completed", true).commit());
            assertTrue(preferences.edit().putString("languagePreference", "en").putBoolean("petEnabled", false)
                .putBoolean("backgroundRuntimeEnabled", false).commit());
            activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            DeviceUi ui = new DeviceUi(instrumentation, activity); ui.awaitPage();
            for (String language : new String[]{"zh", "en"}) {
                selectLanguage(ui, language);
                ui.openPermissions();
                for (String key : new String[]{"allowSensorsRead", "allowActivitySensors", "allowBodySensors", "allowLocationRead",
                        "allowInfrared", "allowBluetoothRead", "allowBluetoothControl", "allowNfcRead", "allowNfcWrite", "allowNfcEmulation",
                        "allowWifiRead", "allowWifiControl", "allowCellularRead", "allowNetworkProbe", "allowDeviceDataRead", "allowDeviceDataWrite"}) {
                    String selector = "[data-droy-preference='" + key + "']";
                    assertTrue(ui.truth("!!document.querySelector(" + JSONObject.quote(selector) + ")"));
                    ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").scrollIntoView({block:'center',behavior:'instant'})");
                    JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(selector)
                        + ").closest('.droy-row'),r=row.getBoundingClientRect();return {fits:[...row.querySelectorAll('.droy-row-title,.droy-description')].every(e=>{"
                        + "const range=document.createRange();range.selectNodeContents(e);return getComputedStyle(e).textOverflow!=='ellipsis'&&[...range.getClientRects()].every(x=>x.left>=r.left-1&&x.right<=r.right+1&&x.top>=r.top-1&&x.bottom<=r.bottom+1)})}})()");
                    assertTrue("Hardware row must display its complete text: " + key, geometry.getBoolean("fits"));
                    if (java.util.Set.of("allowSensorsRead", "allowNfcWrite", "allowWifiRead", "allowDeviceDataWrite").contains(key))
                        capture(instrumentation, context, ui, language + "-" + key, selector);
                }
                String selector = "[data-droy-preference=allowSensorsRead] [role=switch]";
                boolean before = ProductPermissions.allowed(preferences, "allowSensorsRead");
                ui.tap(selector);
                DeviceUi.await("Hardware permission touch was not persisted", 5000,
                    () -> ProductPermissions.allowed(preferences, "allowSensorsRead") != before);
                ui.tap(selector);
                DeviceUi.await("Hardware permission restoration touch was not persisted", 5000,
                    () -> ProductPermissions.allowed(preferences, "allowSensorsRead") == before);
                for (String permission : new String[]{"location", "bluetooth", "wifiScan", "phoneState", "activityRecognition", "bodySensors", "nfcSettings"})
                    assertTrue("System permission entry is missing: " + permission, ui.truth("!!document.querySelector('[data-droy-permission-status=" + permission + "]')"));
                ui.evaluate("document.querySelector('[data-droy-permission-status=bodySensors]').closest('.droy-row').scrollIntoView({block:'center',behavior:'instant'})");
                capture(instrumentation, context, ui, language + "-system-permissions", "[data-droy-permission-status=bodySensors]");
            }
        } finally {
            if (activity != null) { MainActivity finish = activity; instrumentation.runOnMainSync(finish::finish); }
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Hardware UI test left the engine running", 20000, RuntimeService::canManageHistory);
            HistoryData.deleteTree(home.toPath());
            if (hadHome) assertTrue("Keep the original home backup if restoration fails", backup.renameTo(home));
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : previous.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean flag) restore.putBoolean(entry.getKey(), flag);
                else if (value instanceof String text) restore.putString(entry.getKey(), text);
                else if (value instanceof Integer number) restore.putInt(entry.getKey(), number);
                else if (value instanceof Long number) restore.putLong(entry.getKey(), number);
                else if (value instanceof Float number) restore.putFloat(entry.getKey(), number);
                else throw new AssertionError("Unexpected product preference type");
            }
            assertTrue(restore.commit());
            SharedPreferences.Editor setupRestore = installation.edit();
            if (completed == null) setupRestore.remove("completed"); else setupRestore.putBoolean("completed", completed);
            assertTrue(setupRestore.commit());
        }
    }
    private static void selectLanguage(DeviceUi ui, String language) throws Exception {
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("General settings did not open", 10000, () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-settings-panel] button[aria-haspopup=menu]')].find(e=>['中文','English'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-hardware-language-selector','');return true})()"));
        ui.tap("[data-hardware-language-selector]");
        String label = language.equals("zh") ? "中文" : "English";
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&e.textContent.trim()==="
            + JSONObject.quote(label) + ");if(!e)return false;e.setAttribute('data-hardware-language-option','');return true})()"));
        ui.tap("[data-hardware-language-option]");
        DeviceUi.await("Language selection was not acknowledged", 10000, () -> ui.truth(language.equals("zh")
            ? "document.documentElement.lang.startsWith('zh')" : "document.documentElement.lang==='en'"));
    }
    private static void capture(Instrumentation instrumentation, Context context, DeviceUi ui, String name, String selector) throws Exception {
        ui.visibleWebBounds(selector);
        instrumentation.waitForIdleSync();
        ui.awaitPaint();
        // WebView commit precedes the emulator compositor's screenshot buffer.
        android.os.SystemClock.sleep(350);
        File directory = new File(context.getExternalFilesDir(null), "hardware-ui-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(screenshot);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { screenshot.recycle(); }
    }
}

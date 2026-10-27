package io.droyagent.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.DeviceDataStore;
import io.droyagent.androiduse.HardwareSupport;
import io.droyagent.androiduse.NfcCredentials;
import io.droyagent.androiduse.WifiCredentialStore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native UI touch/layout checks with synthetic local inputs and no model or network. */
@RunWith(AndroidJUnit4.class)
public class HardwareNativeUiDeviceTest {
    private static final String[] PREFERENCES = {"languagePreference", "themeColorScheme", "toolsStopped",
        "allowNfcRead", "allowNfcWrite", "allowWifiRead", "allowWifiControl", "allowDeviceDataRead", "allowDeviceDataWrite"};
    private final Map<String, Object> before = new HashMap<>();
    private final List<String> nfcNames = new ArrayList<>(), wifiNames = new ArrayList<>(), shortcutIds = new ArrayList<>(), dataRefs = new ArrayList<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private Activity activity;
    private DeviceUi touch;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertFalse("Run this native UI batch after stopping the engine", RuntimeService.ready);
        preferences = ProductPermissions.preferences(context);
        for (String key : PREFERENCES) before.put(key, preferences.getAll().get(key));
        assertTrue(preferences.edit().putBoolean("toolsStopped", false).putBoolean("allowNfcRead", true)
            .putBoolean("allowNfcWrite", true).putBoolean("allowWifiRead", true).putBoolean("allowWifiControl", true)
            .putBoolean("allowDeviceDataRead", true).putBoolean("allowDeviceDataWrite", true)
            .putString("themeColorScheme", "light").commit());
        touch = new DeviceUi(instrumentation, null);
    }

    @After public void tearDown() throws Exception {
        finish();
        NfcCredentials nfc = new NfcCredentials(context);
        for (String name : nfcNames) { String ref = reference(nfc.list(), name); if (ref != null) nfc.delete(ref); }
        WifiCredentialStore wifi = new WifiCredentialStore(context);
        for (String name : wifiNames) { String ref = reference(wifi.list(), name); if (ref != null) wifi.delete(ref); }
        SharedPreferences.Editor shortcuts = context.getSharedPreferences("device-shortcuts", Context.MODE_PRIVATE).edit();
        for (String id : shortcutIds) shortcuts.remove(id);
        assertTrue(shortcuts.commit());
        DeviceDataStore data = new DeviceDataStore(context);
        for (String ref : dataRefs) data.execute(new JSONObject().put("operation", "delete").put("dataRef", ref), "native-ui-fixture", () -> {});
        SharedPreferences.Editor restore = preferences.edit();
        for (String key : PREFERENCES) {
            Object value = before.get(key);
            if (value == null) restore.remove(key);
            else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
            else restore.putString(key, (String) value);
        }
        assertTrue(restore.commit());
    }

    @Test public void nfcCredentialInputSaveAndBackWorkInBothLanguagesWithSecureWindow() throws Exception {
        for (String language : new String[]{"zh", "en"}) {
            language(language); launch(NfcCredentialActivity.class); assertSecure(); assertTextGeometry();
            List<EditText> fields = fields(); assertEquals(2, fields.size());
            String name = "nfc-ui-" + UUID.randomUUID().toString().substring(0, 8); nfcNames.add(name);
            input(fields.get(0), name); input(fields.get(1), "010203040506"); hideKeyboard();
            assertSecure(); assertTextGeometry();
            Button save = button(HardwareSupport.text(context, io.droyagent.androiduse.R.string.nfc_credential_save));
            tap(save);
            DeviceUi.await("NFC credential save touch did not persist its own fixture", 5000,
                () -> reference(new NfcCredentials(context).list(), name) != null);
            onMain(() -> { assertFalse(save.isEnabled()); assertEquals(0, fields.get(1).length()); });
            assertTextPresent(HardwareSupport.text(context, io.droyagent.androiduse.R.string.nfc_credential_saved));
            assertTextGeometry(); assertSecure();
            String ref = reference(new NfcCredentials(context).list(), name); new NfcCredentials(context).delete(ref);
            tap(button(HardwareSupport.text(context, io.droyagent.androiduse.R.string.nfc_back))); awaitFinished();
        }
    }

    @Test public void wifiCredentialInputSaveAndBackWorkInBothLanguagesWithSecureWindow() throws Exception {
        for (String language : new String[]{"zh", "en"}) {
            language(language); launch(WifiCredentialActivity.class); assertSecure(); assertTextGeometry();
            List<EditText> fields = fields(); assertEquals(2, fields.size());
            String name = "wifi-ui-" + UUID.randomUUID().toString().substring(0, 8); wifiNames.add(name);
            input(fields.get(0), name); input(fields.get(1), "UiFixture2468"); hideKeyboard();
            assertSecure(); assertTextGeometry();
            Button save = button(HardwareSupport.text(context, io.droyagent.androiduse.R.string.wifi_credential_save));
            tap(save);
            DeviceUi.await("Wi-Fi credential save touch did not persist its own fixture", 5000,
                () -> reference(new WifiCredentialStore(context).list(), name) != null);
            onMain(() -> { assertFalse(save.isEnabled()); assertEquals(0, fields.get(1).length()); });
            assertTextPresent(HardwareSupport.text(context, io.droyagent.androiduse.R.string.wifi_credential_saved));
            assertTextGeometry(); assertSecure();
            String ref = reference(new WifiCredentialStore(context).list(), name); new WifiCredentialStore(context).delete(ref);
            tap(button(HardwareSupport.text(context, io.droyagent.androiduse.R.string.wifi_credential_back))); awaitFinished();
        }
    }

    @Test public void healthRationaleHasFullTextAndTouchableBackInBothLanguages() throws Exception {
        for (String language : new String[]{"zh", "en"}) {
            language(language); launch(HealthPermissionRationaleActivity.class);
            assertTextPresent(NativeLocale.text("身体传感器数据用途", "Body sensor data use"));
            assertTextGeometry(); capture("health-" + language + "-top.png");
            Button back = button(NativeLocale.text("返回", "Back")); reveal(back);
            assertTextGeometry(); capture("health-" + language + "-bottom.png"); tap(back); awaitFinished();
        }
    }

    @Test public void savedDataShortcutShowsOwnFixtureAndReturnsWithoutPinning() throws Exception {
        for (String language : new String[]{"zh", "en"}) {
            language(language);
            JSONObject saved = new DeviceDataStore(context).put("native-ui-fixture", new byte[]{1, 2, 3, 4},
                new JSONObject().put("source", "synthetic_ui_fixture"), true, "UI fixture");
            String ref = saved.getString("dataRef"); dataRefs.add(ref);
            String id = UUID.randomUUID().toString(); shortcutIds.add(id);
            String label = "UI " + language;
            JSONObject record = new JSONObject().put("target", "device_data").put("label", label)
                .put("command", new JSONObject().put("operation", "read").put("dataRef", ref));
            assertTrue(context.getSharedPreferences("device-shortcuts", Context.MODE_PRIVATE).edit().putString(id, record.toString()).commit());
            launch(HardwareShortcutActivity.class, new Intent(context, HardwareShortcutActivity.class).putExtra("shortcutId", id));
            DeviceUi.await("The saved-data entry did not show its synthetic payload", 5000, () -> hasText("01020304"));
            assertTextPresent(label); assertTextGeometry(); capture("data-shortcut-" + language + ".png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); awaitFinished();
        }
    }

    private void language(String language) { assertTrue(preferences.edit().putString("languagePreference", language).commit()); assertEquals(language, NativeLocale.active()); }
    private void launch(Class<? extends Activity> type) throws Exception { launch(type, new Intent(context, type)); }
    private void launch(Class<? extends Activity> type, Intent intent) throws Exception {
        finish(); activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertEquals(type, activity.getClass()); instrumentation.waitForIdleSync();
        DeviceUi.await("The native page did not receive layout and window focus", 5000, () -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            onMain(() -> ready.set(activity.hasWindowFocus() && activity.getWindow().getDecorView().getWidth() > 0)); return ready.get();
        });
    }
    private void finish() {
        Activity shown = activity;
        if (shown != null) { onMain(shown::finish); instrumentation.waitForIdleSync(); activity = null; }
    }
    private void awaitFinished() throws Exception {
        Activity shown = activity;
        DeviceUi.await("The back touch did not close the native page", 5000, () -> shown.isFinishing() || shown.isDestroyed());
        instrumentation.waitForIdleSync(); activity = null;
    }
    private List<EditText> fields() {
        List<EditText> result = new ArrayList<>(); onMain(() -> { for (View view : views()) if (view instanceof EditText) result.add((EditText) view); }); return result;
    }
    private Button button(String label) {
        AtomicReference<Button> result = new AtomicReference<>(); onMain(() -> {
            for (View view : views()) if (view instanceof Button && ((Button) view).getText().toString().equals(label)) result.set((Button) view);
        }); assertNotNull("Native action button is missing", result.get()); return result.get();
    }
    private List<View> views() { List<View> result = new ArrayList<>(); collect(activity.getWindow().getDecorView(), result); return result; }
    private void collect(View view, List<View> output) { output.add(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), output); }
    private void input(EditText field, String value) throws Exception {
        tap(field);
        DeviceUi.await("Touch did not focus the native input", 5000, () -> {
            AtomicReference<Boolean> focused = new AtomicReference<>(false); onMain(() -> focused.set(field.hasFocus())); return focused.get();
        });
        instrumentation.sendStringSync(value); instrumentation.waitForIdleSync();
        onMain(() -> assertTrue("Synthetic input did not reach the focused field", value.contentEquals(field.getText())));
    }
    private void reveal(View view) throws Exception {
        onMain(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        instrumentation.waitForIdleSync();
        DeviceUi.await("Native target is clipped or outside the visible viewport", 5000, () -> {
            AtomicReference<Boolean> visible = new AtomicReference<>(false); onMain(() -> {
                Rect rect = new Rect(); visible.set(view.isShown() && view.getGlobalVisibleRect(rect) && rect.width() >= view.getWidth() - 1 && rect.height() >= view.getHeight() - 1);
            }); return visible.get();
        });
    }
    private void tap(View view) throws Exception {
        reveal(view); Rect rect = new Rect(); onMain(() -> assertTrue(view.getGlobalVisibleRect(rect)));
        touch.tapAt(rect.centerX(), rect.centerY()); instrumentation.waitForIdleSync();
    }
    private void hideKeyboard() throws Exception {
        onMain(() -> { InputMethodManager manager = context.getSystemService(InputMethodManager.class); if (manager != null) manager.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0); });
        instrumentation.waitForIdleSync();
        DeviceUi.await("The input keyboard did not leave the native page", 5000, () -> {
            AtomicReference<Boolean> hidden = new AtomicReference<>(false); onMain(() -> {
                android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                hidden.set(insets != null && !insets.isVisible(android.view.WindowInsets.Type.ime()));
            }); return hidden.get();
        });
    }
    private void assertSecure() { onMain(() -> assertNotEquals("Credential page must retain FLAG_SECURE", 0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)); }
    private void assertTextPresent(String expected) throws Exception { DeviceUi.await("Expected native text is missing", 5000, () -> hasText(expected)); }
    private boolean hasText(String expected) {
        AtomicReference<Boolean> found = new AtomicReference<>(false); onMain(() -> { for (View view : views()) if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) found.set(true); }); return found.get();
    }
    private void assertTextGeometry() {
        onMain(() -> {
            for (View view : views()) {
                if (!(view instanceof TextView) || !view.isShown()) continue;
                TextView text = (TextView) view; if (text.getText().length() == 0) continue;
                Layout layout = text.getLayout(); assertNotNull("Native text has no layout", layout);
                assertTrue("Native text has zero width", text.getWidth() > text.getCompoundPaddingLeft() + text.getCompoundPaddingRight());
                assertTrue("Native text is vertically clipped", layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1);
                for (int line = 0; line < layout.getLineCount(); line++) {
                    assertEquals("Native text is ellipsized", 0, layout.getEllipsisCount(line));
                    if (!(text instanceof EditText)) assertTrue("Native text exceeds its available width", layout.getLineWidth(line) <= layout.getWidth() + 2);
                }
                if (!(text instanceof EditText)) assertEquals("Native text is cut off before its end", text.length(), layout.getLineEnd(layout.getLineCount() - 1));
            }
        });
    }
    private void capture(String name) throws Exception {
        onMain(() -> assertEquals("Secure pages must never be captured", 0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
        instrumentation.waitForIdleSync();
        CountDownLatch committed = new CountDownLatch(1);
        onMain(() -> {
            View decor = activity.getWindow().getDecorView();
            decor.postOnAnimation(() -> { decor.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); decor.invalidate(); });
        });
        assertTrue("The native evidence frame did not commit", committed.await(5, TimeUnit.SECONDS));
        Bitmap screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull("Android did not provide a public native-page screenshot", screenshot);
        File directory = new File(context.getExternalFilesDir(null), "hardware-native-ui-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { screenshot.recycle(); }
    }
    private static String reference(JSONArray rows, String name) throws Exception {
        String result = null;
        for (int i = 0; i < rows.length(); i++) if (name.equals(rows.getJSONObject(i).optString("name"))) { assertNull("Synthetic credential name must be unique", result); result = rows.getJSONObject(i).getString("credentialRef"); }
        return result;
    }
    private void onMain(Runnable callback) { instrumentation.runOnMainSync(callback); }
}

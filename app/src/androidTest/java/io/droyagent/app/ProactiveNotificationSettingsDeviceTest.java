package io.droyagent.app;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real settings touches and the actual profile plugin lifecycle; no messages or model requests. */
@RunWith(AndroidJUnit4.class)
public final class ProactiveNotificationSettingsDeviceTest {
    private static final String ROOT = "[data-droy-general=proactiveNotifications]";
    private static final String PLUGIN = ROOT + " > .droy-notification-header [role=switch]";
    private static final String DETAILS = "[data-droy-proactive-details]";
    private static final String LOCAL = DETAILS + " .droy-notification-body > .droy-notification-header:nth-child(1) [role=switch]";
    private static final String APPS = DETAILS + " [role=switch][aria-label='通过其他 App 主动通知']";
    private static final String SEARCH = ROOT + " input[aria-label='搜索通知应用']";
    private static final String RETURN = "[data-droy-general=returnToDroyOnCompletion] [role=switch]";
    private static final String TARGET = "Droy UI test recipient";
    private static final String[] KEYS = {"notifyLocalEnabled", "notifyAppsEnabled", "notifyAllowedApps", "returnToDroyOnCompletion"};
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private NotificationTestState savedRuntime;
    private Boolean savedPlugin;
    private boolean preferencesOwned;
    private Rect stableImeBounds;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks and the engine before notification settings tests", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> all = preferences.getAll();
        for (String key : KEYS) if (all.containsKey(key)) savedPreferences.put(key, all.get(key));
        preferencesOwned = true;
        savedRuntime = new NotificationTestState(context);
        savedRuntime.prepare();
        assertTrue(preferences.edit().putBoolean("notifyLocalEnabled", true).putBoolean("notifyAppsEnabled", false)
                .putString("notifyAllowedApps", "[]").remove("returnToDroyOnCompletion").commit());
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        if ("true".equals(InstrumentationRegistry.getArguments().getString("layoutLandscape"))) {
            DeviceUi.await("The requested landscape condition did not reach the real WebView", 10000,
                    () -> ui.truth("innerWidth>innerHeight"));
        }
        if (ui.nativeNavigationState().getBoolean("webFailure")) {
            capture("native-web-failure");
            fail("The settings UI requires a supported system WebView; the native failure page is visible");
        }
        savedPlugin = pluginRequest(null).getBoolean("enabled");
        pluginRequest(true);
        try {
            DeviceUi.await("The real notification plugin did not activate", 10000,
                    () -> ProactiveNotifications.get(context).status().getBoolean("pluginActive"));
        } catch (AssertionError failure) {
            capture("plugin-activation-failure");
            throw new AssertionError(failure.getMessage() + "; plugin=" + pluginRequest(null)
                    + "; nativeActive=" + ProactiveNotifications.get(context).status().optBoolean("pluginActive")
                    + "; navigation=" + safeNavigationState(), failure);
        }
        openGeneral();
        Bundle args = InstrumentationRegistry.getArguments();
        if ("dark".equals(args.getString("layoutTheme")) || "true".equals(args.getString("layoutMaxTextFont"))) {
            assertTrue("Font/theme variants require the isolated emulator home", NotificationTestState.emulator());
            DeviceUi.await("Theme and text controls did not load", 10000,
                    () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')&&document.querySelectorAll('[data-droy-theme-choice]').length===3"));
            if ("true".equals(args.getString("layoutMaxTextFont"))) maximizeDshTextFont();
            if ("dark".equals(args.getString("layoutTheme"))) {
                ui.tap("[data-droy-theme-choice=dark]");
                DeviceUi.await("Dark theme did not apply", 10000,
                        () -> ui.truth("document.body.hasAttribute('data-ds-dark-theme')"));
            }
        }
    }

    @After public void tearDown() throws Exception {
        try {
            if (savedPlugin != null && RuntimeService.ready) pluginRequest(savedPlugin);
        } finally {
            try {
                if (activity != null) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Settings test left the engine active", 15000, RuntimeService::canManageHistory);
            } finally {
                if (preferencesOwned) {
                    SharedPreferences.Editor editor = preferences.edit();
                    for (String key : KEYS) {
                        Object value = savedPreferences.get(key);
                        if (value instanceof Boolean bool) editor.putBoolean(key, bool);
                        else if (value instanceof String text) editor.putString(key, text);
                        else editor.remove(key);
                    }
                    assertTrue("Could not restore proactive notification preferences", editor.commit());
                }
                if (savedRuntime != null) savedRuntime.restore();
            }
        }
    }

    @Test public void channelsAndTargetsPersistWhilePluginCanBeDisabledAndReenabled() throws Exception {
        assertFalse("An absent completion preference must default off", checked(RETURN));
        setPreference(RETURN, "returnToDroyOnCompletion", true);
        closeAndReopenGeneral();
        assertTrue("The explicitly enabled completion preference was lost", checked(RETURN));
        setPreference(RETURN, "returnToDroyOnCompletion", false);

        assertTrue(checked(PLUGIN));
        openDetails();
        assertTrue(checked(LOCAL));
        assertFalse("App notifications should start disabled", checked(APPS));
        ui.visibleWebBounds(LOCAL); assertLayout(); capture("channels-default");
        setChannel(LOCAL, "localEnabled", false);
        setChannel(LOCAL, "localEnabled", true);
        setChannel(APPS, "appsEnabled", true);

        String packageName = settingsFixturePackage();
        String row = "[data-droy-notify-app='" + packageName + "']";
        ui.showKeyboard(SEARCH);
        awaitKeyboardStable();
        instrumentation.sendStringSync(packageName);
        try {
            DeviceUi.await("Searching did not show the installed fixture app", 5000,
                    () -> ui.truth("document.querySelector(" + JSONObject.quote(SEARCH) + ").value.toLocaleLowerCase()===" + JSONObject.quote(packageName.toLowerCase(java.util.Locale.ROOT))
                        + "&&!!document.querySelector(" + JSONObject.quote(row) + ")"));
        } catch (AssertionError failure) {
            capture("app-search-failure");
            throw new AssertionError(failure.getMessage() + "; state=" + ui.evaluate("({value:document.querySelector("
                    + JSONObject.quote(SEARCH) + ")?.value,focused:document.activeElement?.getAttribute('aria-label'),"
                    + "row:!!document.querySelector(" + JSONObject.quote(row) + ")})"), failure);
        }
        ui.visibleWebBounds(SEARCH); assertKeyboardInputVisible(SEARCH); assertLayout(); capture("app-search-keyboard");
        ui.hideKeyboard(); awaitKeyboardClosed();
        ui.tap(row + " [role=switch]");
        try {
            DeviceUi.await("The chosen notification app was not saved", 10000,
                    () -> allowedApp(packageName) != null && checked(row + " [role=switch]"));
        } catch (AssertionError failure) {
            capture("app-toggle-failure");
            throw new AssertionError(failure.getMessage() + "; allowed=" + allowedApp(packageName)
                    + "; ui=" + ui.evaluate("({checked:document.querySelector(" + JSONObject.quote(row + " [role=switch]")
                        + ")?.getAttribute('aria-checked'),errors:[...document.querySelectorAll(" + JSONObject.quote(ROOT + " [role=alert]")
                        + ")].map(e=>e.textContent)})"), failure);
        }
        String input = row + " input";
        ui.showKeyboard(input);
        awaitKeyboardStable();
        instrumentation.sendStringSync(TARGET);
        DeviceUi.await("The target draft did not reach the real input", 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(input) + ").value===" + JSONObject.quote(TARGET)));
        ui.visibleWebBounds(input); assertKeyboardInputVisible(input); assertLayout(); capture("target-keyboard");
        ui.hideKeyboard(); awaitKeyboardClosed();
        String save = "[data-droy-save-notify-target='" + packageName + "']";
        ui.tap(save);
        DeviceUi.await("The default recipient was not saved", 10000,
                () -> allowedApp(packageName) != null && TARGET.equals(allowedApp(packageName).optString("target"))
                    && ui.truth("document.querySelector(" + JSONObject.quote(save) + ").disabled"));
        ui.visibleWebBounds(input); assertLayout(); capture("target-saved");

        closeAndReopenGeneral();
        openDetails();
        assertTrue(checked(LOCAL)); assertTrue(checked(APPS));
        assertTrue("The selected app was not retained in the initial list", ui.truth("!!document.querySelector(" + JSONObject.quote(row) + ")"));
        assertEquals(TARGET, ui.evaluate("document.querySelector(" + JSONObject.quote(input) + ").value"));
        ui.tap(DETAILS + " > summary");
        DeviceUi.await("Notification details did not collapse", 5000,
                () -> ui.truth("!document.querySelector(" + JSONObject.quote(DETAILS) + ").open"));
        ui.tap(PLUGIN);
        DeviceUi.await("The UI did not disable the real notification plugin", 15000,
                () -> !checked(PLUGIN) && !pluginRequest(null).getBoolean("enabled")
                    && !ProactiveNotifications.get(context).status().getBoolean("pluginActive"));
        assertTrue("Disabled plugin lost the host reenable entry", ui.truth("!!document.querySelector(" + JSONObject.quote(PLUGIN) + ")"));
        assertFalse("Disabled notification plugin kept editable channel details", ui.truth("!!document.querySelector(" + JSONObject.quote(DETAILS) + ")"));
        assertTrue("Disabling proactive notifications removed incoming notification settings",
                ui.truth("!!document.querySelector('[data-droy-general=notificationManagementEnabled]')"));
        ui.visibleWebBounds(PLUGIN); assertLayout(); capture("plugin-disabled");
        ui.tap(PLUGIN);
        DeviceUi.await("The original settings entry could not reactivate the notification plugin", 15000,
                () -> checked(PLUGIN) && pluginRequest(null).getBoolean("enabled")
                    && ProactiveNotifications.get(context).status().getBoolean("pluginActive"));
        openDetails();
        assertTrue(checked(LOCAL)); assertTrue(checked(APPS));
        assertEquals(TARGET, allowedApp(packageName).getString("target"));
        ui.visibleWebBounds(PLUGIN); assertLayout(); capture("plugin-reenabled");
    }

    private void openGeneral() throws Exception {
        dismissAccountWelcome();
        ui.hideKeyboard(); awaitKeyboardClosed();
        DeviceUi.await("The settings Activity did not gain focus", 5000,
                () -> ui.nativeNavigationState().getBoolean("activityFocus"));
        instrumentation.getUiAutomation().waitForIdle(300, 5000);
        DeviceUi.await("The visible menu is covered by a dialog or transition", 5000,
                () -> ui.truth("(()=>{const e=document.querySelector('[data-droy-menu]');if(!e)return false;const r=e.getBoundingClientRect(),"
                    + "hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!hit&&(hit===e||e.contains(hit))})()"));
        Object beforeTouch = safeNavigationState();
        ui.tap("[data-droy-menu]");
        try {
            DeviceUi.await("The settings drawer did not open", 5000,
                    () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        } catch (AssertionError failure) {
            capture("drawer-open-failure");
            throw new AssertionError(failure.getMessage() + "; before=" + beforeTouch + "; safe state=" + safeNavigationState()
                    + "; nativeBounds=" + ui.webBounds("[data-droy-menu]") + "; native=" + ui.nativeNavigationState(), failure);
        }
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("Settings did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The notification plugin switch did not become actionable", 15000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(PLUGIN) + ")&&!document.querySelector(" + JSONObject.quote(PLUGIN) + ").disabled"));
    }

    private void closeAndReopenGeneral() throws Exception {
        ui.hideKeyboard(); awaitKeyboardClosed(); ui.pressBack();
        DeviceUi.await("Settings Back did not return to the conversation", 5000,
                () -> ui.truth("!document.querySelector('[data-droy-settings-panel]')"));
        openGeneral();
    }

    private void openDetails() throws Exception {
        if (!ui.truth("document.querySelector(" + JSONObject.quote(DETAILS) + ")?.open===true")) ui.tap(DETAILS + " > summary");
        DeviceUi.await("Notification channels did not expand", 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(DETAILS) + ")?.open===true"));
    }

    private boolean checked(String selector) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-checked')==='true'");
    }

    private void dismissAccountWelcome() throws Exception {
        long[] quietSince = {0};
        DeviceUi.await("The fresh-home account welcome did not settle", 10000, () -> {
            boolean actionable = ui.truth("(()=>{document.querySelectorAll('[data-droy-notify-welcome-close]').forEach(e=>e.removeAttribute('data-droy-notify-welcome-close'));"
                    + "for(const heading of document.querySelectorAll('[role=dialog] h2')){if(!['开始使用','Get started'].includes(heading.textContent.trim()))continue;"
                    + "const dialog=heading.closest('[role=dialog]');if(!dialog.getClientRects().length)continue;"
                    + "const close=dialog.querySelector('button[aria-label=关闭],button[aria-label=Close]');if(close&&!close.disabled){close.setAttribute('data-droy-notify-welcome-close','');return true}}return false})()");
            if (actionable) {
                ui.tap("[data-droy-notify-welcome-close]"); quietSince[0] = 0; return false;
            }
            if (quietSince[0] == 0) quietSince[0] = android.os.SystemClock.elapsedRealtime();
            return android.os.SystemClock.elapsedRealtime() - quietSince[0] >= 600;
        });
    }

    private void awaitKeyboardClosed() throws Exception {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int original = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
            DeviceUi.await("The dismissing input window still covers settings controls", 5000, () -> {
                if (ui.keyboardVisible()) return false;
                for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows())
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false;
                return true;
            });
        } finally {
            info.flags = original;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }

    private void awaitKeyboardStable() throws Exception {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int original = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        Rect[] previous = {null}; long[] unchangedSince = {0};
        try {
            DeviceUi.await("The visible input window did not settle", 5000, () -> {
                if (!ui.keyboardVisible()) return false;
                Rect current = null;
                for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    current = new Rect(); window.getBoundsInScreen(current); break;
                }
                if (current == null || current.isEmpty()) return false;
                long now = android.os.SystemClock.elapsedRealtime();
                if (!current.equals(previous[0])) { previous[0] = current; unchangedSince[0] = now; return false; }
                if (now - unchangedSince[0] < 250) return false;
                stableImeBounds = current;
                return true;
            });
        } finally {
            info.flags = original;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }

    private void assertKeyboardInputVisible(String selector) throws Exception {
        Rect field = ui.webBounds(selector);
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),r=e.getBoundingClientRect(),pane=e.closest('[data-droy-settings-options]').getBoundingClientRect(),"
                + "hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2),box=n=>{if(!n)return null;const b=n.getBoundingClientRect();return {top:b.top,bottom:b.bottom,left:b.left,right:b.right,width:b.width,height:b.height,scrollTop:n.scrollTop,clientHeight:n.clientHeight,scrollHeight:n.scrollHeight}};"
                + "return {top:r.top,bottom:r.bottom,paneTop:pane.top,paneBottom:pane.bottom,options:box(e.closest('[data-droy-settings-options]')),"
                + "panel:box(e.closest('[data-droy-settings-panel]')),header:box(document.querySelector('[data-droy-settings-back]')?.parentElement),"
                + "tabs:box(document.querySelector('[data-droy-settings-section=general]')?.parentElement),"
                + "hit:!!hit&&(hit===e||e.contains(hit)),focused:document.activeElement===e}})()");
        boolean visible = stableImeBounds != null && field.bottom <= stableImeBounds.top + 1
                && geometry.getDouble("top") >= geometry.getDouble("paneTop") - 1
                && geometry.getDouble("bottom") <= geometry.getDouble("paneBottom") + 1
                && geometry.getBoolean("hit") && geometry.getBoolean("focused");
        if (!visible) capture("keyboard-visibility-failure");
        assertTrue("The focused input is obscured by the settled keyboard or settings header: field=" + field
                + "; ime=" + stableImeBounds + "; css=" + geometry, visible);
    }

    private void setChannel(String selector, String field, boolean value) throws Exception {
        assertNotEquals("The test requires an actual channel switch change", value, checked(selector));
        ui.tap(selector);
        DeviceUi.await("The channel change was not saved: " + field, 10000,
                () -> ProactiveNotifications.get(context).status().getBoolean(field) == value && checked(selector) == value
                    && ui.truth("!document.querySelector(" + JSONObject.quote(selector) + ").disabled"));
    }

    private void setPreference(String selector, String key, boolean value) throws Exception {
        assertNotEquals("The test requires an actual preference switch change", value, checked(selector));
        ui.tap(selector);
        DeviceUi.await("The preference change was not saved: " + key, 10000,
                () -> preferences.getBoolean(key, !value) == value && checked(selector) == value
                    && ui.truth("!document.querySelector(" + JSONObject.quote(selector) + ").disabled"));
    }

    private String settingsFixturePackage() throws Exception {
        JSONArray apps = ProactiveNotifications.get(context).status().getJSONArray("apps");
        for (int i = 0; i < apps.length(); i++)
            if ("com.android.settings".equals(apps.getJSONObject(i).getString("packageName"))) return "com.android.settings";
        throw new AssertionError("A launchable system Settings app is required as a configuration-only fixture");
    }

    private JSONObject allowedApp(String packageName) throws Exception {
        JSONArray allowed = ProactiveNotifications.get(context).status().getJSONArray("allowedApps");
        for (int i = 0; i < allowed.length(); i++) if (packageName.equals(allowed.getJSONObject(i).getString("packageName")))
            return allowed.getJSONObject(i);
        return null;
    }

    private JSONObject pluginRequest(Boolean enabled) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + RuntimeService.webPort
                + "/droy/notify/plugin").openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cookie", "droy_session=" + RuntimeService.webToken);
        try {
            if (enabled != null) {
                byte[] payload = new JSONObject().put("enabled", enabled).toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(payload); }
            }
            assertEquals("Notification plugin control request failed", 200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private void maximizeDshTextFont() throws Exception {
        if (!ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'")) {
            ui.tap("[data-droy-font-expander]");
            DeviceUi.await("The font controls did not expand", 5000,
                    () -> ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'"));
        }
        String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
        for (int i = 0; i < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); i++) {
            String before = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
            ui.tap(increase);
            DeviceUi.await("The text font setting did not change", 5000,
                    () -> !before.equals(String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"))));
        }
        assertTrue("The font control did not reach its supported maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
    }

    private void assertLayout() throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const root=document.querySelector(" + JSONObject.quote(ROOT)
                + "),failures=[];if(!root)return {failures:['missing-root']};const rr=root.getBoundingClientRect();"
                + "if(root.scrollWidth>root.clientWidth+1)failures.push('row-overflow');"
                + "for(const e of root.querySelectorAll('button,input,[role=switch],summary,.droy-row-title,.droy-description')){"
                + "if(!e.getClientRects().length)continue;let collapsed=false;for(let a=e.parentElement;a&&a!==root;a=a.parentElement)"
                + "if(a.matches('details:not([open])')&&!a.querySelector(':scope > summary')?.contains(e)){collapsed=true;break}if(collapsed)continue;"
                + "const b=e.getBoundingClientRect();if(b.width<=0||b.height<=0||b.left<rr.left-1||b.right>rr.right+1)failures.push('control-outside-row');"
                + "if(e.matches('button,input,[role=switch],summary')&&(b.height<47||b.width<47))failures.push({kind:'small-touch-target',tag:e.tagName,height:b.height,width:b.width});"
                + "if(e.matches('input'))continue;const walk=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let n;while(n=walk.nextNode()){"
                + "if(!n.textContent.trim()||n.parentElement.closest('[aria-hidden=true]'))continue;const range=document.createRange();range.selectNodeContents(n);"
                + "for(const r of range.getClientRects()){if(r.width<.1||r.height<.1)continue;if(r.left<b.left-1||r.right>b.right+1||r.top<b.top-1||r.bottom>b.bottom+1)failures.push('text-clipped');"
                + "for(let a=n.parentElement;a&&a!==root;a=a.parentElement){const s=getComputedStyle(a),ar=a.getBoundingClientRect();"
                + "if(/hidden|clip/.test(s.overflowX)&&(r.left<ar.left-1||r.right>ar.right+1))failures.push('ancestor-clips-text')}}}}"
                + "const options=document.querySelector('[data-droy-settings-options]');if(options&&options.scrollWidth>options.clientWidth+1)failures.push('settings-overflow');"
                + "return {failures,cssWidth:innerWidth,cssHeight:innerHeight}})()");
        if (result.getJSONArray("failures").length() > 0) capture("layout-failure");
        assertEquals("Proactive notification settings layout failed: " + result, 0, result.getJSONArray("failures").length());
    }

    private void capture(String stage) throws Exception {
        ui.evaluate("window.__droyNotifyPainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__droyNotifyPainted=true))");
        DeviceUi.await("Settings did not finish painting", 5000, () -> ui.truth("window.__droyNotifyPainted"));
        instrumentation.waitForIdleSync();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "proactive-notification-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("notificationEvidenceLabel", "settings")
                    .replaceAll("[^A-Za-z0-9_-]", "_");
            File imageFile = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream output = new FileOutputStream(imageFile)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            JSONObject conditions = (JSONObject) ui.evaluate("({theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',"
                    + "cssWidth:innerWidth,cssHeight:innerHeight,dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),"
                    + "ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            conditions.put("api", android.os.Build.VERSION.SDK_INT);
            android.content.pm.PackageInfo webView = android.webkit.WebView.getCurrentWebViewPackage();
            conditions.put("webViewVersion", webView == null ? "unavailable" : webView.versionName);
            if (ui.keyboardVisible() && stableImeBounds != null)
                conditions.put("inputWindow", new JSONObject().put("left", stableImeBounds.left).put("top", stableImeBounds.top)
                        .put("right", stableImeBounds.right).put("bottom", stableImeBounds.bottom));
            File jsonFile = new File(directory, imageFile.getName().replace(".png", ".json"));
            try (FileOutputStream output = new FileOutputStream(jsonFile)) { output.write(conditions.toString(2).getBytes(StandardCharsets.UTF_8)); }
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_PROACTIVE_NOTIFICATION_EVIDENCE " + imageFile.getAbsolutePath()
                    + "\nDROY_PROACTIVE_NOTIFICATION_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }

    private Object safeNavigationState() throws Exception {
        return ui.evaluate("(()=>{const menu=document.querySelector('[data-droy-menu]'),r=menu?.getBoundingClientRect(),hit=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;"
                + "return {menu:!!menu,menuRect:r?{left:r.left,top:r.top,width:r.width,height:r.height}:null,menuHit:!!menu&&(hit===menu||menu.contains(hit)),drawer:!!document.querySelector('[data-droy-drawer-open=true]'),"
                + "settings:!!document.querySelector('[data-droy-settings-panel]'),modalTitles:[...document.querySelectorAll('[role=dialog] h2,[role=alertdialog] h2')]"
                + ".filter(e=>e.getClientRects().length).map(e=>e.textContent.trim())}})()");
    }
}

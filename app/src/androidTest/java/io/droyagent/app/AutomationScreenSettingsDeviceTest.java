package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The real settings entry, durable switch touches and rendered text geometry. */
@RunWith(AndroidJUnit4.class)
public final class AutomationScreenSettingsDeviceTest {
    private static final String ROOT = "[data-droy-general=automationScreenDisplay]";
    private static final String TOGGLE = ROOT + " [role=switch]";
    private static final String[] PREF_KEYS = {"automationScreenDisplay", "petEnabled", "petExternalDisplay", "petInternalDisplay"};
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private boolean ownsPreferences, startedRuntime, layoutChanged;
    private String originalTheme, originalTextFont;
    private boolean originalFontsExpanded;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        assertTrue("Stop tasks and runtime before screen-glow settings tests", RuntimeService.canManageHistory());
        Bundle arguments = InstrumentationRegistry.getArguments();
        boolean adjustLayout = "dark".equals(arguments.getString("layoutTheme")) || "true".equals(arguments.getString("layoutMaxTextFont"));
        if (adjustLayout) assertTrue("Theme/font matrix conditions require a dedicated emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : PREF_KEYS) if (preferences.contains(key)) originalPreferences.put(key, preferences.getAll().get(key));
        ownsPreferences = true;
        assertTrue(preferences.edit().remove("automationScreenDisplay").putBoolean("petEnabled", false)
            .putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").commit());
        startedRuntime = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity); ui.awaitPage(); openGeneral();
        if (adjustLayout) {
            DeviceUi.await("Original theme/font controls did not load", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')&&document.querySelectorAll('[data-droy-theme-choice]').length===3"));
            originalTheme = String.valueOf(ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')"));
            assertTrue(java.util.Set.of("light", "dark", "system").contains(originalTheme));
            originalTextFont = textFontValue(); originalFontsExpanded = fontsExpanded(); layoutChanged = true;
            if ("true".equals(arguments.getString("layoutMaxTextFont"))) {
                setFontsExpanded(true);
                String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
                for (int i = 0; i < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); i++) stepTextFont(increase);
                assertTrue("The actual DSH font control did not reach its maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
            }
            if ("dark".equals(arguments.getString("layoutTheme"))) chooseTheme("dark");
        }
    }

    @After public void tearDown() throws Exception {
        try {
            if (startedRuntime) {
                try {
                    if (layoutChanged) {
                        assertTrue("Cannot restore original theme/font without live settings", ui != null && activity != null && !activity.isDestroyed() && RuntimeService.ready);
                        if (!ui.truth("!!document.querySelector('[data-droy-settings-panel]')")) openGeneral();
                        ui.hideKeyboard();
                        if (!originalTextFont.equals(textFontValue())) {
                            setFontsExpanded(true);
                            double desired = Double.parseDouble(originalTextFont.replace("px", "").trim());
                            for (int i = 0; i < 60 && !originalTextFont.equals(textFontValue()); i++) {
                                double current = Double.parseDouble(textFontValue().replace("px", "").trim());
                                stepTextFont("[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=" + (current > desired ? "decrease" : "increase") + "]");
                            }
                        }
                        setFontsExpanded(originalFontsExpanded);
                        if (!originalTheme.equals(String.valueOf(ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')")))) chooseTheme(originalTheme);
                        assertEquals("DSH text size was not restored", originalTextFont, textFontValue()); awaitDurableLayout();
                    }
                } finally {
                    if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                    instrumentation.runOnMainSync(RuntimeService::requestStop);
                    DeviceUi.await("Settings test left runtime active", 15000, RuntimeService::canManageHistory);
                    AutomationScreenOverlay.runtimeStopped(); PetOverlay.runtimeStopped(); instrumentation.waitForIdleSync();
                }
            }
        } finally {
            if (ownsPreferences) {
                SharedPreferences.Editor restore = preferences.edit();
                for (String key : PREF_KEYS) {
                    Object value = originalPreferences.get(key);
                    if (value instanceof Boolean bool) restore.putBoolean(key, bool);
                    else if (value instanceof String text) restore.putString(key, text);
                    else restore.remove(key);
                }
                assertTrue("Could not restore screen-glow settings", restore.commit());
            }
        }
    }

    @Test public void defaultOnSwitchIsIndependentOfPetAndPersistsRealTouches() throws Exception {
        assertTrue("A fresh preference must default on", checked());
        assertFalse("The test fixture unexpectedly enabled the pet", PetOverlay.enabled(context));
        assertEquals("自动化操作屏幕显示", ui.evaluate("document.querySelector(" + JSONObject.quote(ROOT + " .droy-row-title") + ").textContent.trim()"));
        assertEquals("当Droy操作手机时，屏幕边缘会发光提醒", ui.evaluate("document.querySelector(" + JSONObject.quote(ROOT + " .droy-description") + ").textContent.trim()"));
        assertLayout(); capture("01-default-on");
        ui.tap(TOGGLE);
        DeviceUi.await("The real switch touch did not save false", 5000,
            () -> preferences.contains("automationScreenDisplay") && !preferences.getBoolean("automationScreenDisplay", true) && !checked());
        assertFalse("Disabling the glow unexpectedly enabled the pet", PetOverlay.enabled(context));
        capture("02-disabled");
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings Back did not return to the same conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')||!!document.querySelector('[data-droy-drawer-open=true]')"));
        openGeneral(); assertFalse("Reopening settings lost the explicit disabled preference", checked());
        assertLayout(); ui.tap(TOGGLE);
        DeviceUi.await("The real switch touch did not save true", 5000,
            () -> preferences.getBoolean("automationScreenDisplay", false) && checked());
        assertFalse("Enabling the glow changed the independent pet preference", PetOverlay.enabled(context));
        assertLayout(); capture("03-enabled-saved");
        ui.pressBack();
        DeviceUi.await("System Back did not close screen-glow settings", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private boolean checked() throws Exception { return ui.truth("document.querySelector(" + JSONObject.quote(TOGGLE) + ").getAttribute('aria-checked')==='true'"); }
    private void openGeneral() throws Exception {
        Object initial = safeNavigationState();
        dismissAccountWelcome();
        ui.hideKeyboard();
        DeviceUi.await("The settings Activity did not gain focus", 5000,
            () -> ui.nativeNavigationState().getBoolean("activityFocus"));
        instrumentation.getUiAutomation().waitForIdle(300, 5000);
        if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) {
            try {
                DeviceUi.await("The initial menu is covered by a dialog or transition", 5000,
                    () -> ui.truth("(()=>{const e=document.querySelector('[data-droy-menu]');if(!e)return false;const r=e.getBoundingClientRect(),"
                        + "hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!hit&&(hit===e||e.contains(hit))})()"));
            } catch (AssertionError failure) {
                captureNavigation("initial-menu-covered");
                throw new AssertionError(failure.getMessage() + "; initial=" + initial + "; safe state=" + safeNavigationState()
                    + "; native=" + ui.nativeNavigationState(), failure);
            }
            Object beforeTouch = safeNavigationState();
            captureNavigation("00-before-menu-touch");
            ui.tap("[data-droy-menu]");
            try {
                DeviceUi.await("The real menu touch did not open the drawer", 5000,
                    () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
            } catch (AssertionError failure) {
                captureNavigation("drawer-open-failure");
                throw new AssertionError(failure.getMessage() + "; initial=" + initial + "; before=" + beforeTouch
                    + "; safe state=" + safeNavigationState() + "; nativeBounds=" + ui.webBounds("[data-droy-menu]")
                    + "; native=" + ui.nativeNavigationState(), failure);
            }
        }
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The settings page did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The screen-glow setting did not become interactive", 10000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(TOGGLE) + ")&&!document.querySelector(" + JSONObject.quote(TOGGLE) + ").disabled"));
    }
    private void dismissAccountWelcome() throws Exception {
        long[] quietSince = {0};
        DeviceUi.await("The fresh-home account welcome did not settle", 10000, () -> {
            boolean clicked = ui.truth("(()=>{for(const heading of document.querySelectorAll('[role=dialog] h2')){"
                + "if(!['开始使用','Get started'].includes(heading.textContent.trim()))continue;const dialog=heading.closest('[role=dialog]');"
                + "if(!dialog.getClientRects().length)continue;const close=dialog.querySelector('button[aria-label=关闭],button[aria-label=Close]');"
                + "if(close&&!close.disabled){close.click();return true}}return false})()");
            if (clicked) { quietSince[0] = 0; return false; }
            if (quietSince[0] == 0) quietSince[0] = android.os.SystemClock.elapsedRealtime();
            return android.os.SystemClock.elapsedRealtime() - quietSince[0] >= 600;
        });
    }
    private Object safeNavigationState() throws Exception {
        return ui.evaluate("(()=>{const menu=document.querySelector('[data-droy-menu]'),r=menu?.getBoundingClientRect(),hit=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;"
            + "const known=['开始使用','Get started','预览版说明','Preview Notice','欢迎使用 DroyAgent','Welcome to DroyAgent','添加一个 API Key 开始使用','Add an API key to get started'];"
            + "return {menu:!!menu,menuRect:r?{left:r.left,top:r.top,width:r.width,height:r.height}:null,menuHit:!!menu&&(hit===menu||menu.contains(hit)),"
            + "menuHitElement:hit?{tag:hit.tagName,role:hit.getAttribute('role')}:null,drawer:!!document.querySelector('[data-droy-drawer-open=true]'),"
            + "settings:!!document.querySelector('[data-droy-settings-panel]'),knownModalTitles:[...document.querySelectorAll('[role=dialog] h2,[role=alertdialog] h2')]"
            + ".filter(e=>e.getClientRects().length).map(e=>e.textContent.trim()).filter(text=>known.includes(text)),visibleModalCount:[...document.querySelectorAll('[role=dialog],[role=alertdialog]')].filter(e=>e.getClientRects().length).length}})()");
    }
    private void captureNavigation(String stage) throws Exception {
        instrumentation.waitForIdleSync();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull("Could not capture navigation before settings opened", image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "screen-glow-settings-evidence"); assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("glowEvidenceLabel", "settings").replaceAll("[^A-Za-z0-9_-]", "_");
            File output = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_SCREEN_GLOW_NAVIGATION_EVIDENCE " + output.getAbsolutePath()
                + "\nDROY_SCREEN_GLOW_NAVIGATION " + safeNavigationState() + "\n"); instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }
    private void assertLayout() throws Exception {
        revealWholeSetting();
        JSONObject shape = (JSONObject) ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(ROOT) + "),r=row.getBoundingClientRect(),toggle=row.querySelector('[role=switch]');"
            + "const rect=e=>{const b=e.getBoundingClientRect();return {left:b.left,right:b.right,top:b.top,bottom:b.bottom,width:b.width,height:b.height}};"
            + "return {row:rect(row),toggle:rect(toggle),widths:[document.documentElement,document.querySelector('[data-droy-settings-options]'),row].map(e=>({client:e.clientWidth,scroll:e.scrollWidth})),"
            + "text:[...row.querySelectorAll('.droy-row-title,.droy-description,.droy-status')].filter(e=>e.getClientRects().length).map(e=>({rect:rect(e),right:e.getBoundingClientRect().right,inside:e.getBoundingClientRect().left>=r.left-1&&e.getBoundingClientRect().right<=r.right+1}))}})()");
        JSONArray widths = shape.getJSONArray("widths");
        for (int i = 0; i < widths.length(); i++) assertTrue("Settings requires unintended horizontal scrolling: " + shape,
            widths.getJSONObject(i).getDouble("scroll") <= widths.getJSONObject(i).getDouble("client") + 1);
        JSONObject toggle = shape.getJSONObject("toggle");
        assertTrue("The switch lost its minimum real touch area: " + shape, toggle.getDouble("width") >= 47 && toggle.getDouble("height") >= 47);
        JSONArray text = shape.getJSONArray("text"); assertTrue(text.length() >= 2);
        for (int i = 0; i < text.length(); i++) assertTrue("Necessary text escaped the row: " + shape, text.getJSONObject(i).getBoolean("inside"));
        assertTrue("Setting text is clipped or overlaps its switch", ui.truth("(()=>{const row=document.querySelector(" + JSONObject.quote(ROOT) + "),toggle=row.querySelector('[role=switch]').getBoundingClientRect();"
            + "for(const e of row.querySelectorAll('.droy-row-title,.droy-description,.droy-status')){if(!e.getClientRects().length)continue;const bounds=e.getBoundingClientRect(),walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let node;"
            + "while(node=walker.nextNode()){if(!node.textContent.trim())continue;const range=document.createRange();range.selectNodeContents(node);for(const r of range.getClientRects()){"
            + "if(r.width<.1||r.height<.1)continue;if(r.left<bounds.left-1||r.right>bounds.right+1||r.top<bounds.top-1||r.bottom>bounds.bottom+1)return false;"
            + "if(r.right>toggle.left+1&&r.left<toggle.right-1&&r.bottom>toggle.top+1&&r.top<toggle.bottom-1)return false;"
            + "for(let a=node.parentElement;a&&a!==row;a=a.parentElement){const c=getComputedStyle(a),b=a.getBoundingClientRect();if(/hidden|clip/.test(c.overflowX)&&(r.left<b.left-1||r.right>b.right+1))return false;"
            + "if(/hidden|clip/.test(c.overflowY)&&(r.top<b.top-1||r.bottom>b.bottom+1))return false}}}}return true})()"));
    }
    private String textFontValue() throws Exception { return String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()")); }
    private boolean fontsExpanded() throws Exception { return ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'"); }
    private void setFontsExpanded(boolean expanded) throws Exception {
        if (fontsExpanded() != expanded) ui.tap("[data-droy-font-expander]");
        DeviceUi.await("Font controls did not change expansion", 5000, () -> fontsExpanded() == expanded);
    }
    private void stepTextFont(String selector) throws Exception {
        String previous = textFontValue(); ui.tap(selector);
        DeviceUi.await("The real DSH text-font touch did not apply", 5000, () -> !previous.equals(textFontValue()));
    }
    private void chooseTheme(String theme) throws Exception {
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("The original theme control did not apply", 10000,
            () -> theme.equals(preferences.getString("themePreference", ""))
                && ui.truth("document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme))
                && (!theme.equals("dark") || ui.truth("document.body.hasAttribute('data-ds-dark-theme')")));
    }
    private void awaitDurableLayout() throws Exception {
        DeviceUi.await("Restored theme/font did not finish saving", 10000, () -> {
            File file = new File(context.getFilesDir(), "dsh-home/profiles/mobile/cordis.patch.yml");
            String source = file.isFile() ? new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
            java.util.regex.Matcher section = java.util.regex.Pattern.compile("(?ms)^- id: ui-theme\\R(?:(?!^- id:).)*").matcher(source);
            String value = section.find() ? section.group() : "";
            java.util.regex.Matcher theme = java.util.regex.Pattern.compile("(?m)^\\s+preference: [\"']?(system|light|dark)").matcher(value);
            java.util.regex.Matcher size = java.util.regex.Pattern.compile("(?m)^\\s+fontSize: (\\d+)").matcher(value);
            return originalTheme.equals(theme.find() ? theme.group(1) : "system") && originalTextFont.equals((size.find() ? size.group(1) : "14") + "px");
        });
    }
    private void capture(String stage) throws Exception {
        revealWholeSetting();
        java.util.concurrent.CountDownLatch painted = new java.util.concurrent.CountDownLatch(1);
        android.webkit.WebView web = ui.web();
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) {
                web.getViewTreeObserver().registerFrameCommitCallback(painted::countDown);
                web.invalidate();
            }
        }));
        assertTrue("Settings screenshot was not committed", painted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "screen-glow-settings-evidence"); assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("glowEvidenceLabel", "settings").replaceAll("[^A-Za-z0-9_-]", "_");
            JSONObject conditions = (JSONObject) ui.evaluate("({cssWidth:innerWidth,cssHeight:innerHeight,themeSource:document.documentElement.getAttribute('data-ds-theme-source'),theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()})");
            conditions.put("fontScale", activity.getResources().getConfiguration().fontScale).put("width", image.getWidth()).put("height", image.getHeight());
            File output = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_SCREEN_GLOW_SETTINGS_EVIDENCE " + output.getAbsolutePath() + "\nDROY_SCREEN_GLOW_SETTINGS_CONDITIONS " + conditions + "\n"); instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }
    private void revealWholeSetting() throws Exception {
        DeviceUi.await("The complete screen-glow setting cannot be brought into view", 5000, () -> ui.truth(
            "(()=>{const row=document.querySelector(" + JSONObject.quote(ROOT) + "),owner=row?.closest('[data-droy-settings-options]');"
            + "if(!row||!owner)return false;const v=visualViewport,b=owner.getBoundingClientRect(),top=Math.max(b.top,(v?.offsetTop||0)+60),"
            + "bottom=Math.min(b.bottom,(v?.offsetTop||0)+(v?.height||innerHeight)-24),r=row.getBoundingClientRect();"
            + "if(r.top<top||r.bottom>bottom)owner.scrollTop+=(r.top+r.bottom-top-bottom)/2;"
            + "const next=row.getBoundingClientRect();return next.top>=top-1&&next.bottom<=bottom+1})()"));
        ui.visibleWebBounds(TOGGLE);
    }
}

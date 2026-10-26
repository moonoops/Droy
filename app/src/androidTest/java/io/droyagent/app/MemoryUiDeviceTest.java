package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real settings entry and denied Root flow. No readiness fixture or capture is enabled. */
@RunWith(AndroidJUnit4.class)
public final class MemoryUiDeviceTest {
    private static final String MAIN_SWITCH = "[data-droy-memory] > .droy-memory-toggle [role=switch]";
    private static final String[] PRIVATE_PATHS = {"dsh-home", "droy-memory", "droy-memory-archives", "droy-privacy", "droy-privacy-marker.json", "droy-privacy.identity.json", "droy-history-spool", "droy-execution"};
    private final Map<String, Map<String, ?>> savedPreferences = new LinkedHashMap<>();
    private final Map<File, File> preserved = new LinkedHashMap<>();
    private final Map<File, Map<String, String>> originalFiles = new LinkedHashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private boolean ownsState, launched, rotated;

    /** Select this nested class explicitly; it never uses the emulator fixture setup. */
    @RunWith(AndroidJUnit4.class)
    public static final class PhoneReadOnly {
        private MemoryUiDeviceTest page;
        private Map<String, Object> memoryChoices;
        private String historyConfiguration;

        @Before public void setUpPhone() throws Exception {
            assertEquals("Pass -e memoryUiReadOnlyPhone true for the read-only phone entry", "true",
                InstrumentationRegistry.getArguments().getString("memoryUiReadOnlyPhone"));
            assertFalse("This class is for the target phone", Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
            assertTrue("Finish other tasks before the phone UI check", RuntimeService.canManageHistory());
            page = new MemoryUiDeviceTest();
            page.instrumentation = InstrumentationRegistry.getInstrumentation();
            page.context = page.instrumentation.getTargetContext();
            memoryChoices = choices(page.context);
            historyConfiguration = page.context.getSharedPreferences("history_capture", Context.MODE_PRIVATE).getString("configuration", "{}");
            page.activity = (MainActivity) page.instrumentation.startActivitySync(new Intent(page.context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            page.ui = new DeviceUi(page.instrumentation, page.activity);
            page.ui.awaitPage();
        }

        @After public void tearDownPhone() throws Exception {
            if (page == null) return;
            if (page.activity != null && !page.activity.isDestroyed()) page.instrumentation.runOnMainSync(page.activity::finish);
            assertEquals("Read-only navigation changed memory choices", memoryChoices, choices(page.context));
            assertEquals("Read-only navigation changed capture choices", historyConfiguration,
                page.context.getSharedPreferences("history_capture", Context.MODE_PRIVATE).getString("configuration", "{}"));
            // Normal activity/background lifecycle remains in charge; requestStop
            // would write the user's persistent runtime choice on this phone.
        }

        @Test public void allSettingsPeersAndMemorySubpagesNavigateWithoutChangingChoices() throws Exception {
            page.openSettings();
            JSONArray tabs = (JSONArray) page.ui.evaluate("[...document.querySelectorAll('[data-droy-settings-section]')].map(e=>e.getAttribute('data-droy-settings-section'))");
            boolean foundMemory = false;
            for (int index = 0; index < tabs.length(); index++) {
                String id = tabs.getString(index), selector = "[data-droy-settings-section=" + JSONObject.quote(id) + "]";
                page.ui.tap(selector);
                DeviceUi.await("A settings peer did not become selected", 10000,
                    () -> page.ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-current')==='true'"));
                page.assertTextFits(selector);
                if (id.equals("droy-memory")) {
                    foundMemory = true;
                    DeviceUi.await("The read-only memory page did not load", 10000,
                        () -> page.ui.truth("!!document.querySelector('" + MAIN_SWITCH + "')&&!document.querySelector('" + MAIN_SWITCH + "').disabled"));
                    boolean enabledBefore = page.checked();
                    assertTrue("Only memory settings should be exposed", page.ui.truth("!document.querySelector('[data-droy-memory-tab],.droy-memory-search,[data-droy-memory-archive]')&&!!document.querySelector('.droy-memory-settings')"));
                    assertEquals("Navigation changed the memory master", enabledBefore, page.checked());
                    page.assertTextFits("[data-droy-memory] > .droy-memory-toggle"); page.capture("phone-memory-settings");
                    page.assertTextFits(".droy-memory-settings section:last-child > p:last-child"); page.capture("phone-memory-settings-bottom");
                }
                page.capture("phone-settings-" + id.replaceAll("[^A-Za-z0-9_-]", "_"));
            }
            assertTrue("The memory peer is missing", foundMemory);
            page.ui.tap("[data-droy-settings-section=general]");
            page.ui.tap("[data-droy-settings-back]");
            DeviceUi.await("Phone settings did not return to the conversation", 5000,
                () -> !page.ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        }

        private static Map<String, Object> choices(Context context) {
            SharedPreferences preferences = context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE);
            Map<String, Object> values = new HashMap<>();
            for (String key : new String[]{"enabled", "recallEnabled", "automaticExtraction", "privacyEverActivated"}) {
                if (preferences.contains(key)) values.put(key, preferences.getAll().get(key));
            }
            return values;
        }
    }

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Personal memory UI validation requires a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertEquals("Pass -e memoryUiDedicated true on a disposable emulator without app Root authorization", "true",
            InstrumentationRegistry.getArguments().getString("memoryUiDedicated"));
        assertTrue("Finish tasks and stop the engine before memory UI validation", RuntimeService.canManageHistory());
        for (String name : new String[]{"product", "droy_memory", "history_capture"}) {
            savedPreferences.put(name, new HashMap<>(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        }
        ownsState = true;
        for (String name : PRIVATE_PATHS) {
            File current = new File(context.getFilesDir(), name);
            originalFiles.put(current, snapshot(current.toPath()));
            File backup = new File(context.getFilesDir(), "memory-ui-preserved-" + UUID.randomUUID());
            if (current.exists()) {
                assertTrue("Could not preserve an existing memory test path: " + name, current.renameTo(backup));
                preserved.put(current, backup);
            } else preserved.put(current, null);
        }
        assertTrue(new File(context.getFilesDir(), "dsh-home").mkdirs());
        assertTrue(context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(context.getSharedPreferences("history_capture", Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(context.getSharedPreferences("product", Context.MODE_PRIVATE).edit()
            .putBoolean("petEnabled", false).putString("petInternalDisplay", "off").putString("petExternalDisplay", "off")
            .putBoolean("backgroundRuntimeEnabled", false).putBoolean(BackgroundRuntime.STOPPED, false).commit());
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertTrue("Could not establish the display orientation", automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated = true;
        launched = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        boolean landscape = "true".equals(InstrumentationRegistry.getArguments().getString("layoutLandscape"));
        assertTrue("Could not orient the ready WebView", automation.setRotation(landscape ? UiAutomation.ROTATION_FREEZE_90 : UiAutomation.ROTATION_FREEZE_0));
        DeviceUi.await("WebView did not reach the requested orientation", 5000,
            () -> ui.truth(landscape ? "innerWidth>innerHeight" : "innerHeight>innerWidth"));
        applyDisplayCondition();
    }

    @After public void tearDown() throws Exception {
        if (!ownsState) return;
        boolean restoredRotation = !rotated || instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).setRotation(UiAutomation.ROTATION_UNFREEZE);
        rotated = false;
        try {
            if (launched) {
                try { if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish); }
                finally {
                    instrumentation.runOnMainSync(RuntimeService::requestStop);
                    DeviceUi.await("Memory UI cleanup left an engine active", 20000, RuntimeService::canManageHistory);
                    PetOverlay.runtimeStopped();
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            assertTrue("Keep preserved data until the fixture engine has stopped", RuntimeService.canManageHistory());
            for (Map.Entry<File, File> entry : preserved.entrySet()) {
                Path path = entry.getKey().toPath();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) HistoryData.deleteTree(path);
                else Files.deleteIfExists(path);
                if (entry.getValue() != null) assertTrue("Could not restore original private data", entry.getValue().renameTo(entry.getKey()));
                assertEquals("Memory UI test changed original private data", originalFiles.get(entry.getKey()), snapshot(path));
            }
            for (Map.Entry<String, Map<String, ?>> entry : savedPreferences.entrySet()) restorePreferences(entry.getKey(), entry.getValue());
            ownsState = false;
            assertTrue("Could not restore display rotation", restoredRotation);
        }
    }

    @Test public void memoryTabIsPeerToGeneralAndRootDenialLeavesAllCaptureOff() throws Exception {
        openSettings();
        assertTrue("Memory is not a peer of general settings", ui.truth("(()=>{const g=document.querySelector('[data-droy-settings-section=general]'),m=document.querySelector('[data-droy-settings-section=droy-memory]');return !!g&&!!m&&g.parentElement===m.parentElement})()"));
        assertEquals(localized("Droy 记忆", "Droy Memory"), ui.evaluate("document.querySelector('[data-droy-settings-section=droy-memory]').textContent.trim()"));
        JSONArray peers = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-droy-settings-section]')].map(e=>e.getAttribute('data-droy-settings-section'))");
        for (int index = 0; index < peers.length(); index++) {
            String id = peers.getString(index), peer = "[data-droy-settings-section=" + JSONObject.quote(id) + "]";
            ui.tap(peer);
            DeviceUi.await("A shared settings tab did not become selected", 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(peer) + ")?.getAttribute('aria-current')==='true'"));
            assertTextFits(peer); capture("peer-" + id.replaceAll("[^A-Za-z0-9_-]", "_"));
        }
        ui.tap("[data-droy-settings-section=droy-memory]");
        DeviceUi.await("The memory main switch did not load", 10000, () -> ui.truth("!!document.querySelector('" + MAIN_SWITCH + "')&&!document.querySelector('" + MAIN_SWITCH + "').disabled"));
        DeviceUi.await("The initial memory status did not settle", 10000,
            () -> ui.truth("!!document.querySelector('.droy-memory-settings')"));
        assertFalse("The memory master must start disabled", checked());
        assertEquals(localized("Droy记忆", "Droy memory"), ui.evaluate("document.querySelector('[data-droy-memory] > .droy-memory-toggle > div > span').textContent"));
        assertTrue("The memory purpose must describe supported recording only", ui.truth("document.querySelector('[data-droy-memory] > .droy-memory-toggle').textContent.includes(" + JSONObject.quote(localized("去过的地方和做过的事", "places you visit and things you do")) + ")"));
        assertTextFits("[data-droy-memory] > .droy-memory-toggle");
        capture("main-off");

        ui.tap(MAIN_SWITCH);
        DeviceUi.await("The real host did not reject enabling without Root", 30000,
            () -> ui.truth("document.querySelector('.droy-memory-error')?.textContent.includes('Root')") && !checked());
        assertFalse("Root rejection still persisted enabled", context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE).getBoolean("enabled", false));
        JSONObject status = readStatus();
        assertFalse("This test requires a real non-Root emulator", status.getJSONObject("gate").getBoolean("rootAuthorized"));
        assertFalse(status.getBoolean("available"));
        JSONObject capture = status.getJSONObject("capture");
        assertNotEquals("collecting", capture.getString("state"));
        JSONObject config = capture.getJSONObject("configuration");
        for (String key : new String[]{"enabled", "activityEnabled", "pageTextEnabled", "deviceStateEnabled", "locationEnabled"}) assertFalse(key, config.getBoolean(key));
        capture("root-denied");

        assertTrue("Only the Settings content should remain visible", ui.truth("!document.querySelector('[data-droy-memory-tab],.droy-memory-search,[data-droy-memory-archive]')&&!!document.querySelector('.droy-memory-settings')"));
        assertFalse("Retention must stay hidden", ui.truth("document.querySelector('.droy-memory-settings').textContent.includes(" + JSONObject.quote(localized("保留与遗忘", "Retention and forgetting")) + ")"));
        int sectionCount = ((Number) ui.evaluate("document.querySelectorAll('.droy-memory-settings > section').length")).intValue();
        for (int section = 1; section <= sectionCount; section++) {
            String heading = ".droy-memory-settings > section:nth-child(" + section + ") > h3";
            assertTextFits(heading); capture("settings-section-" + section);
        }
        assertTextFits(".droy-memory-settings section:last-child > p:last-child"); capture("settings-bottom");
        assertTrue("Personal memory settings unexpectedly scroll horizontally", ui.truth("document.documentElement.scrollWidth<=document.documentElement.clientWidth+1"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The shared settings container no longer opens General", 5000,
            () -> ui.truth("!!document.querySelector('#droy-assistant-name')"));
        capture("general-peer");
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("The settings back touch did not return to the conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    @Test public void simplifiedSettingsUseInlineChoicesAndMatchingDiagnostics() throws Exception {
        openSettings();
        ui.tap("[data-droy-settings-section=droy-memory]");
        DeviceUi.await("Memory settings did not load", 10000, () -> ui.truth("!!document.querySelector('.droy-memory-settings')"));
        assertTrue("Memory lists and backup must be hidden", ui.truth("!document.querySelector('[data-droy-memory-tab],.droy-memory-search,[data-droy-memory-archive]')"));
        assertTrue("Memory description must explain its purpose and Root", ui.truth("document.querySelector('[data-droy-memory] > .droy-memory-toggle').textContent.includes(" + JSONObject.quote(localized("去过的地方和做过的事", "places you visit and things you do")) + ")&&document.querySelector('[data-droy-memory] > .droy-memory-toggle').textContent.includes('Root')"));
        assertTextFits("[data-droy-memory] > .droy-memory-toggle"); capture("simplified-memory");
        int count = ((Number) ui.evaluate("document.querySelectorAll('.droy-memory-settings > section').length")).intValue();
        for (int index = 1; index <= count; index++) {
            assertTextFits(".droy-memory-settings > section:nth-child(" + index + ") > h3"); capture("memory-section-" + index);
        }
        assertTextFits(".droy-memory-settings section:last-child > p:last-child"); capture("memory-bottom");

        ui.tap("[data-droy-settings-section=droy-voice]");
        DeviceUi.await("Voice choices did not load", 15000,
            () -> ui.truth("!!document.querySelector('.droy-voice-choice summary')"));
        assertTrue("Speech controls must use Droy dropdowns; audio capture source is a separate control", ui.truth("(()=>{const root=document.querySelector('[data-droy-voice-settings]');return [...root.querySelectorAll('select')].every(e=>!!e.closest('[data-droy-general=audio]')&&['声音来源','Audio source'].includes(e.getAttribute('aria-label')))&&" + JSONObject.quote(localized("发声方式|朗读音色|语速", "Speech provider|Readout voice|Speech rate")) + ".split('|').every(label=>[...root.querySelectorAll('.droy-voice-choice>summary')].some(e=>e.getAttribute('aria-label')===label))})()"));
        String engine = ".droy-voice-choice > summary[aria-label=" + JSONObject.quote(localized("发声方式", "Speech provider")) + "]";
        ui.visibleWebBounds(engine); ui.tap(engine);
        DeviceUi.await("Inline engine dropdown did not open", 5000,
            () -> ui.truth("!!document.querySelector('.droy-voice-choice[open] [role=listbox]')"));
        assertTextFits(".droy-voice-choice[open] [role=option][data-value=melo]"); capture("inline-voice-dropdown");
        ui.pressBack();
        DeviceUi.await("System back must fold the dropdown before leaving settings", 5000,
            () -> ui.truth("!document.querySelector('.droy-voice-choice[open]')&&!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap(engine); ui.tap(".droy-voice-choice[open] [role=option][data-value=melo]");
        DeviceUi.await("Inline choice did not update its draft", 5000,
            () -> "melo".equals(ui.evaluate("document.querySelector(" + JSONObject.quote(engine) + ").dataset.value")));
        assertTrue("Choosing a draft must close the list", ui.truth("!document.querySelector('.droy-voice-choice[open]')"));
        capture("voice-selected-draft");

        ui.tap("[data-droy-settings-section=droy-permissions]");
        DeviceUi.await("Phone permissions did not load", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-preference=allowMemoryWrite]')"));
        assertTextFits("[data-droy-permissions] > h2"); capture("simple-permissions");
        ui.evaluate("document.querySelector('[data-droy-preference=allowMemoryWrite]').closest('.droy-row').setAttribute('data-settings-last-capability','')");
        assertTextFits("[data-settings-last-capability]"); capture("permissions-last-capability");
        String capability = "[data-droy-preference=allowMemoryWrite] [role=switch]";
        boolean before = ui.truth("document.querySelector(" + JSONObject.quote(capability) + ").getAttribute('aria-checked')==='true'");
        ui.tap(capability);
        DeviceUi.await("Permission switch did not save", 5000,
            () -> before != ui.truth("document.querySelector(" + JSONObject.quote(capability) + ").getAttribute('aria-checked')==='true'"));
        ui.tap(capability);
        DeviceUi.await("Permission switch did not restore", 5000,
            () -> before == ui.truth("document.querySelector(" + JSONObject.quote(capability) + ").getAttribute('aria-checked')==='true'"));
        String lastSystem = "[data-droy-permission=brightness]";
        assertTextFits(lastSystem); capture("system-permissions");

        ui.tap("[data-droy-settings-section=general]");
        ui.visibleWebBounds("[data-droy-action=openDiagnostics]"); ui.tap("[data-droy-action=openDiagnostics]");
        DeviceUi.await("Native diagnostics did not open", 5000,
            () -> DeviceUi.findButton(activity.getWindow().getDecorView(), localized("复制诊断", "Copy diagnostics")) != null);
        capture("matching-diagnostics-top");
        String deletion = localized("删除全部对话记录", "Delete all conversation history");
        instrumentation.runOnMainSync(() -> {
            android.widget.Button control = DeviceUi.findButton(activity.getWindow().getDecorView(), deletion);
            assertNotNull(control); assertNull("Diagnostics must not force uppercase button labels", control.getTransformationMethod());
            control.requestRectangleOnScreen(new android.graphics.Rect(0, 0, control.getWidth(), control.getHeight()), true);
        });
        instrumentation.waitForIdleSync(); capture("matching-diagnostics-bottom");
        android.graphics.Rect backBounds = new android.graphics.Rect();
        instrumentation.runOnMainSync(() -> {
            android.widget.Button back = DeviceUi.findButton(activity.getWindow().getDecorView(), localized("‹ 返回", "‹ Back"));
            assertNotNull(back); back.requestRectangleOnScreen(new android.graphics.Rect(0, 0, back.getWidth(), back.getHeight()), true);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> DeviceUi.findButton(activity.getWindow().getDecorView(), localized("‹ 返回", "‹ Back")).getGlobalVisibleRect(backBounds));
        ui.tapAt(backBounds.centerX(), backBounds.centerY());
        DeviceUi.await("Diagnostics back did not reveal the conversation", 5000, ui::nativeUiReady);
    }

    private void openSettings() throws Exception {
        ui.hideKeyboard();
        DeviceUi.await("The keyboard touch window did not leave before opening the menu", 5000, () -> !ui.keyboardVisible());
        ui.awaitPaint();
        if (ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) {
            SettingsEntryDeviceUi.openFromDrawer(ui);
            return;
        }
        ui.evaluate("window.__settingsMenuTouches=[];window.__settingsMenuTouchListener=e=>{window.__settingsMenuTouches.push({type:e.type,trusted:e.isTrusted,x:e.clientX,y:e.clientY,menu:!!e.target?.closest?.('[data-droy-menu]')});if(window.__settingsMenuTouches.length>12)window.__settingsMenuTouches.shift()};for(const type of ['pointerdown','pointerup','pointercancel','click'])document.addEventListener(type,window.__settingsMenuTouchListener,true)");
        try {
            ui.visibleWebBounds("[data-droy-menu]");
            android.graphics.Rect[] previous = {null};
            long[] stableSince = {0};
            DeviceUi.await("The menu touch target did not settle in the current viewport", 5000, () -> {
                android.graphics.Rect current = ui.webBounds("[data-droy-menu]");
                boolean hit = ui.truth("(()=>{const e=document.querySelector('[data-droy-menu]'),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!hit&&(e===hit||e.contains(hit))&&!e.closest('[inert]')})()");
                if (previous[0] == null || !previous[0].equals(current) || !hit) {
                    previous[0] = current; stableSince[0] = android.os.SystemClock.uptimeMillis(); return false;
                }
                return android.os.SystemClock.uptimeMillis() - stableSince[0] >= 150;
            });
            ui.awaitPaint();
            android.graphics.Rect target = ui.webBounds("[data-droy-menu]");
            assertEquals("The committed menu target moved before touch", previous[0], target);
            ui.tapAt(target.centerX(), target.centerY());
            DeviceUi.await("The real menu did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
            SettingsEntryDeviceUi.openFromDrawer(ui);
            DeviceUi.await("Settings did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        } catch (Exception | AssertionError failure) {
            try { recordMenuFailure(); captureMenuFailure(); }
            catch (Exception | AssertionError diagnostics) { failure.addSuppressed(diagnostics); }
            throw failure;
        } finally {
            ui.evaluate("for(const type of ['pointerdown','pointerup','pointercancel','click'])document.removeEventListener(type,window.__settingsMenuTouchListener,true);delete window.__settingsMenuTouchListener;delete window.__settingsMenuTouches");
        }
    }

    private void recordMenuFailure() throws Exception {
        JSONObject state = (JSONObject) ui.evaluate("(()=>{const mark=e=>e?{tag:e.tagName,role:e.getAttribute('role'),menu:e.hasAttribute('data-droy-menu'),sidebar:e.hasAttribute('data-droy-sidebar'),frame:e.hasAttribute('data-droy-frame'),inert:e.hasAttribute('inert'),ariaHidden:e.getAttribute('aria-hidden')}:null;const rect=e=>{if(!e)return null;const r=e.getBoundingClientRect(),s=getComputedStyle(e),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);const ancestors=[];for(let p=e;p&&ancestors.length<7;p=p.parentElement){const ps=getComputedStyle(p);ancestors.push({...mark(p),display:ps.display,visibility:ps.visibility,position:ps.position,zIndex:ps.zIndex,pointerEvents:ps.pointerEvents})}return {x:r.x,y:r.y,width:r.width,height:r.height,clientRects:e.getClientRects().length,display:s.display,visibility:s.visibility,pointerEvents:s.pointerEvents,inert:!!e.closest('[inert]'),ariaHidden:!!e.closest('[aria-hidden=true]'),hit:mark(hit),hitInside:!!hit&&(e===hit||e.contains(hit)),ancestors}};const v=visualViewport;return {menus:[...document.querySelectorAll('[data-droy-menu]')].map(rect),frames:[...document.querySelectorAll('[data-droy-frame]')].map(rect),drawers:[...document.querySelectorAll('[data-droy-sidebar]')].map(rect),drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]'),viewport:{width:innerWidth,height:innerHeight,scale:v?.scale,visualWidth:v?.width,visualHeight:v?.height,offsetLeft:v?.offsetLeft,offsetTop:v?.offsetTop},touches:window.__settingsMenuTouches||[]}})()");
        JSONObject nativeView = new JSONObject();
        android.webkit.WebView view = ui.web();
        instrumentation.runOnMainSync(() -> {
            int[] location = new int[2]; view.getLocationOnScreen(location);
            try { nativeView.put("x", location[0]).put("y", location[1]).put("width", view.getWidth()).put("height", view.getHeight())
                .put("shown", view.isShown()).put("focused", view.hasWindowFocus()).put("displayId", view.getDisplay().getDisplayId()); }
            catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
        });
        state.put("nativeWeb", nativeView).put("loadingOverlay", ui.loadingOverlayShown()).put("keyboard", ui.keyboardVisible());
        Bundle output = new Bundle(); output.putString("stream", "\nDROY_SETTINGS_MENU_TOUCH_FAILURE " + state + "\n"); instrumentation.sendStatus(2, output);
    }

    private void captureMenuFailure() throws Exception {
        Bitmap screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull("Could not capture the actual menu failure frame", screenshot);
        try {
            File directory = new File(context.getExternalFilesDir(null), "memory-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            File output = File.createTempFile("menu-touch-failed-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle result = new Bundle(); result.putString("stream", "\nDROY_SETTINGS_MENU_FAILURE_SCREENSHOT " + output.getAbsolutePath() + "\n"); instrumentation.sendStatus(2, result);
        } finally { screenshot.recycle(); }
    }

    private void applyDisplayCondition() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        openSettings(); ui.tap("[data-droy-settings-section=general]");
        String language = args.getString("layoutLanguage", "zh");
        assertTrue("Unknown language", language.equals("zh") || language.equals("en"));
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-settings-panel] button[aria-haspopup=menu]')].find(e=>['中文','English'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-memory-language-selector','');return true})()"));
        ui.tap("[data-memory-language-selector]");
        String languageLabel = language.equals("en") ? "English" : "中文";
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&e.textContent.trim()==="
            + JSONObject.quote(languageLabel) + ");if(!e)return false;e.setAttribute('data-memory-language-option','');return true})()"));
        ui.tap("[data-memory-language-option]");
        DeviceUi.await("The language selection did not reach both DSH and native settings", 10000,
            () -> language.equals(context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("languagePreference", ""))
                && ui.truth("document.documentElement.lang.startsWith(" + JSONObject.quote(language) + ")"));
        String theme = args.getString("layoutTheme", "light");
        assertTrue("Unknown theme", theme.equals("light") || theme.equals("dark"));
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("The theme control did not apply", 10000,
            () -> ui.truth("document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
        if ("true".equals(args.getString("layoutMaxTextFont"))) {
            ui.tap("[data-droy-font-expander]");
            String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
            for (int step = 0; step < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); step++) {
                String before = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
                ui.tap(increase);
                DeviceUi.await("The font control did not apply", 5000,
                    () -> !before.equals(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()")));
            }
            assertTrue(ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        }
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Display setup did not close settings", 5000, () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private boolean checked() throws Exception {
        return ui.truth("document.querySelector('" + MAIN_SWITCH + "')?.getAttribute('aria-checked')==='true'");
    }

    private String localized(String chinese, String english) {
        return "en".equals(InstrumentationRegistry.getArguments().getString("layoutLanguage")) ? english : chinese;
    }

    private JSONObject readStatus() throws Exception {
        ui.evaluate("window.__memoryTestStatus=null;fetch('/droy/memory',{credentials:'same-origin',cache:'no-store'}).then(r=>r.json()).then(v=>window.__memoryTestStatus=v)");
        DeviceUi.await("The authenticated memory status did not return", 10000, () -> ui.truth("window.__memoryTestStatus!==null"));
        JSONObject response = (JSONObject) ui.evaluate("window.__memoryTestStatus");
        assertTrue("Memory status failed", response.getBoolean("ok"));
        return response.getJSONObject("result");
    }

    private void assertTextFits(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
            + "),r=e.getBoundingClientRect(),w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let n;let fit=true;"
            + "while(n=w.nextNode()){if(!n.textContent.trim())continue;const range=document.createRange();range.selectNodeContents(n);"
            + "for(const x of range.getClientRects())if(x.width&&!(x.left>=r.left-1&&x.right<=r.right+1&&x.top>=r.top-1&&x.bottom<=r.bottom+1))fit=false}"
            + "return {fit,width:r.width,left:r.left,right:r.right,viewport:innerWidth}})()");
        assertTrue("Memory text clips its own container: " + selector + " " + geometry, geometry.getBoolean("fit"));
        assertTrue("Memory text exceeds viewport", geometry.getDouble("left") >= -1 && geometry.getDouble("right") <= geometry.getDouble("viewport") + 1);
    }

    private void capture(String stage) throws Exception {
        awaitVisiblePagePaint();
        if (ownsState) {
            boolean landscape = "true".equals(InstrumentationRegistry.getArguments().getString("layoutLandscape"));
            assertTrue("The screenshot no longer has the requested orientation", ui.truth(landscape ? "innerWidth>innerHeight" : "innerHeight>innerWidth"));
        }
        boolean secureWindow = (activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0;
        // Changing UiAutomation flags reconnects its session and resets frozen
        // rotation. Keep DeviceUi's session and wait for native IME composition.
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Bitmap screenshot = automation.takeScreenshot();
        if (screenshot == null && secureWindow) {
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_MEMORY_VISUAL_UNAVAILABLE " + stage + " FLAG_SECURE; privacy protection preserved\n");
            instrumentation.sendStatus(2, status);
            return;
        }
        assertNotNull(screenshot);
        try {
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            int stableFrames = 0;
            while (stableFrames < 3 && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(120);
                Bitmap next = automation.takeScreenshot();
                assertNotNull(next);
                stableFrames = next.sameAs(screenshot) ? stableFrames + 1 : 0;
                screenshot.recycle(); screenshot = next;
            }
            assertEquals("The native screenshot did not settle", 3, stableFrames);
            File folder = new File(context.getExternalFilesDir(null), "memory-ui-evidence");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("memoryEvidenceLabel", "memory").replaceAll("[^A-Za-z0-9_-]", "_");
            File output = File.createTempFile(label + "-" + stage + "-", ".png", folder);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            JSONObject conditions = (JSONObject) ui.evaluate("({cssWidth:innerWidth,cssHeight:innerHeight,language:document.documentElement.lang,memoryFont:document.querySelector('[data-droy-memory]')?getComputedStyle(document.querySelector('[data-droy-memory]')).fontSize:null,theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            conditions.put("secureWindow", secureWindow);
            conditions.put("fixture", ownsState ? "disposable profile; actual Root rejection; no readiness injection" : "target phone; read-only navigation; existing settings preserved");
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEMORY_EVIDENCE " + output.getAbsolutePath() + "\nDROY_MEMORY_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { screenshot.recycle(); }
    }

    private void awaitVisiblePagePaint() throws Exception {
        android.webkit.WebView web = ui.web();
        boolean[] webVisible = {false};
        instrumentation.runOnMainSync(() -> webVisible[0] = web != null && web.isAttachedToWindow() && web.isShown());
        if (webVisible[0]) { ui.awaitPaint(); return; }
        java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
        android.view.View[] nativePage = {null};
        instrumentation.runOnMainSync(() -> {
            android.view.View page;
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("nativePage");
                field.setAccessible(true); page = (android.view.View) field.get(activity);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            assertNotNull("A hidden WebView must have a visible native page for this screenshot", page);
            assertTrue("The native screenshot page is not attached and visible", page.isAttachedToWindow() && page.isShown());
            nativePage[0] = page;
            page.postOnAnimation(() -> {
                page.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                page.invalidate();
            });
        });
        assertTrue("The visible native page did not commit its screenshot frame", committed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        instrumentation.runOnMainSync(() -> assertTrue("The committed native screenshot page has no visible layout",
            nativePage[0].isShown() && nativePage[0].getWidth() > 0 && nativePage[0].getHeight() > 0));
    }

    @SuppressWarnings("unchecked") private void restorePreferences(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?>) editor.putStringSet(key, (Set<String>) value);
            else throw new AssertionError("Unknown preference type");
        }
        assertTrue("Could not restore preferences", editor.commit());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String relative = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) result.put(relative, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) result.put(relative, "directory");
                else result.put(relative, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
}

package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the shipped DSH UI and origin-scoped Android bridge without calling a model. */
@RunWith(AndroidJUnit4.class)
public final class UiNavigationDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private SharedPreferences preferences;
    private String originalTheme;
    private boolean themeChanged;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks and engine before UI navigation tests", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        if (!ui.nativeUiReady()) assertTrue("A cold launch must cover its unfinished conversation", ui.loadingOverlayShown());
        ui.awaitPage();
        DeviceUi.await("DSH did not synchronize its initial theme", 15000, () -> preferences.contains("themePreference"));
        originalTheme = preferences.getString("themePreference", "system");
        assertTrue("Use a phone-sized test surface", ((Number) ui.evaluate("innerWidth")).intValue() < 1024);
    }

    @After public void tearDown() throws Exception {
        try {
            if (themeChanged && originalTheme != null && RuntimeService.ready && ui != null && ui.web() != null) {
                openGeneral();
                selectTheme(originalTheme);
            }
        } finally {
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("UI navigation test left its automatically started runtime active", 15000, RuntimeService::canManageHistory);
        }
    }

    @Test public void automaticConversationDrawerAndSettingsKeepCurrentDraft() throws Exception {
        assertTrue("Opening the app must start DSH without a launch button", RuntimeService.ready);
        assertFalse("The ready conversation is still behind its loading cover", ui.loadingOverlayShown());
        assertTrue("The visible conversation has not completed mobile readiness", ui.truth("document.documentElement.dataset.droyUiReady === 'true'"));
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            for (String removed : new String[]{"对话", "练习", "能力", "设置", "启动引擎", "停止任务"})
                assertNull("Obsolete native navigation remains: " + removed, DeviceUi.findButton(decor, removed));
        });
        assertFalse("Sidebar must start hidden", drawerOpen());
        assertTrue("The top-left menu must be available", ui.truth("!!document.querySelector('[data-droy-menu]')"));
        WebView original = ui.web();
        String draft = "Unsent Droy navigation fixture draft";
        DeviceUi.await("The DSH composer did not mount", 10000,
                () -> ui.truth("!!document.querySelector('[data-composer-input][contenteditable=\"true\"]')"));
        assertComposerVisible();
        ui.tap("[data-composer-input]");
        DeviceUi.await("A deliberate composer tap did not open Android's keyboard", 10000, ui::keyboardVisible);
        instrumentation.sendStringSync(draft);
        DeviceUi.await("DSH did not retain the typed draft", 5000, () -> composerText().contains(draft));
        ui.evaluate("document.querySelector('[data-composer-input]')?.blur()");
        ui.hideKeyboard();

        ui.click("[data-droy-menu]");
        DeviceUi.await("Menu did not open the conversation drawer", 5000, this::drawerOpen);
        ui.click("[data-droy-backdrop]");
        DeviceUi.await("Backdrop did not close the drawer", 5000, () -> !drawerOpen());
        ui.click("[data-droy-menu]");
        instrumentation.runOnMainSync(activity::onBackPressed);
        DeviceUi.await("Android Back did not close the drawer", 5000, () -> !drawerOpen());

        ui.click("[data-droy-menu]");
        DeviceUi.await("The drawer did not open before focusing its settings launcher", 5000, this::drawerOpen);
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The sidebar account menu did not open full-screen settings", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertFalse("Opening settings must close the underlying conversation drawer", drawerOpen());
        ui.click("[data-droy-settings-section='droy-permissions']");
        DeviceUi.await("The permission section did not load", 10000,
                () -> ui.truth("document.querySelectorAll('[data-droy-preference]').length === 17"));
        for (String section : new String[]{"general", "models", "droy-permissions"})
            assertTrue("Missing DSH settings section: " + section,
                    ui.truth("!!document.querySelector('[data-droy-settings-section=\"" + section + "\"]')"));
        assertEquals("Permission page must expose every product capability", 17,
                ((Number) ui.evaluate("document.querySelectorAll('[data-droy-preference]').length")).intValue());
        instrumentation.runOnMainSync(activity::onBackPressed);
        DeviceUi.await("Android Back did not close DSH settings", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-permissions]')"));
        assertFalse("Returning from settings must reach the conversation in one step", drawerOpen());
        assertConversationFocusRestored(false);
        assertSame("Settings navigation replaced the current WebView", original, ui.web());
        assertComposerVisible();
        assertTrue("Settings navigation discarded the current draft", composerText().contains(draft));

        openGeneral();
        ui.click("[data-droy-action='openDiagnostics']");
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> assertNotNull("Droy diagnostics did not open",
                DeviceUi.findButton(activity.getWindow().getDecorView(), "停止任务和引擎")));
        instrumentation.runOnMainSync(activity::onBackPressed);
        assertSame("Returning from native diagnostics reloaded the conversation", original, ui.web());
        assertTrue("Returning from diagnostics lost the current draft", composerText().contains(draft));

        openGeneral();
        ui.click("[data-droy-action='openDiagnostics']");
        ui.nativeClick("停止任务和引擎");
        DeviceUi.await("Stopping from About & diagnostics did not release the runtime", 15000, RuntimeService::canManageHistory);
        instrumentation.runOnMainSync(activity::onBackPressed);
        SystemClock.sleep(1500);
        assertTrue("Returning to conversation automatically restarted a stopped engine", RuntimeService.canManageHistory());
        assertNull("Returning to conversation recreated the runtime service", RuntimeService.instance);
        assertFalse("A stopped conversation returned to ready state", RuntimeService.ready);
        instrumentation.runOnMainSync(() -> {
            android.widget.Button start = DeviceUi.findButton(activity.getWindow().getDecorView(), "启动并进入对话");
            assertNotNull("Stopped conversation has no explicit restart entry", start);
            assertTrue("The explicit restart entry must be visible", start.isShown());
        });
        assertFalse("An explicitly stopped engine must not leave a loading cover", ui.loadingOverlayShown());
    }

    @Test public void keyboardNavigationStillShowsFocusAfterTouchHighlightsAreRemoved() throws Exception {
        ui.hideKeyboard();
        ui.tap("[data-droy-menu]");
        DeviceUi.await("The real menu button did not open the drawer", 5000, this::drawerOpen);
        ui.pressBack();
        DeviceUi.await("The real system Back did not close the drawer", 5000, () -> !drawerOpen());
        assertTrue("Android's default blue touch highlight remains on the menu", ui.truth("(()=>{"
                + "const menu=document.querySelector('[data-droy-menu]'),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');"
                + "ctx.fillStyle=getComputedStyle(menu).webkitTapHighlightColor;ctx.fillRect(0,0,1,1);return ctx.getImageData(0,0,1,1).data[3]===0})()"));
        boolean reachedMenu = false;
        for (int step = 0; step < 32; step++) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB);
            if (ui.truth("document.activeElement===document.querySelector('[data-droy-menu]')")) {
                reachedMenu = true;
                break;
            }
        }
        assertTrue("Actual Android Tab navigation cannot reach the main menu control", reachedMenu);
        assertTrue("A keyboard-focused menu control must retain a visible focus indicator", ui.truth("(()=>{"
                + "const menu=document.querySelector('[data-droy-menu]'),style=getComputedStyle(menu);"
                + "return menu.matches(':focus-visible')&&((style.outlineStyle!=='none'&&parseFloat(style.outlineWidth)>=1)||style.boxShadow!=='none')})()"));
    }

    @Test public void drawerAnimationKeepsConversationGeometryStable() throws Exception {
        assertFalse(drawerOpen());
        for (boolean opening : new boolean[]{true, false}) {
            ui.evaluate("(()=>{const center=document.querySelector('[data-droy-center]');const composer=document.querySelector('[data-composer-input]');"
                    + "const rect=e=>{const r=e.getBoundingClientRect();return {left:r.left,width:r.width}};"
                    + "window.__droyMotionFixture={initial:{center:rect(center),composer:rect(composer)},frames:[],done:false,"
                    + "sidebarTransition:getComputedStyle(document.querySelector('[data-droy-sidebar]')).transitionProperty};"
                    + "let remaining=18;const sample=time=>{const fixture=window.__droyMotionFixture;fixture.frames.push({time,center:rect(center),composer:rect(composer)});"
                    + "if(--remaining>0)requestAnimationFrame(sample);else fixture.done=true};requestAnimationFrame(sample)})()");
            ui.click(opening ? "[data-droy-menu]" : "[data-droy-backdrop]");
            ui.evaluate("window.__droyMotionFixture.frameTransition=getComputedStyle(document.querySelector('[data-droy-frame]')).transitionProperty");
            DeviceUi.await("The drawer motion sample did not finish", 5000,
                    () -> ui.truth("window.__droyMotionFixture.done"));
            JSONObject sample = (JSONObject) ui.evaluate("window.__droyMotionFixture");
            String forbidden = "(?s).*(?:\\ball\\b|width|height|grid|left|right|margin|padding).*";
            assertFalse("The drawer frame must not animate layout properties", sample.getString("frameTransition").matches(forbidden));
            assertFalse("The drawer must animate through compositing", sample.getString("sidebarTransition").matches(forbidden));
            JSONArray frames = sample.getJSONArray("frames");
            for (int index = 0; index < frames.length(); index++) for (String target : new String[]{"center", "composer"}) {
                JSONObject initial = sample.getJSONObject("initial").getJSONObject(target);
                JSONObject frame = frames.getJSONObject(index).getJSONObject(target);
                assertEquals("Drawer movement changed " + target + " width", initial.getDouble("width"), frame.getDouble("width"), 1);
                assertEquals("Drawer movement shifted " + target, initial.getDouble("left"), frame.getDouble("left"), 1);
            }
            assertEquals(opening, drawerOpen());
            ui.evaluate("delete window.__droyMotionFixture");
        }
    }

    @Test public void fullScreenSettingsFillTheWindowAndKeepControlsInsideSafeInsets() throws Exception {
        assertNativeWebViewFillsWindow();
        assertInsetsMatchAndroid(false);
        assertSafeControl("[data-droy-menu]", false);
        ui.evaluate("window.__droySettingsReturnComposer=document.querySelector('[data-composer-input]')");
        ui.click("[data-droy-menu]");
        DeviceUi.await("The conversation drawer did not open before entering settings", 5000, this::drawerOpen);
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The sidebar account menu did not open full-screen settings", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertFalse("Opening settings must close the underlying conversation drawer", drawerOpen());
        ui.click("[data-droy-settings-section='general']");
        DeviceUi.await("The general settings section did not mount", 10000,
                () -> ui.truth("!!document.querySelector('.droy-assistant-name-input') && !document.querySelector('.droy-assistant-name-input').disabled"));
        assertSettingsGeometry();
        assertSafeControl("[data-droy-settings-back]", false);
        for (String section : new String[]{"models", "droy-permissions", "general"}) {
            String selector = "[data-droy-settings-section='" + section + "']";
            ui.visibleWebBounds(selector);
            assertSafeControl(selector, false);
            ui.click(selector);
            assertSettingsGeometry();
        }
        ui.openPermissions();
        assertSettingControlScrollsIntoSafeArea("[data-droy-permission='directory']");
        assertSettingControlScrollsIntoSafeArea(".droy-advanced > summary");
        ui.click(".droy-advanced > summary");
        DeviceUi.await("Advanced permission actions did not expand", 5000,
                () -> ui.truth("!!document.querySelector('details.droy-advanced[open]')"));
        assertSettingControlScrollsIntoSafeArea("details.droy-advanced[open] [data-droy-action='openSettingRestores']");
        ui.evaluate("(()=>{window.__droyFocusFixture=[];window.__droyFocusRecorder=event=>{const e=event.target;"
                + "if(!(e instanceof Element))return;const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                + "window.__droyFocusFixture.push({tag:e.tagName,menu:e.matches('[data-droy-menu]'),"
                + "composer:!!e.closest('[data-composer-input]'),preference:!!e.closest('[data-droy-preference]'),"
                + "hidden:!!e.closest('[inert]')||s.visibility==='hidden'||!r.width||!r.height});"
                + "if(window.__droyFocusFixture.length>16)window.__droyFocusFixture.shift()};"
                + "document.addEventListener('focusin',window.__droyFocusRecorder,true)})()");
        ui.click("[data-droy-settings-back]");
        DeviceUi.await("Settings header Back did not return directly to the conversation", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')") && !drawerOpen());
        assertConversationFocusRestored(true);
        assertComposerVisible();
    }

    @Test public void keyboardInsetIsConsumedOnceWithoutResizingTheWebView() throws Exception {
        Rect initialWindow = assertNativeWebViewFillsWindow();
        try {
            ui.showKeyboard("[data-composer-input]");
            assertInsetsMatchAndroid(true);
            assertEquals("IME must not resize or move the full-window WebView", initialWindow, assertNativeWebViewFillsWindow());
            assertSafeControl("[data-composer-card]", true);
            assertBottomPaddingUsesOneInset("[data-droy-center]");
            if (ui.truth("!!document.querySelector('[data-conversation-content][data-content-phase=hero]')"))
                assertTrue("The empty composer must sit just above the keyboard", ui.truth("(()=>{"
                    + "const root=document.documentElement,card=document.querySelector('[data-composer-card]');"
                    + "const ime=parseFloat(getComputedStyle(root).getPropertyValue('--droy-ime-bottom'))||0;"
                    + "const gap=innerHeight-ime-card.getBoundingClientRect().bottom;return gap>=-1&&gap<=12})()"));
            ui.hideKeyboard();
            openGeneral();
            ui.showKeyboard(".droy-assistant-name-input");
            assertInsetsMatchAndroid(true);
            assertEquals("Settings keyboard resized the full-window WebView", initialWindow, assertNativeWebViewFillsWindow());
            assertSettingsGeometry();
            assertBottomPaddingUsesOneInset("[data-droy-settings-panel]");
            ui.evaluate("document.querySelector('.droy-assistant-name-input').scrollIntoView({block:'center',behavior:'instant'})");
            DeviceUi.await("The focused settings input remained behind the IME", 5000, () -> controlIsSafe(".droy-assistant-name-input", true));
        } finally {
            ui.evaluate("document.activeElement?.blur()");
            ui.hideKeyboard();
        }
        assertInsetsMatchAndroid(false);
        assertEquals(initialWindow, assertNativeWebViewFillsWindow());
    }

    @Test public void settingsForegroundPaintsBehindBothSystemBars() throws Exception {
        openGeneral();
        final int fixtureColor = Color.rgb(39, 122, 205);
        // Cover only the real settings panel. Its existing geometry and clipping
        // remain intact, so old modal margins or native inset padding fail this
        // screenshot check. No screenshot is saved or attached to test output.
        ui.evaluate("(()=>{const panel=document.querySelector('[data-droy-settings-panel]');"
                + "window.__droyPanelPosition=panel.style.position;if(getComputedStyle(panel).position==='static')panel.style.position='relative';"
                + "const cover=document.createElement('div');cover.id='droy-system-bar-fixture';"
                + "cover.style.cssText='position:absolute;inset:0;z-index:2147483647;background:rgb(39,122,205);pointer-events:none';"
                + "panel.appendChild(cover);window.__droyCaptureReady=false;"
                + "requestAnimationFrame(()=>requestAnimationFrame(()=>window.__droyCaptureReady=true))})()");
        Bitmap captured = null;
        try {
            DeviceUi.await("The synthetic settings surface did not paint", 5000, () -> ui.truth("window.__droyCaptureReady"));
            instrumentation.waitForIdleSync();
            SystemClock.sleep(150);
            captured = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull("Android did not capture the full-screen settings surface", captured);
            assertEquals("The actual settings surface is not visible", fixtureColor,
                    captured.getPixel(captured.getWidth() / 2, captured.getHeight() / 2));
            for (int x : new int[]{captured.getWidth() / 4, captured.getWidth() * 3 / 4}) {
                assertEquals("Settings did not paint continuously behind the status bar", fixtureColor, captured.getPixel(x, 2));
                assertEquals("Settings did not paint continuously behind the navigation bar", fixtureColor,
                        captured.getPixel(x, captured.getHeight() - 3));
            }
        } finally {
            if (captured != null) captured.recycle();
            ui.evaluate("(()=>{document.getElementById('droy-system-bar-fixture')?.remove();"
                    + "const panel=document.querySelector('[data-droy-settings-panel]');if(panel)panel.style.position=window.__droyPanelPosition;"
                    + "delete window.__droyPanelPosition;delete window.__droyCaptureReady})()");
        }
    }

    @Test public void permissionControlsUseTheDshGeneralControlStyles() throws Exception {
        openGeneral();
        String nativeSwitch = "[role='switch'][aria-label='显示代码工作视图'],[role='switch'][aria-label='Show coding view']";
        String[] switchProperties = {"width", "height", "border-radius", "border-width", "padding", "font-family", "font-size", "transition-property"};
        JSONObject reference = controlStyle(nativeSwitch, switchProperties);
        assertEquals("General DNS must use the original DSH switch style", reference.toString(),
                controlStyle("[role='switch'][aria-label='浏览器加密 DNS']", switchProperties).toString());
        String[] buttonProperties = {"height", "border-radius", "border-width", "padding", "font-family", "font-size", "font-weight", "line-height"};
        JSONObject generalButton = controlStyle("[data-droy-action='openDiagnostics']", buttonProperties);
        ui.openPermissions();
        assertEquals("Permissions must use the original DSH switch style", reference.toString(),
                controlStyle("[data-droy-preference='allowObserve'] [role='switch']", switchProperties).toString());
        assertEquals("Permission actions must match the general settings buttons", generalButton.toString(),
                controlStyle("[data-droy-action='requestPermission'][data-droy-permission='contacts']", buttonProperties).toString());
    }

    @Test public void userSystemScreenshotCanCaptureTheAppWindow() throws Exception {
        final int fixtureColor = Color.rgb(39, 122, 205);
        View cover = new View(activity);
        // Cover the real page before invoking system capture so test output
        // cannot contain conversations, names or model credentials.
        instrumentation.runOnMainSync(() -> {
            assertEquals("Manual screenshots must be allowed", 0, activity.getWindow().getAttributes().flags
                & android.view.WindowManager.LayoutParams.FLAG_SECURE);
            cover.setBackgroundColor(fixtureColor);
            ((android.view.ViewGroup) activity.getWindow().getDecorView()).addView(cover,
                new android.view.ViewGroup.LayoutParams(-1, -1));
        });
        Bitmap captured = null;
        try {
            instrumentation.waitForIdleSync();
            SystemClock.sleep(200);
            captured = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull("Android did not produce a system screenshot", captured);
            assertEquals("The app window was blanked in the system screenshot", fixtureColor,
                captured.getPixel(captured.getWidth() / 2, captured.getHeight() / 2));
        } finally {
            if (captured != null) captured.recycle();
            instrumentation.runOnMainSync(() -> ((android.view.ViewGroup) cover.getParent()).removeView(cover));
        }
    }

    @Test public void dshThemeIsTheOnlyThemeControlAndNativePagesFollowIt() throws Exception {
        openGeneral();
        assertEquals("The general page should contain one DNS control", 1,
                ((Number) ui.evaluate("document.querySelectorAll('[aria-label=\"浏览器加密 DNS\"]').length")).intValue());
        selectTheme("dark");
        assertShellMatchesDsh();
        assertTrue("DSH body did not enter the dark palette", ui.truth("document.body.hasAttribute('data-ds-dark-theme')"));
        assertFalse("Legacy native theme must no longer override DSH", preferences.contains("darkTheme"));
        WebView original = ui.web();
        ui.click("[data-droy-action='openDiagnostics']");
        instrumentation.waitForIdleSync();
        AtomicBoolean lightStatusBars = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> lightStatusBars.set((activity.getWindow().getInsetsController().getSystemBarsAppearance()
                & android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0));
        assertFalse("Native diagnostics retained light status icons after choosing dark", lightStatusBars.get());
        instrumentation.runOnMainSync(activity::onBackPressed);
        assertSame(original, ui.web());
        assertTrue("Native navigation overwrote the DSH theme", ui.truth("document.documentElement.getAttribute('data-ds-theme-source') === 'dark'"));
        openGeneral();
        selectTheme("light");
        assertShellMatchesDsh();
        assertFalse("DSH light palette was not applied", ui.truth("document.body.hasAttribute('data-ds-dark-theme')"));
        selectTheme("system");
        assertShellMatchesDsh();
        assertEquals("system", ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')"));
    }

    @Test public void productBridgeRejectsChildFramesAndForeignDocuments() throws Exception {
        String originalName = preferences.getString("assistantName", "DroyAgent");
        assertTrue("The trusted top-level document needs its native bridge", ui.truth("typeof DroyNative?.postMessage === 'function'"));
        String request = new JSONObject().put("id", "nested-frame-fixture").put("action", "setPreference")
                .put("args", new JSONObject().put("key", "assistantName").put("value", "Nested frame fixture")).toString();
        String html = "<script>if(typeof DroyNative!=='undefined')DroyNative.postMessage(" + JSONObject.quote(request)
                + ");parent.__droyNestedFrameCompleted=true;</script>";
        ui.evaluate("(()=>{window.__droyNestedFrameCompleted=false;const frame=document.createElement('iframe');frame.id='droy-origin-fixture';frame.srcdoc="
                + JSONObject.quote(html) + ";document.body.appendChild(frame)})()");
        DeviceUi.await("The child-frame fixture did not execute", 5000, () -> ui.truth("window.__droyNestedFrameCompleted === true"));
        SystemClock.sleep(300);
        assertEquals("A child frame changed a product preference", originalName, preferences.getString("assistantName", "DroyAgent"));
        ui.evaluate("document.getElementById('droy-origin-fixture').remove()");

        WebView view = ui.web();
        instrumentation.runOnMainSync(() -> view.loadDataWithBaseURL("https://untrusted.example/",
                "<!doctype html><html><body id='foreign-document'>synthetic foreign origin</body></html>", "text/html", "UTF-8", null));
        DeviceUi.await("The foreign document fixture did not load", 5000,
                () -> ui.truth("document.body?.id === 'foreign-document'"));
        assertTrue("A foreign origin was given the privileged native bridge", ui.truth("typeof window.DroyNative === 'undefined'"));
        assertEquals(originalName, preferences.getString("assistantName", "DroyAgent"));
        ui.invoke("discardWebView");
        ui.invoke("showChat");
        ui.awaitPage();
    }

    private void openGeneral() throws Exception {
        ui.awaitPage();
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("DSH general settings did not open", 10000,
                () -> ui.truth("!!document.querySelector('.droy-assistant-name-input') && !document.querySelector('.droy-assistant-name-input').disabled"));
    }

    private void selectTheme(String theme) throws Exception {
        themeChanged = true;
        String label = switch (theme) { case "dark" -> "深色"; case "light" -> "浅色"; default -> "跟随系统"; };
        String english = switch (theme) { case "dark" -> "Dark"; case "light" -> "Light"; default -> "System"; };
        assertTrue("Missing original DSH theme control: " + theme, ui.truth("(()=>{const e=[...document.querySelectorAll('button[aria-pressed]')].find(b=>["
                + JSONObject.quote(label) + "," + JSONObject.quote(english) + "].includes(b.textContent.trim()));if(!e)return false;e.click();return true})()"));
        DeviceUi.await("Native shell did not follow DSH theme " + theme, 10000,
                () -> theme.equals(preferences.getString("themePreference", ""))
                        && ui.truth("document.documentElement.getAttribute('data-ds-theme-source') === " + JSONObject.quote(theme)));
    }

    private void assertComposerVisible() throws Exception {
        JSONObject bounds = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-composer-input]');if(!e)return {};const r=e.getBoundingClientRect();return {left:r.left,right:r.right,width:r.width,height:r.height,viewport:innerWidth,interactive:!e.closest('[inert]')}})()");
        assertTrue("The DSH composer is collapsed or outside the viewport: " + bounds,
                bounds.optDouble("width", 0) >= 200 && bounds.optDouble("height", 0) > 0
                        && bounds.optDouble("left", -1) >= 0
                        && bounds.optDouble("right", Double.POSITIVE_INFINITY) <= bounds.optDouble("viewport", 0)
                        && bounds.optBoolean("interactive", false));
    }

    private Rect assertNativeWebViewFillsWindow() {
        Rect result = new Rect();
        WebView current = ui.web();
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            int[] origin = new int[2], webOrigin = new int[2];
            decor.getLocationOnScreen(origin);
            current.getLocationOnScreen(webOrigin);
            Rect window = new Rect(origin[0], origin[1], origin[0] + decor.getWidth(), origin[1] + decor.getHeight());
            result.set(webOrigin[0], webOrigin[1], webOrigin[0] + current.getWidth(), webOrigin[1] + current.getHeight());
            assertEquals("WebView must draw behind the status, navigation and cutout regions", window, result);
            View root = (View) current.getParent().getParent();
            assertEquals("Native root retained left inset padding", 0, root.getPaddingLeft());
            assertEquals("Native root retained top inset padding", 0, root.getPaddingTop());
            assertEquals("Native root retained right inset padding", 0, root.getPaddingRight());
            assertEquals("Native root retained bottom inset padding", 0, root.getPaddingBottom());
            assertEquals("Native resize and web safe-area padding must not both consume the IME",
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                    activity.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        });
        return result;
    }

    private void assertInsetsMatchAndroid(boolean keyboard) throws Exception {
        DeviceUi.await("Web safe-area/IME dimensions did not match the Android window", 10000, () -> {
            int[] physical = new int[7];
            WebView current = ui.web();
            instrumentation.runOnMainSync(() -> {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                physical[0] = bars.top; physical[1] = bars.right; physical[2] = bars.bottom; physical[3] = bars.left;
                physical[4] = insets.isVisible(WindowInsets.Type.ime()) ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
                physical[5] = current.getWidth(); physical[6] = insets.isVisible(WindowInsets.Type.ime()) ? 1 : 0;
            });
            if ((physical[6] == 1) != keyboard) return false;
            JSONObject css = (JSONObject) ui.evaluate("(()=>{const s=getComputedStyle(document.documentElement);"
                    + "const px=n=>parseFloat(s.getPropertyValue(n))||0;return {width:innerWidth,values:"
                    + "['--droy-safe-top','--droy-safe-right','--droy-safe-bottom','--droy-safe-left','--droy-ime-bottom'].map(px)}})()");
            double scale = physical[5] / css.getDouble("width");
            JSONArray values = css.getJSONArray("values");
            for (int index = 0; index < 5; index++)
                if (Math.abs(physical[index] / scale - values.getDouble(index)) > 1) return false;
            return true;
        });
    }

    private void assertSettingsGeometry() throws Exception {
        JSONObject shape = (JSONObject) ui.evaluate("(()=>{const p=document.querySelector('[data-droy-settings-panel]');"
                + "if(!p)return {};const r=p.getBoundingClientRect(),s=getComputedStyle(p);"
                + "const m=document.querySelector('[data-droy-settings-mask]');"
                + "const o=document.querySelector('[data-droy-settings-options]')?.getBoundingClientRect();"
                + "const root=getComputedStyle(document.documentElement);const px=n=>parseFloat(root.getPropertyValue(n))||0;return {"
                + "left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:innerWidth,height:innerHeight,"
                + "margin:[s.marginTop,s.marginRight,s.marginBottom,s.marginLeft],radius:s.borderRadius,shadow:s.boxShadow,"
                + "maskVisible:!!m&&m.getClientRects().length>0&&getComputedStyle(m).visibility!=='hidden',"
                + "optionsBottom:o?.bottom,safeBottom:Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))}})()");
        assertTrue("The full-screen settings panel did not mount", shape.has("width"));
        assertEquals("Settings retained a left modal gap", 0, shape.getDouble("left"), 1);
        assertEquals("Settings retained a top modal gap", 0, shape.getDouble("top"), 1);
        assertEquals("Settings retained a right modal gap", shape.getDouble("width"), shape.getDouble("right"), 1);
        assertEquals("Settings retained a bottom modal gap", shape.getDouble("height"), shape.getDouble("bottom"), 1);
        JSONArray margins = shape.getJSONArray("margin");
        for (int index = 0; index < margins.length(); index++) assertEquals("Full-screen settings must have no outer margin", "0px", margins.getString(index));
        assertEquals("Full-screen settings retained outer rounded corners", "0px", shape.getString("radius"));
        assertEquals("Full-screen settings retained a surrounding shadow", "none", shape.getString("shadow"));
        assertFalse("Full-screen settings retained the desktop surrounding mask", shape.getBoolean("maskVisible"));
        assertTrue("Settings content extends below the safe bottom edge",
                shape.getDouble("optionsBottom") <= shape.getDouble("height") - shape.getDouble("safeBottom") + 1);
    }

    private void assertSafeControl(String selector, boolean keyboard) throws Exception {
        assertTrue("The control overlaps a system bar, cutout or keyboard: " + selector, controlIsSafe(selector, keyboard));
    }

    private void assertConversationFocusRestored(boolean allowOriginalComposer) throws Exception {
        try {
            DeviceUi.await("Settings did not restore visible conversation focus", 5000,
                    () -> ui.truth("(()=>{const e=document.activeElement,r=e?.getBoundingClientRect(),s=e?getComputedStyle(e):null;"
                            + "const target=e===document.querySelector('[data-droy-menu]')"
                            + (allowOriginalComposer ? "||e===window.__droySettingsReturnComposer" : "")
                            + ";return target&&!!e?.isConnected&&!e.closest('[inert]')&&!document.getElementById('root')?.inert"
                            + "&&r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'})()"));
        } catch (AssertionError failure) {
            Object focus = ui.evaluate("(()=>{const e=document.activeElement,r=e?.getBoundingClientRect(),s=e?getComputedStyle(e):null;return {"
                    + "tag:e?.tagName,menu:e?.matches('[data-droy-menu]'),composer:!!e?.closest('[data-composer-input]'),"
                    + "preference:!!e?.closest('[data-droy-preference]'),connected:!!e?.isConnected,inert:!!e?.closest('[inert]'),"
                    + "rootInert:!!document.getElementById('root')?.inert,visibility:s?.visibility,"
                    + "rect:r?{left:r.left,top:r.top,width:r.width,height:r.height}:null,recent:(window.__droyFocusFixture||[]).slice(-8)}})()");
            throw new AssertionError(failure.getMessage() + "; safe active focus=" + focus, failure);
        } finally {
            ui.evaluate("(()=>{if(window.__droyFocusRecorder)document.removeEventListener('focusin',window.__droyFocusRecorder,true);"
                    + "delete window.__droyFocusRecorder;delete window.__droyFocusFixture;delete window.__droySettingsReturnComposer})()");
        }
    }

    private void assertSettingControlScrollsIntoSafeArea(String selector) throws Exception {
        assertTrue("The settings action did not mount: " + selector, ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!e||!e.getClientRects().length||e.disabled||e.closest('details:not([open])')&&!e.matches('summary'))return false;"
                + "e.scrollIntoView({block:'end',behavior:'instant'});return true})()"));
        try {
            DeviceUi.await("The settings action remained below the safe content area", 5000,
                    () -> ui.truth("(()=>{const c=document.querySelector('[data-droy-settings-options]');"
                            + "const e=document.querySelector(" + JSONObject.quote(selector) + ");"
                            + "const r=e.getBoundingClientRect(),b=c.getBoundingClientRect();return r.top>=b.top-1&&r.bottom<=b.bottom+1})()"));
        } catch (AssertionError failure) {
            Object geometry = ui.evaluate("(()=>{const c=document.querySelector('[data-droy-settings-options]');"
                    + "const e=document.querySelector(" + JSONObject.quote(selector) + ");const rect=e=>{if(!e)return null;const r=e.getBoundingClientRect();"
                    + "return {top:r.top,bottom:r.bottom,width:r.width,height:r.height}};"
                    + "const root=getComputedStyle(document.documentElement);return {control:rect(e),container:rect(c),"
                    + "closedDetails:!!e?.closest('details:not([open])'),display:e?getComputedStyle(e).display:null,"
                    + "visibility:e?getComputedStyle(e).visibility:null,scrollTop:c?.scrollTop,scrollHeight:c?.scrollHeight,clientHeight:c?.clientHeight,"
                    + "viewportHeight:innerHeight,safeBottom:root.getPropertyValue('--droy-safe-bottom'),imeBottom:root.getPropertyValue('--droy-ime-bottom')}})()");
            throw new AssertionError(failure.getMessage() + ": " + selector + "; safe geometry=" + geometry, failure);
        }
    }

    private boolean controlIsSafe(String selector, boolean keyboard) throws Exception {
        return ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");if(!e)return false;"
                + "const r=e.getBoundingClientRect(),s=getComputedStyle(document.documentElement),px=n=>parseFloat(s.getPropertyValue(n))||0;"
                + "const bottom=Math.max(px('--droy-safe-bottom')," + (keyboard ? "px('--droy-ime-bottom')" : "0") + ");"
                + "return r.width>0&&r.height>0&&r.top>=px('--droy-safe-top')-1&&r.left>=px('--droy-safe-left')-1"
                + "&&r.right<=innerWidth-px('--droy-safe-right')+1&&r.bottom<=innerHeight-bottom+1})()");
    }

    private void assertBottomPaddingUsesOneInset(String selector) throws Exception {
        JSONObject padding = (JSONObject) ui.evaluate("(()=>{const s=getComputedStyle(document.documentElement),px=n=>parseFloat(s.getPropertyValue(n))||0;"
                + "return {actual:parseFloat(getComputedStyle(document.querySelector(" + JSONObject.quote(selector) + ")).paddingBottom),"
                + "expected:Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))}})()");
        assertEquals("The content consumed both navigation and keyboard bottom insets", padding.getDouble("expected"), padding.getDouble("actual"), 1);
    }

    private JSONObject controlStyle(String selector, String[] properties) throws Exception {
        JSONArray names = new JSONArray();
        for (String property : properties) names.put(property);
        Object value = ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");if(!e)return null;const style=getComputedStyle(e);"
                + "return Object.fromEntries(" + names + ".map(name=>[name,style.getPropertyValue(name)]))})()");
        assertTrue("Missing settings control: " + selector, value instanceof JSONObject);
        return (JSONObject) value;
    }

    private void assertShellMatchesDsh() throws Exception {
        JSONArray rgb = (JSONArray) ui.evaluate("(()=>{const e=document.createElement('span');e.style.backgroundColor='var(--dsw-alias-bg-base)';"
                + "document.body.appendChild(e);const color=getComputedStyle(e).backgroundColor;e.remove();return color.match(/[0-9.]+/g).map(Number)})()");
        int expected = Color.rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2));
        DeviceUi.await("System insets did not match the DSH conversation palette", 10000, () -> {
            AtomicBoolean matches = new AtomicBoolean();
            WebView current = ui.web();
            instrumentation.runOnMainSync(() -> {
                View root = (View) current.getParent().getParent();
                matches.set(root.getBackground() instanceof ColorDrawable color && color.getColor() == expected
                        && activity.getWindow().getStatusBarColor() == Color.TRANSPARENT
                        && activity.getWindow().getNavigationBarColor() == Color.TRANSPARENT
                        && !activity.getWindow().isStatusBarContrastEnforced()
                        && !activity.getWindow().isNavigationBarContrastEnforced());
            });
            return matches.get();
        });
    }

    private boolean drawerOpen() throws Exception { return ui.truth("!!document.querySelector('[data-droy-drawer-open=\"true\"]')"); }
    private String composerText() throws Exception { return String.valueOf(ui.evaluate("document.querySelector('[data-composer-input]')?.textContent || ''")); }
}

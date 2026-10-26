package io.droyagent.app;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.WebView;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real brand surfaces and trusted Android input in a disposable emulator profile. */
@RunWith(AndroidJUnit4.class)
public final class BrandingUiDeviceTest {
    private static final String[] KEYS = {"assistantName", "brandSidebarLabel", "brandWelcomeTemplate", "brandComposerTemplate", "brandHeroBadge",
        "petId", "petEnabled", "petExternalDisplay", "petInternalDisplay", "backgroundRuntimeEnabled",
        "themePreference", "themeColorScheme", "themeBackgroundColor", "resetTheme", "darkTheme"};
    private static final String LAUNCHER = "[data-droy-sidebar] [data-droy-settings-launcher]";
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private Instrumentation instrumentation;
    private String windowSnapshot = "No accessibility windows sampled";
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private File home, preserved;
    private boolean owned, hadHome, launched;
    private Map<String, String> originalFiles;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Brand fixtures require a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Finish existing tasks and stop the engine before brand UI tests", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> current = preferences.getAll();
        for (String key : KEYS) if (current.containsKey(key)) savedPreferences.put(key, current.get(key));
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "droy-branding-home-backup-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        owned = true;
        assertTrue("Could not create the disposable brand profile", home.mkdirs());
        SharedPreferences.Editor edit = preferences.edit();
        for (String key : KEYS) edit.remove(key);
        assertTrue(edit.putString("assistantName", "Droy").putString("petId", "default").putBoolean("petEnabled", false)
            .putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").putBoolean("backgroundRuntimeEnabled", false).commit());
        launched = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        awaitFreshPage();
        Bundle args = InstrumentationRegistry.getArguments();
        if (args.containsKey("layoutTheme") || "true".equals(args.getString("layoutMaxTextFont"))) {
            openGeneral();
            String theme = args.getString("layoutTheme", "light");
            assertTrue("Unknown layoutTheme", theme.equals("light") || theme.equals("dark"));
            ui.tap("[data-droy-theme-choice=" + theme + "]");
            DeviceUi.await("The real theme control did not apply", 10000,
                () -> theme.equals(preferences.getString("themePreference", ""))
                    && ui.truth("document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
            if ("true".equals(args.getString("layoutMaxTextFont"))) {
                ui.tap("[data-droy-font-expander]");
                String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
                for (int step = 0; step < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); step++) {
                    String before = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
                    ui.tap(increase);
                    DeviceUi.await("The text font touch did not apply", 5000,
                        () -> !before.equals(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()")));
                }
                assertTrue("The font control did not reach its maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
            }
            closeSettings();
        }
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            if (launched) {
                try { if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish); }
                finally {
                    instrumentation.runOnMainSync(RuntimeService::requestStop);
                    DeviceUi.await("Brand UI cleanup left an engine active", 20000, RuntimeService::canManageHistory);
                    PetOverlay.runtimeStopped();
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            assertTrue("Keep the original home backup until the fixture engine stops", RuntimeService.canManageHistory());
            HistoryData.deleteTree(home.toPath());
            if (hadHome) assertTrue("Could not restore the original DSH home", preserved.renameTo(home));
            assertEquals("Brand UI tests changed the original DSH data", originalFiles, snapshot(home.toPath()));
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : KEYS) {
                Object value = savedPreferences.get(key);
                if (value instanceof String text) restore.putString(key, text);
                else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                else if (value instanceof Integer number) restore.putInt(key, number);
                else if (value instanceof Float number) restore.putFloat(key, number);
                else restore.remove(key);
            }
            assertTrue("Could not restore brand preferences", restore.commit());
            owned = false;
        }
    }

    @Test public void sidebarHeaderContainsOnlyAlignedLogoAndName() throws Exception {
        assertDrawerBrand("Droy");
        capture("sidebar-header");
        ui.tap("[data-droy-drawer-close]");
        DeviceUi.await("The sidebar close control did not return to chat", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
    }

    @Test public void emptyComposerDocksAboveKeyboardAndReturnsToItsCenteredPosition() throws Exception {
        assertTrue("Use the fresh empty home for the composer test", ui.truth("!!document.querySelector('[data-droy-composer-hero]')"));
        ui.hideKeyboard();
        double originalBottom = ((Number) ui.evaluate("document.querySelector('[data-composer-card]').getBoundingClientRect().bottom")).doubleValue();
        assertTrue("The empty input should start above the bottom of the screen", ui.truth("(()=>{const r=document.querySelector('[data-composer-card]').getBoundingClientRect();return r.bottom<innerHeight-80&&r.top>0})()"));
        capture("composer-centered");
        try {
            ui.showKeyboard("[data-composer-input]");
            DeviceUi.await("The hero composer did not dock above the keyboard", 5000, () -> ui.truth("(()=>{"
                + "const root=document.documentElement;if(root.getAttribute('data-droy-ime-visible')!=='true')return false;"
                + "const ime=parseFloat(getComputedStyle(root).getPropertyValue('--droy-ime-bottom'))||0;"
                + "const card=document.querySelector('[data-composer-card]').getBoundingClientRect();"
                + "const gap=innerHeight-ime-card.bottom;return gap>=-1&&gap<=12})()"));
            assertComposerTouchesKeyboardWindow();
            capture("composer-keyboard");
        } finally {
            ui.evaluate("document.activeElement?.blur()");
            ui.hideKeyboard();
        }
        DeviceUi.await("The composer did not return to its centered position", 5000, () ->
            Math.abs(((Number) ui.evaluate("document.querySelector('[data-composer-card]').getBoundingClientRect().bottom")).doubleValue() - originalBottom) < 2);
        capture("composer-centered-restored");
    }

    @Test public void brandSurfacesRenameAndTemplatesPersistThroughRealSettingsTouches() throws Exception {
        assertWelcome("与Droy开始对话");
        assertTrue("The preview badge is still exposed", ui.truth("![...document.querySelectorAll('main, [data-droy-center]')].some(e=>/探索未至之境|预览版|Explore the unexplored/.test(e.textContent))"));
        awaitBrandImages();
        assertTrue("Default brand mark must use the installed logo", ui.truth("[...document.querySelectorAll('[data-droy-brand-mark]')].some(e=>new URL(e.src).pathname==='/droy/branding/installed-logo')"));
        capture("welcome-default");
        assertDrawerBrand("Droy");
        capture("drawer-default");
        openSettings();
        assertTrue("The upstream login/feedback entries remain visible", ui.truth("![...document.querySelectorAll('[role=menuitem]')].filter(e=>e.getClientRects().length).some(e=>/登录|意见反馈|Sign in|Feedback|Contact us/i.test(e.textContent))"));
        assertTrue("Direct settings navigation should not open a menu", ui.truth("![...document.querySelectorAll('[role=menu]')].some(e=>e.getClientRects().length)"));
        capture("settings-launcher");
        selectGeneral();
        replaceAndSave("#droy-assistant-name", "Droy UI 42", ".droy-assistant-name-save", "assistantName");
        ui.tap("[data-droy-brand-settings] summary");
        DeviceUi.await("The brand form did not expand", 5000,
            () -> ui.truth("document.querySelector('[data-droy-brand-settings] details').open"));
        replaceAndSave("#droy-brandWelcomeTemplate", "Hello {name}", "[data-droy-brand-save=brandWelcomeTemplate]", "brandWelcomeTemplate");
        replaceAndSave("#droy-brandComposerTemplate", "Task for {name}", "[data-droy-brand-save=brandComposerTemplate]", "brandComposerTemplate");
        replaceAndSave("#droy-brandHeroBadge", "My companion", "[data-droy-brand-save=brandHeroBadge]", "brandHeroBadge");
        capture("brand-settings");
        closeSettings();
        assertWelcome("Hello Droy UI 42");
        assertTrue("The document title did not follow the brand", ui.truth("document.title.includes('Droy UI 42')&&!document.title.includes('DeepSeek Harness')"));
        assertTrue("The composer snapshot did not follow the configured template", ui.truth("window.DroyBranding.getSnapshot().composer==='Task for Droy UI 42'"));
        assertTrue("A usable composer should show the configured placeholder", ui.truth("(()=>{const e=document.querySelector('[data-placeholder]');return !e||e.getAttribute('contenteditable')==='false'||e.getAttribute('data-placeholder')==='Task for Droy UI 42'})()"));
        DeviceUi.await("The configured welcome badge did not appear", 5000,
            () -> ui.truth("document.querySelector('[data-droy-brand-badge]')?.textContent==='My companion'"));
        capture("welcome-custom");
        openGeneral();
        ui.tap("[data-droy-brand-settings] summary");
        replaceAndSave("#droy-brandHeroBadge", "", "[data-droy-brand-save=brandHeroBadge]", "brandHeroBadge");
        closeSettings();
        DeviceUi.await("An empty welcome badge should be hidden", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-brand-badge]')"));
        assertDrawerBrand("Droy UI 42");
        capture("drawer-custom");
        ui.tap("[data-droy-drawer-close]");
        DeviceUi.await("Drawer did not close", 5000, () -> !ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        android.webkit.WebView reloadView = ui.web();
        instrumentation.runOnMainSync(reloadView::reload);
        awaitFreshPage();
        assertWelcome("Hello Droy UI 42");
        assertTrue("The composer template was lost after reload", ui.truth("window.DroyBranding.getSnapshot().composer==='Task for Droy UI 42'"));
    }

    private void awaitFreshPage() throws Exception {
        DeviceUi.await("The brand page did not become ready: " + RuntimeService.status, 90000,
            () -> RuntimeService.ready && ui.web() != null && ui.truth("typeof window.DroyMobile?.handleBack==='function'&&document.documentElement.dataset.droyUiReady==='true'"));
        DeviceUi.await("The known first-run welcome did not finish", 20000, () -> ui.truth("(()=>{const visible=e=>e.getClientRects().length&&!e.closest('[inert]');for(const h of document.querySelectorAll('h2')){if(!visible(h))continue;const text=h.textContent.trim();const labels=/^(欢迎使用 Droy|Welcome to Droy|预览版说明|Preview Notice)/.test(text)?['继续','Continue']:/^(添加一个 API Key 开始使用|Add an API key to get started)/.test(text)?['稍后配置','Configure later']:/^(开始使用|Get started)$/.test(text)?['关闭','Close']:null;if(!labels)continue;const modal=h.closest('[role=dialog],[role=alertdialog],dialog[open]');const b=[...(modal?.querySelectorAll('button')||[])].find(e=>visible(e)&&!e.disabled&&(labels.includes(e.textContent.trim())||labels.includes(e.getAttribute('aria-label'))));if(b){b.click();return false}}return !document.getElementById('root')?.inert})()"));
        ui.awaitPage();
        ui.hideKeyboard();
        DeviceUi.await("Brand page did not gain focus", 5000, () -> ui.nativeNavigationState().getBoolean("activityFocus"));
    }

    private void assertWelcome(String text) throws Exception {
        DeviceUi.await("The Droy welcome did not match the configured brand", 10000,
            () -> ui.truth("document.querySelector('[data-droy-brand-welcome]')?.textContent===" + JSONObject.quote(text)));
        assertLayout("[data-droy-brand-welcome]");
    }

    private void awaitBrandImages() throws Exception {
        DeviceUi.await("The brand image did not load", 10000, () -> ui.truth("(()=>{const images=[...document.querySelectorAll('[data-droy-brand-mark]')].filter(e=>e.getClientRects().length);return images.length>0&&images.every(e=>e.complete&&e.naturalWidth>0)})()"));
    }

    private void openDrawer() throws Exception {
        ui.hideKeyboard();
        if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) ui.tap("[data-droy-menu]");
        DeviceUi.await("The real menu touch did not open the drawer", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        JSONObject[] previous = {null};
        DeviceUi.await("The drawer brand is still moving", 5000, () -> {
            JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-droy-sidebar]'),r=e.getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height}})()");
            boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
            previous[0] = current;
            return stable && current.getDouble("left") >= -1;
        });
    }

    private void assertDrawerBrand(String name) throws Exception {
        openDrawer();
        DeviceUi.await("The sidebar brand did not update", 5000,
            () -> ui.truth("document.querySelector('[data-droy-brand-name]')?.textContent===" + JSONObject.quote(name)));
        assertTrue("Sidebar must contain only the logo and name", ui.truth("!document.querySelector('[data-droy-brand-label],[data-droy-brand-credit]')"));
        awaitBrandImages();
        assertLayout("[data-droy-brand-identity]");
        assertTrue("Logo and name must share their vertical center", ui.truth("(()=>{const a=document.querySelector('[data-droy-sidebar] [data-droy-brand-mark]').getBoundingClientRect(),b=document.querySelector('[data-droy-brand-name]').getBoundingClientRect();return Math.abs(a.top+a.height/2-b.top-b.height/2)<1})()"));
    }

    private void openSettings() throws Exception {
        openDrawer();
        try {
            assertTrue("Could not focus the visible settings launcher", ui.truth("(()=>{const e=document.querySelector("
                + JSONObject.quote(LAUNCHER) + ");if(!e||e.closest('[inert]'))return false;e.focus({preventScroll:true});return document.activeElement===e})()"));
            JSONObject[] previous = {null};
            DeviceUi.await("The settings launcher is still moving or obscured", 5000, () -> {
                JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(LAUNCHER)
                    + ");const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                    + "return {left:r.left,top:r.top,width:r.width,height:r.height,hit:!!hit&&(e===hit||e.contains(hit))}})()");
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
                + JSONObject.quote(LAUNCHER) + ");const r=e?.getBoundingClientRect();return {present:!!e,expanded:e?.getAttribute('aria-expanded'),"
                + "disabled:!!e?.disabled,inert:!!e?.closest('[inert]'),rect:r?.toJSON(),focus:document.activeElement===e,"
                + "roles:[...document.querySelectorAll('[role=menu],[role=menuitem],[role=dialog]')].map(e=>({role:e.getAttribute('role'),"
                + "label:e.getAttribute('aria-label'),visible:!!e.getClientRects().length,inert:!!e.closest('[inert]')}))}})()"), failure);
        }
    }

    private void selectGeneral() throws Exception {
        DeviceUi.await("The settings page did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The general settings did not become interactive", 10000,
            () -> ui.truth("!!document.querySelector('#droy-assistant-name')&&!document.querySelector('#droy-assistant-name').disabled"));
    }

    private void openGeneral() throws Exception { openSettings(); selectGeneral(); }

    private void closeSettings() throws Exception {
        ui.hideKeyboard();
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings did not return to the conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void replaceAndSave(String input, String value, String save, String preference) throws Exception {
        ui.tap(input);
        DeviceUi.await("Android did not focus the brand input", 5000,
            () -> ui.truth("document.activeElement===document.querySelector(" + JSONObject.quote(input) + ")"));
        long now = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
        if (!value.isEmpty()) instrumentation.sendStringSync(value);
        DeviceUi.await("Trusted Android input did not update the brand draft", 5000,
            () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(input) + ").value")));
        // Ctrl+A opens Android's native selection toolbar; leave editing before
        // testing the save tap so the toolbar does not consume that gesture.
        ui.hideKeyboard();
        ui.evaluate("document.querySelector(" + JSONObject.quote(input) + ").blur()");
        ui.tap("[data-droy-settings-header]>span");
        ui.visibleWebBounds(save);
        JSONObject[] previous = {null};
        DeviceUi.await("The save target is moving after the keyboard closes", 5000, () -> {
            JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(save)
                + ");const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {rect:r.toJSON(),hit:!!hit&&(e===hit||e.contains(hit)),ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0}})()");
            boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
            previous[0] = current;
            return stable && current.getBoolean("hit") && current.getDouble("ime") <= 1;
        });
        ui.evaluate("(()=>{window.__brandSaveEvents=[];window.__brandSaveGeometry={rect:document.querySelector(" + JSONObject.quote(save)
            + ").getBoundingClientRect().toJSON(),visual:{top:visualViewport.offsetTop,height:visualViewport.height},innerHeight};for(const t of ['pointerdown','pointerup','click'])document.addEventListener(t,e=>window.__brandSaveEvents.push({type:e.type,trusted:e.isTrusted,prevented:e.defaultPrevented,x:e.clientX,y:e.clientY,tag:e.target.tagName,save:e.target.closest('[data-droy-brand-save]')?.getAttribute('data-droy-brand-save')}),{capture:true,once:true})})()");
        ui.tap(save);
        try {
            DeviceUi.await("The real save touch did not persist brand text", 10000,
                () -> value.equals(preferences.getString(preference, "")) && ui.truth("document.querySelector(" + JSONObject.quote(save) + ").disabled"));
        } catch (AssertionError failure) {
            capture("save-failure");
            throw new AssertionError(failure.getMessage() + "; key=" + preference + "; saved=" + preferences.getString(preference, "<missing>")
                + "; ui=" + ui.evaluate("({draft:document.querySelector(" + JSONObject.quote(input) + ").value,disabled:document.querySelector("
                + JSONObject.quote(save) + ").disabled,errors:[...document.querySelectorAll('.droy-error')].map(e=>e.textContent),badge:window.DroyBranding.getSnapshot().heroBadge,events:window.__brandSaveEvents,geometry:window.__brandSaveGeometry})"), failure);
        }
        assertLayout(input);
    }

    private void assertLayout(String selector) throws Exception {
        if (!ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
            + ");if(!e)return false;const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&r.left>=-1&&r.right<=innerWidth+1&&e.scrollWidth<=e.clientWidth+2&&e.scrollHeight<=e.clientHeight+2})()")) {
            capture("layout-failure");
            fail("Brand content overflows or clips: " + selector + "; " + ui.evaluate("(()=>{let e=document.querySelector(" + JSONObject.quote(selector)
                + ");const chain=[];for(let i=0;e&&i<6;i++,e=e.parentElement){const s=getComputedStyle(e);chain.push({tag:e.tagName,cls:e.className,rect:e.getBoundingClientRect().toJSON(),scroll:[e.scrollWidth,e.scrollHeight],client:[e.clientWidth,e.clientHeight],overflow:s.overflow,display:s.display,height:s.height})}return chain})()"));
        }
    }

    private Rect keyboardWindowBounds() {
        AccessibilityServiceInfo info = ui.automation().getServiceInfo();
        assertNotNull("The UI automation connection must stay active", info);
        if ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.automation().setServiceInfo(info);
        }
        Rect bounds = new Rect();
        StringBuilder windows = new StringBuilder("flags=").append(info.flags).append("; windows=[");
        for (AccessibilityWindowInfo window : ui.automation().getWindows()) {
            try {
                Rect frame = new Rect();
                window.getBoundsInScreen(frame);
                windows.append("{type=").append(window.getType()).append(", id=").append(window.getId())
                    .append(", bounds=").append(frame.toShortString()).append("},");
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) bounds.set(frame);
            } finally { window.recycle(); }
        }
        windowSnapshot = windows.append(']').toString();
        return bounds;
    }

    private void assertComposerTouchesKeyboardWindow() throws Exception {
        String[] previous = {null};
        int[] stableFrames = {0};
        String[] diagnostic = {"No window bounds sampled"};
        try { DeviceUi.await("The composer must touch the physical IME window within 12 CSS px", 5000, () -> {
            Rect keyboard = keyboardWindowBounds(), composer = ui.webBounds("[data-composer-card]");
            int[] webWidth = {0};
            WebView view = ui.web();
            instrumentation.runOnMainSync(() -> webWidth[0] = view.getWidth());
            double cssWidth = ((Number) ui.evaluate("innerWidth")).doubleValue();
            int allowance = (int) Math.ceil(12 * webWidth[0] / cssWidth);
            int gap = keyboard.top - composer.bottom;
            String current = keyboard.toShortString() + " / " + composer.toShortString();
            stableFrames[0] = current.equals(previous[0]) ? stableFrames[0] + 1 : 0;
            previous[0] = current;
            diagnostic[0] = current + "; gap=" + gap + "; allowance=" + allowance + "; " + windowSnapshot;
            return stableFrames[0] >= 2 && !keyboard.isEmpty() && !composer.isEmpty()
                && gap >= -2 && gap <= allowance;
        }); } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "; " + diagnostic[0], failure);
        }
        ui.awaitPaint();
    }

    private void capture(String stage) throws Exception {
        ui.awaitPaint();
        Bitmap bitmap = ui.automation().takeScreenshot();
        assertNotNull("Could not capture brand UI", bitmap);
        try {
            File directory = new File(context.getExternalFilesDir(null), "branding-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("brandingEvidenceLabel", "brand").replaceAll("[^A-Za-z0-9_-]", "_");
            JSONObject conditions = (JSONObject) ui.evaluate("({cssWidth:innerWidth,cssHeight:innerHeight,theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            if (stage.startsWith("composer")) {
                conditions.put("composerScreenBounds", ui.webBounds("[data-composer-card]").toShortString());
                conditions.put("imeScreenBounds", keyboardWindowBounds().toShortString());
                conditions.put("accessibilityWindows", windowSnapshot);
                conditions.put("viewport", ui.evaluate("({scrollY,offsetTop:visualViewport?.offsetTop,height:visualViewport?.height})"));
            }
            File output = File.createTempFile(label + "-" + stage + "-" + bitmap.getWidth() + "x" + bitmap.getHeight() + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_BRAND_EVIDENCE " + output.getAbsolutePath() + "\nDROY_BRAND_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { bitmap.recycle(); }
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

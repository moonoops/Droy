package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real WebView layout acceptance; run unchanged at narrow, normal, landscape and enlarged-font sizes. */
@RunWith(AndroidJUnit4.class)
public final class MobileFeatureLayoutDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private File runtime, isolatedHome, preservedHome;
    private boolean homeOwned, hadOriginalHome;
    private Map<String, String> originalFiles;
    private SharedPreferences preferences;
    private String originalTheme;
    private boolean hadOriginalTheme;
    private String originalLanguage;
    private boolean hadOriginalLanguage;
    private Boolean originalSetupCompleted;
    private boolean setupCompletionOwned;
    private boolean rotationOwned;
    private static final String OPTIONS = "[data-droy-settings-options]";
    private static final String TABS = "[data-droy-settings-tabs]";
    // Paragraph line leading may sit outside a scrollport while every glyph fits.
    // Interactive controls still require their complete target boxes.
    private static final String LAST_CONTROL_RECT = "(()=>{const e=document.querySelector('[data-layout-last]');"
        + "if(e.hasAttribute('data-layout-last-text')){const range=document.createRange();range.selectNodeContents(e);const rect=range.getBoundingClientRect();if(rect.width>0&&rect.height>0)return rect;}"
        + "return e.getBoundingClientRect()})()";

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        hadOriginalTheme = preferences.contains("themePreference");
        originalTheme = preferences.getString("themePreference", "system");
        hadOriginalLanguage = preferences.contains("languagePreference");
        originalLanguage = preferences.getString("languagePreference", "system");
        boolean emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone");
        boolean developmentPhone = context.getPackageName().equals("io.droyagent.app.dev")
            && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && "true".equals(InstrumentationRegistry.getArguments().getString("layoutDevelopmentPhoneFixture"));
        assertTrue("Layout fixtures require a dedicated emulator or an explicitly selected development app", emulator || developmentPhone);
        assertTrue("Stop the existing engine before layout tests", RuntimeService.canManageHistory());
        RuntimeInstaller.installCertificate(context);
        runtime = RuntimeInstaller.install(context);
        isolatedHome = new File(context.getFilesDir(), "dsh-home");
        preservedHome = new File(context.getFilesDir(), "droy-layout-home-backup-" + UUID.randomUUID());
        hadOriginalHome = isolatedHome.exists();
        originalFiles = snapshot(isolatedHome.toPath());
        if (hadOriginalHome) assertTrue("Could not preserve the original DSH home", isolatedHome.renameTo(preservedHome));
        homeOwned = true;
        assertTrue("Could not create the isolated layout home", isolatedHome.mkdirs());
        String locale = InstrumentationRegistry.getArguments().getString("layoutLocale");
        if (locale != null) {
            assertTrue("Unsupported layout locale", locale.equals("zh") || locale.equals("en"));
            assertTrue(preferences.edit().putString("languagePreference", locale).commit());
        }
        seedFutureAutomation();
        SharedPreferences installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        originalSetupCompleted = installation.contains("completed") ? installation.getBoolean("completed", false) : null;
        assertTrue(installation.edit().putBoolean("completed", true).commit());
        setupCompletionOwned = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        String landscape = InstrumentationRegistry.getArguments().getString("layoutLandscape");
        if (landscape != null) {
            assertTrue("Unsupported layout orientation", landscape.equals("true") || landscape.equals("false"));
            boolean requestedLandscape = landscape.equals("true");
            assertTrue(ui.automation().setRotation(requestedLandscape
                ? android.app.UiAutomation.ROTATION_FREEZE_90 : android.app.UiAutomation.ROTATION_FREEZE_0));
            rotationOwned = true;
            DeviceUi.await("The layout did not reach the requested orientation", 5000,
                () -> ui.truth(requestedLandscape ? "innerWidth>innerHeight" : "innerHeight>innerWidth"));
        }
        ui.hideKeyboard();
        awaitMainMenu();
        if ("true".equals(InstrumentationRegistry.getArguments().getString("layoutMaxTextFont"))) maximizeDshTextFont();
        String theme = InstrumentationRegistry.getArguments().getString("layoutTheme");
        if (theme != null) chooseTheme(theme);
    }

    @After public void tearDown() throws Exception {
        if (!homeOwned) return;
        try {
            if (activity != null) {
                releaseInput();
                instrumentation.runOnMainSync(activity::finish);
                DeviceUi.await("Layout Activity did not finish", 10000, () -> {
                    boolean[] destroyed = {false};
                    instrumentation.runOnMainSync(() -> destroyed[0] = activity.isDestroyed());
                    return destroyed[0];
                });
            }
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Layout cleanup left the runtime alive", 15000, RuntimeService::canManageHistory);
            assertNoModelOrToolRequests();
        } finally {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Layout cleanup left the runtime alive", 15000, RuntimeService::canManageHistory);
            assertTrue("Keep the original data backed up while the fixture engine is alive", RuntimeService.canManageHistory());
            HistoryData.deleteTree(isolatedHome.toPath());
            if (hadOriginalHome) assertTrue("Could not restore the original DSH home", preservedHome.renameTo(isolatedHome));
            assertEquals("The layout fixture changed original DSH data", originalFiles, snapshot(isolatedHome.toPath()));
            SharedPreferences.Editor restore = preferences.edit();
            if (hadOriginalTheme) restore.putString("themePreference", originalTheme); else restore.remove("themePreference");
            if (hadOriginalLanguage) restore.putString("languagePreference", originalLanguage); else restore.remove("languagePreference");
            assertTrue("Could not restore the native theme preference", restore.commit());
            if (setupCompletionOwned) {
                SharedPreferences.Editor installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE).edit();
                if (originalSetupCompleted == null) installation.remove("completed");
                else installation.putBoolean("completed", originalSetupCompleted);
                assertTrue("Could not restore installation completion", installation.commit());
                setupCompletionOwned = false;
            }
            if (rotationOwned) {
                assertTrue(ui.automation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE));
                rotationOwned = false;
            }
            homeOwned = false;
        }
    }

    @Test public void fullFeatureTitlesSettingsTabsAndFormsRemainReadableAndReachable() throws Exception {
        openPanel("插件", "Plugins", "[data-plugin-panel]");
        capture("plugin-page");
        assertReadable("[data-plugin-panel] h1", true);
        assertNoHorizontalOverflow("[data-plugin-panel]", "plugin page");
        markButton("[data-plugin-panel]", "添加插件", "Add plugin");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The actual plugin install dialog did not open", 10000,
            () -> ui.truth("!![...document.querySelectorAll('[role=dialog]')].find(e=>e.querySelector('input[aria-label=\"包名或地址\"],input[aria-label=\"Package name or address\"]'))"));
        ui.evaluate("(()=>{const e=[...document.querySelectorAll('[role=dialog]')].find(e=>e.querySelector('input[aria-label=\"包名或地址\"],input[aria-label=\"Package name or address\"]'));e.setAttribute('data-layout-modal','')})()");
        ui.hideKeyboard();
        reachElementByTouch("[data-layout-modal] h2");
        capture("plugin-install");
        assertReadable("[data-layout-modal] h2", false);
        assertNoHorizontalOverflow("[data-layout-modal]", "plugin install dialog");
        assertLastControlReachable("[data-layout-modal]", "plugin install dialog");
        markButton("[data-layout-modal]", "关闭", "Close");
        ui.tap("[data-layout-action]");
        DeviceUi.await("Plugin install dialog did not close", 5000, () -> !ui.truth("!!document.querySelector('[data-layout-modal]')"));
        assertPluginDetailAndMore();
        returnFromPanel("[data-plugin-panel]");

        openPanel("自动化任务", "Automation tasks", "[data-testid=task-manager-page]");
        capture("automation-page");
        assertReadable("[data-testid=task-manager-page] h1", true);
        assertNoHorizontalOverflow("[data-testid=task-manager-page]", "automation page");
        assertLastControlReachable("[data-testid=task-manager-page]", "automation page");
        assertAutomationDetails();
        returnFromPanel("[data-testid=task-manager-page]");

        openDrawer();
        reachElementByTouch("[data-droy-sidebar] [data-droy-settings-launcher]");
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The settings page did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        JSONArray sections = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-droy-settings-section]')].map(e=>e.getAttribute('data-droy-settings-section'))");
        for (String expected : new String[]{"general", "models", "plugins", "agent-presets", "droy-permissions"})
            assertTrue("A full-profile settings section is missing: " + expected, sections.toString().contains(JSONObject.quote(expected)));
        String lastSection = sections.getString(sections.length() - 1);
        String lastTab = "[data-droy-settings-section=" + JSONObject.quote(lastSection) + "]";
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:"
            + JSONObject.quote(lastSection) + "}}))");
        DeviceUi.await("Opening a specified final section left its selected tab off screen", 5000,
            () -> ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(lastTab) + "),c=document.querySelector('[data-droy-settings-tabs]');"
                + "if(e?.getAttribute('aria-current')!=='true'||!c)return false;const r=e.getBoundingClientRect(),b=c.getBoundingClientRect();return r.left>=b.left-1&&r.right<=b.right+1})()"));
        capture("settings-open-last");
        assertReadable(lastTab, false);
        for (int index = 0; index < sections.length(); index++) {
            String section = sections.getString(index);
            String selector = "[data-droy-settings-section=" + JSONObject.quote(section) + "]";
            reachTabByTouch(selector, TABS);
            assertReadable(selector, false);
            ui.tap(selector);
            DeviceUi.await("A touched settings tab did not switch: " + section, 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-current')==='true'"));
            awaitSettingsContent();
            ui.evaluate("document.querySelector('[data-droy-settings-options]').scrollTop=0");
            ui.awaitPaint();
            capture("settings-" + section);
            assertNoHorizontalOverflow(OPTIONS, "settings/" + section);
            assertNestedTabs(section);
            assertLastControlReachable(OPTIONS, "settings/" + section);
            if (section.equals("plugins")) assertPluginPresetSwitcher();
            if (section.equals("droy-permissions")) for (String permission : new String[]{"file", "directory"}) {
                String choice = ".droy-button[data-droy-permission=" + permission + "]";
                ui.visibleWebBounds(choice);
                assertReadable(choice, false);
                capture("permissions-" + permission + "-choice");
            }
            if (section.equals("models")) assertModelAddForm();
            if (section.equals("agent-presets")) assertPresetNamesAndGuide();
        }
        reachTabByTouch("[data-droy-settings-section=general]", TABS);
        ui.tap("[data-droy-settings-section=general]");
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings Back did not return to chat", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        awaitMainMenu();
        assertComposerCommands();
    }

    @Test public void builtInPluginDetailsExpandAndRemainReadable() throws Exception {
        openDrawer();
        reachElementByTouch("[data-droy-sidebar] [data-droy-settings-launcher]");
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The settings page did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        reachTabByTouch("[data-droy-settings-section=plugins]", TABS);
        ui.tap("[data-droy-settings-section=plugins]");
        DeviceUi.await("The actual built-in plugin inventory did not load", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-options] [data-plugin-scope=preset]')&&!!document.querySelector('[data-droy-settings-options] [data-plugin-scope=global]')"));
        int checked = 0;
        for (String scope : new String[]{"preset", "global"}) {
            String group = "[data-droy-settings-options] [data-plugin-scope=" + scope + "]";
            String toggle = group + " button[aria-controls]";
            if (ui.truth("document.querySelector(" + JSONObject.quote(toggle) + ")?.getAttribute('aria-expanded')==='false'")) ui.tap(toggle);
            String card = group + " [data-plugin-module]";
            DeviceUi.await("No built-in plugin card was available in " + scope, 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(card) + ")"));
            ui.evaluate("document.querySelector(" + JSONObject.quote(card) + ").setAttribute('data-layout-plugin-card','')");
            String selected = "[data-layout-plugin-card]";
            String header = selected + " > button[aria-expanded]";
            ui.tap(header);
            DeviceUi.await("The actual built-in plugin card did not expand", 5000,
                () -> ui.truth("document.querySelector('[data-layout-plugin-card]')?.getAttribute('data-open')==='true'"));
            ui.visibleWebBounds(header + " strong");
            assertReadable(header + " strong", false);
            assertNoHorizontalOverflow(selected, "expanded built-in " + scope + " plugin");
            JSONArray facts = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-layout-plugin-card] dd')].map((e,i)=>{e.setAttribute('data-layout-plugin-fact',String(i));return String(i)})");
            assertTrue("An expanded built-in card must expose its actual detail facts", facts.length() > 0);
            for (int i = 0; i < facts.length(); i++) {
                String fact = "[data-layout-plugin-fact=" + JSONObject.quote(facts.getString(i)) + "]";
                ui.visibleWebBounds(fact);
                assertReadable(fact, false);
            }
            capture("built-in-" + scope + "-expanded-last-fact");
            ui.tap(header);
            DeviceUi.await("The actual built-in plugin card did not collapse", 5000,
                () -> !ui.truth("document.querySelector('[data-layout-plugin-card]')?.getAttribute('data-open')==='true'"));
            ui.evaluate("document.querySelector('[data-layout-plugin-card]').removeAttribute('data-layout-plugin-card')");
            checked++;
        }
        assertEquals("Both preset and global built-in plugin details must be checked", 2, checked);
        Bundle status = new Bundle();status.putString("stream", "\nDROY_BUILT_IN_DETAILS_CHECKED " + checked + "\n");instrumentation.sendStatus(2, status);
    }

    @Test public void defaultSpaceAndTwoBuiltInModesRemainUsableAfterRenameAndRestart() throws Exception {
        openDrawer();
        String workspace = "[data-droy-sidebar] [data-row-key^='workspace:']";
        String defaultSpace = ui.truth("document.documentElement.lang.startsWith('zh')") ? "默认空间" : "Default space";
        assertTrue("A new profile must display the default space name",
            ui.truth("document.querySelector(" + JSONObject.quote(workspace) + ")?.textContent.includes(" + JSONObject.quote(defaultSpace) + ")"));
        capture("default-space-sidebar");
        markButton("[data-droy-sidebar]", "新会话", "New Session");
        ui.tap("[data-layout-action]");
        ui.awaitPage();
        DeviceUi.await("The new conversation must show the default space", 10000,
            () -> ui.truth("document.querySelector('[data-content-phase=hero] [data-droy-workspace-chip]')?.textContent.trim()===" + JSONObject.quote(defaultSpace)));
        assertReadable("[data-content-phase=hero] [data-droy-workspace-chip]", false);
        markPresetChip();
        assertTrue("New conversations must start with Standard mode", ui.truth(
            "['标准模式','Standard mode'].includes(document.querySelector('[data-layout-preset-chip]')?.textContent.trim())"));
        capture("default-space-standard");
        ui.tap("[data-layout-preset-chip]");
        DeviceUi.await("The mode picker did not open", 5000,
            () -> ui.truth("document.querySelector('[data-layout-preset-chip]')?.getAttribute('aria-expanded')==='true'"));
        JSONArray choices = (JSONArray) ui.evaluate("[...document.querySelectorAll('[role=menuitem]')].filter(e=>e.getClientRects().length&&!e.closest('[inert]')).map(e=>e.textContent.trim())");
        assertEquals("The new-conversation picker must contain only the two built-in modes", 2, choices.length());
        assertTrue("Standard mode is missing from the picker", choices.toString().contains("标准模式") || choices.toString().contains("Standard mode"));
        assertTrue("Creator mode is missing from the picker", choices.toString().contains("创造模式") || choices.toString().contains("Creator mode"));
        ui.evaluate("[...document.querySelectorAll('[role=menuitem]')].filter(e=>e.getClientRects().length&&!e.closest('[inert]')).forEach((e,i)=>e.setAttribute('data-layout-mode-choice',String(i)))");
        for (int i = 0; i < choices.length(); i++) assertReadable("[data-layout-mode-choice='" + i + "']", false);
        assertNoHorizontalOverflow("[role=menu]", "new-conversation mode picker");
        capture("two-mode-picker");
        ui.evaluate("(()=>{const e=[...document.querySelectorAll('[role=menuitem]')].find(e=>e.getClientRects().length&&/^(创造模式|Creator mode)/.test(e.textContent.trim()));if(e)e.setAttribute('data-layout-creator-choice','')})()");
        ui.tap("[data-layout-creator-choice]");
        ui.awaitPage();
        DeviceUi.await("Touching Creator mode did not select it", 10000,
            () -> ui.truth("['创造模式','Creator mode'].includes(document.querySelector('[data-layout-preset-chip]')?.textContent.trim())&&!document.querySelector('[data-layout-creator-choice]')"));
        capture("creator-selected");
        assertReadable("[data-layout-preset-chip]", false);

        openDrawer();
        reachElementByTouch("[data-droy-sidebar] [data-droy-settings-launcher]");
        SettingsEntryDeviceUi.openFromDrawer(ui);
        reachTabByTouch("[data-droy-settings-section=agent-presets]", TABS);
        ui.tap("[data-droy-settings-section=agent-presets]");
        DeviceUi.await("The two built-in modes did not load in settings", 10000,
            () -> ui.truth("document.querySelectorAll('[data-agent-preset-id]').length===2"));
        assertPresetNamesAndGuide();
        capture("two-mode-settings");
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings did not return to the conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));

        openDrawer();
        // Select the workspace row's own action rather than the session menus below it.
        ui.tap(workspace);
        ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(workspace)
            + ");const e=[...row.querySelectorAll('button')].find(e=>/操作$|^Workspace actions/.test(e.getAttribute('aria-label')||''));if(e)e.setAttribute('data-layout-workspace-actions','')})()");
        ui.tap("[data-layout-workspace-actions]");
        ui.evaluate("(()=>{const e=[...document.querySelectorAll('[role=menuitem]')].find(e=>e.getClientRects().length&&['重命名','Rename'].includes(e.textContent.trim()));if(e)e.setAttribute('data-layout-rename-space','')})()");
        ui.tap("[data-layout-rename-space]");
        String input = "[role=dialog] input[data-modal-autofocus]";
        DeviceUi.await("The actual space rename dialog did not open", 5000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(input) + ")"));
        ui.evaluate("document.querySelector(" + JSONObject.quote(input) + ").closest('[role=dialog]').setAttribute('data-layout-rename-dialog','')");
        ui.tap(input);
        ui.evaluate("document.querySelector(" + JSONObject.quote(input) + ").select()");
        instrumentation.sendStringSync("Daily space");
        DeviceUi.await("The space rename field did not accept Android key input", 5000,
            () -> ui.truth("document.querySelector(" + JSONObject.quote(input) + ")?.value==='Daily space'"));
        ui.hideKeyboard();
        capture("rename-space-dialog");
        markButton("[data-layout-rename-dialog]", "重命名", "Rename");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The space rename did not reach the sidebar", 10000,
            () -> ui.truth("!document.querySelector(" + JSONObject.quote(input) + ")&&document.querySelector(" + JSONObject.quote(workspace) + ")?.textContent.includes('Daily space')"));
        capture("renamed-space-sidebar");

        releaseInput();
        MainActivity previous = activity;
        instrumentation.runOnMainSync(previous::finish);
        DeviceUi.await("The Activity did not finish before the engine restart", 10000, previous::isDestroyed);
        instrumentation.runOnMainSync(RuntimeService::requestStop);
        DeviceUi.await("The engine did not stop before the restart", 15000, RuntimeService::canManageHistory);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        openDrawer();
        assertTrue("Restart must preserve the user's space name", ui.truth(
            "document.querySelector(" + JSONObject.quote(workspace) + ")?.textContent.includes('Daily space')"));
        assertFalse("Restart must not add another default space", ui.truth(
            "[...document.querySelectorAll(" + JSONObject.quote(workspace) + ")].some(e=>e.textContent.includes(" + JSONObject.quote(defaultSpace) + "))"));
        capture("renamed-space-after-restart");
    }

    private void markPresetChip() throws Exception {
        DeviceUi.await("The new-conversation mode chip did not become ready", 10000, () -> ui.truth(
            "(()=>{const e=[...document.querySelectorAll('[data-content-phase=hero] button[aria-haspopup=menu]')].find(e=>e.getClientRects().length&&!e.disabled&&['标准模式','Standard mode','创造模式','Creator mode'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-layout-preset-chip','');return true})()"));
    }

    private void assertComposerCommands() throws Exception {
        openDrawer();
        String row = "[data-droy-session-row='droy-layout-fixture']";
        if (!ui.truth("!!document.querySelector(" + JSONObject.quote(row) + ")"))
            ui.tap("[data-droy-sidebar] [role=treeitem][aria-expanded=false]");
        String surface = row + " [data-droy-session-surface]";
        reachElementByTouch(surface);
        String[] previous = {null}; long[] unchangedSince = {0};
        try {
            DeviceUi.await("The actual session row did not settle into a touchable position", 5000, () -> {
                JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(surface) + "),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2),root=getComputedStyle(document.documentElement),px=n=>parseFloat(root.getPropertyValue(n))||0;"
                    + "let fullyVisible=r.top>=px('--droy-safe-top')-1&&r.bottom<=innerHeight-Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))+1;"
                    + "for(let a=e.parentElement;a;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();if(/hidden|clip|auto|scroll/.test(s.overflowY)&&(r.top<b.top-1||r.bottom>b.bottom+1))fullyVisible=false;}"
                    + "return {left:r.left,top:r.top,width:r.width,height:r.height,fullyVisible,hit:!!hit&&(e===hit||e.contains(hit))}})()");
                String value = current.toString();
                if (!value.equals(previous[0])) { previous[0] = value; unchangedSince[0] = SystemClock.elapsedRealtime(); }
                return current.getBoolean("fullyVisible") && current.getBoolean("hit")
                    && SystemClock.elapsedRealtime() - unchangedSince[0] >= 200;
            });
        } catch (AssertionError failure) {
            capture("session-touch-position-failure");
            Object state = ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(surface) + "),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2),ancestors=[];"
                + "for(let a=e.parentElement;a;a=a.parentElement){const s=getComputedStyle(a);ancestors.push({tag:a.tagName,className:a.className,overflowY:s.overflowY,scrollTop:a.scrollTop,scrollHeight:a.scrollHeight,clientHeight:a.clientHeight,rect:a.getBoundingClientRect().toJSON()});}"
                + "return {target:r.toJSON(),hit:hit?{tag:hit.tagName,className:hit.className,role:hit.getAttribute('role')}:null,selection:String(getSelection()),ancestors}})()");
            throw new AssertionError(failure.getMessage() + "; actual touch geometry=" + state, failure);
        }
        capture("session-drawer-reached");
        // The row is already reached and fully checked. Generic tap() invokes
        // scrollIntoView again, which can move this short drawer under a system bar.
        Rect sessionBounds = ui.webBounds(surface);
        ui.tapAt(sessionBounds.centerX(), sessionBounds.centerY());
        try {
            DeviceUi.await("The fictional standard session did not open", 10000,
                () -> ui.truth("!!document.querySelector('[data-conversation-session=\"droy-layout-fixture\"][data-content-phase=active] [data-composer-input][contenteditable=true]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
        } catch (AssertionError failure) {
            capture("session-selection-failure");
            Object state = ui.evaluate("({drawer:!!document.querySelector('[data-droy-drawer-open=true]'),rows:[...document.querySelectorAll('[data-droy-session-row]')].map(e=>({id:e.getAttribute('data-droy-session-row'),selected:e.getAttribute('aria-selected')})),contents:[...document.querySelectorAll('[data-conversation-session]')].map(e=>({session:e.getAttribute('data-conversation-session'),phase:e.getAttribute('data-content-phase')})),dialogs:[...document.querySelectorAll('[role=dialog] h2')].map(e=>e.textContent.trim())})");
            throw new AssertionError(failure.getMessage() + "; actual session state=" + state, failure);
        }
        assertCommandMenu("active");
        openDrawer();
        markButton("[data-droy-sidebar]", "新会话", "New Session");
        ui.tap("[data-layout-action]");
        DeviceUi.await("A real new conversation did not open the hero composer", 10000,
            () -> ui.truth("!!document.querySelector('[data-content-phase=hero] [data-droy-workspace-chip]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
        // A blank session remounts the model-key onboarding in this unconfigured
        // fixture. Reuse the existing exact-dialog dismissal before any touch.
        ui.awaitPage();
        awaitMainMenu();
        DeviceUi.await("The new blank session lost its hero composer", 10000,
            () -> ui.truth("!!document.querySelector('[data-conversation-session][data-content-phase=hero] [data-composer-input][contenteditable=true]')"));
        assertCommandMenu("hero");
    }

    private void assertCommandMenu(String phase) throws Exception {
        ui.hideKeyboard();
        String composer = "[data-content-phase=" + phase + "]";
        String add = composer + " [data-droy-composer-add]";
        ui.visibleWebBounds(add);
        capture("composer-before-menu-" + phase);
        try {
            DeviceUi.await("The " + phase + " composer add button is not ready for touch", 10000,
                () -> ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(add) + ");if(!e||e.disabled)return false;const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!hit&&(e===hit||e.contains(hit))})()"));
        } catch (AssertionError error) {
            Object diagnostic = ui.evaluate("[...document.querySelectorAll('[data-droy-composer-add]')].map(e=>{const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {phase:e.closest('[data-content-phase]')?.getAttribute('data-content-phase'),disabled:e.disabled,rect:r.toJSON(),hit:hit?.outerHTML.slice(0,900),dialogs:[...document.querySelectorAll('[role=dialog]')].filter(e=>e.getClientRects().length).map(e=>e.textContent)}})");
            throw new AssertionError(error.getMessage() + "; " + diagnostic, error);
        }
        ui.tap(add);
        DeviceUi.await("The actual composer command menu did not open", 5000,
            () -> ui.truth("document.querySelectorAll('[data-trigger-menu] [role=option]').length>=8"));
        capture("composer-menu-" + phase);
        Object geometry = ui.evaluate("(()=>{const menu=document.querySelector('[data-trigger-menu]'),list=menu.querySelector('[role=listbox]'),ancestors=[];for(let e=menu;e;e=e.parentElement){const s=getComputedStyle(e);ancestors.push({tag:e.tagName,className:e.className,position:s.position,overflowX:s.overflowX,overflowY:s.overflowY,transform:s.transform,rect:e.getBoundingClientRect().toJSON()})}return {phase:document.querySelector('[data-conversation-content]')?.getAttribute('data-content-phase'),menu:menu.getBoundingClientRect().toJSON(),listbox:{rect:list.getBoundingClientRect().toJSON(),scrollTop:list.scrollTop,clientHeight:list.clientHeight,scrollHeight:list.scrollHeight},composer:document.querySelector('[data-composer-seat]')?.getBoundingClientRect().toJSON(),ancestors}})()");
        Bundle status = new Bundle();status.putString("stream", "\nDROY_COMPOSER_GEOMETRY " + geometry + "\n");instrumentation.sendStatus(2, status);
        JSONArray commands = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-trigger-menu] [role=option]')].map(e=>({id:e.id,names:[...e.querySelectorAll('span')].filter(s=>!s.closest('[aria-hidden=true]')).map(s=>s.textContent.trim())}))");
        List<String> commandNames = new ArrayList<>();
        for (int i = 0; i < commands.length(); i++) {
            JSONArray names = commands.getJSONObject(i).getJSONArray("names");
            for (int index = 0; index < names.length(); index++)
                commandNames.add(names.getString(index).toLowerCase(java.util.Locale.ROOT));
        }
        for (String name : new String[]{"file", "goal", "plan", "feedback", "compact", "permission", "model", "export"})
            assertTrue("The full command menu omitted /" + name + ": " + commandNames, commandNames.contains(name));
        JSONArray targets = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-trigger-menu] [role=presentation],[data-trigger-menu] [role=option]')].filter(e=>e.textContent.trim()).map((e,index)=>{e.setAttribute('data-layout-command',String(index));return String(index)})");
        for (int index = 0; index < targets.length(); index++) {
            String selector = "[data-layout-command=" + JSONObject.quote(targets.getString(index)) + "]";
            reachCommandByTouch(selector);
            // The menu can overlap the global navigation in screen coordinates;
            // its text must own the hit target and paint above that navigation.
            assertReadable(selector, false);
        }
        capture("composer-menu-bottom-" + phase);
        assertNoHorizontalOverflow("[data-trigger-menu]", "composer command menu");
        ui.pressBack();
        DeviceUi.await("System Back did not dismiss the command menu", 5000,
            () -> !ui.truth("!!document.querySelector('[data-trigger-menu]')"));
        assertTrue("Inspecting the command menu must leave the synthetic composer empty",
            ui.truth("document.querySelector(" + JSONObject.quote(composer + " [data-composer-input]") + ")?.textContent.trim()===''"));
    }

    private void reachCommandByTouch(String selector) throws Exception {
        String viewport = "[data-trigger-menu] [role=listbox]";
        JSONObject position = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            position = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + "),c=document.querySelector(" + JSONObject.quote(viewport)
                + ");const r=e.getBoundingClientRect(),b=c.getBoundingClientRect();return {visible:r.top>=b.top-1&&r.bottom<=b.bottom+1,above:r.top<b.top,height:r.height,available:b.height,delta:r.top<b.top?r.top-b.top:r.bottom-b.bottom,scrollTop:c.scrollTop,scrollHeight:c.scrollHeight,top:r.top,bottom:r.bottom,viewportTop:b.top,viewportBottom:b.bottom,label:e.textContent}})()");
            assertTrue("A command row cannot fit in the visible menu: " + position, position.getDouble("height") <= position.getDouble("available") + 1);
            if (position.getBoolean("visible")) return;
            Rect bounds = ui.webBounds(viewport);
            boolean above = position.getBoolean("above");
            // Move only the remaining clipped distance, with a touch-slop allowance.
            // A fixed long swipe can oscillate a tall row above/below a short landscape viewport.
            float scale = (float) (bounds.height() / position.getDouble("available"));
            float distance = Math.min(bounds.height() * 0.6f,
                (float) Math.abs(position.getDouble("delta")) * scale + ViewConfiguration.get(context).getScaledTouchSlop() + 8);
            float start = above ? bounds.top + bounds.height() * 0.2f : bounds.bottom - bounds.height() * 0.2f;
            ui.swipe(bounds.centerX(), start, bounds.centerX(), start + (above ? distance : -distance), 500);
            SystemClock.sleep(150);
        }
        capture("composer-scroll-failure");
        fail("The command menu item cannot be reached by actual vertical touch scrolling: " + selector + " " + position);
    }

    private void chooseTheme(String theme) throws Exception {
        assertTrue("Unsupported layout theme", List.of("light", "dark", "system").contains(theme));
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        String selector = "[data-droy-theme-choice=" + theme + "]";
        DeviceUi.await("The actual DSH theme choice did not load", 10000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(selector) + ")"));
        ui.tap(selector);
        DeviceUi.await("The DSH page did not apply the requested theme", 5000,
            () -> theme.equals(preferences.getString("themePreference", "")));
        ui.tap("[data-droy-settings-back]");
        awaitMainMenu();
    }

    private void maximizeDshTextFont() throws Exception {
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("DSH font settings did not load", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')"));
        if (!ui.truth("document.querySelector('[data-droy-font-expander]').getAttribute('aria-expanded')==='true'")) ui.tap("[data-droy-font-expander]");
        String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
        DeviceUi.await("The original text font control did not expand", 5000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(increase) + ")"));
        for (int i = 0; i < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); i++) {
            String oldSize = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size')"));
            ui.tap(increase);
            DeviceUi.await("The actual DSH text font increment did not persist", 5000,
                () -> !oldSize.equals(String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size')"))));
        }
        assertTrue("The real DSH font control did not reach its supported maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        capture("dsh-max-text-font");
        ui.tap("[data-droy-settings-back]");
        awaitMainMenu();
    }

    private void assertAutomationDetails() throws Exception {
        ui.tap("[data-testid=task-manager-page] button[aria-label='Fictional layout task']");
        DeviceUi.await("The fictional scheduled task did not open its details", 5000,
            () -> ui.truth("!!document.querySelector('[data-testid=task-manager-page] [data-detail-tab=rule]')"));
        capture("automation-details-rules");
        for (String tab : new String[]{"rule", "records"}) {
            String selector = "[data-testid=task-manager-page] [data-detail-tab=" + tab + "]";
            assertReadable(selector, true);
            ui.tap(selector);
            DeviceUi.await("The automation detail tab did not switch: " + tab, 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-selected')==='true'"));
            capture("automation-details-" + tab);
            assertNoHorizontalOverflow("[data-testid=task-manager-page] aside", "automation details/" + tab);
            assertLastControlReachable("[data-testid=task-manager-page] aside [role=tabpanel]:not([hidden])", "automation details/" + tab);
        }
        ui.tap("[data-testid=task-manager-page] button[aria-label='关闭详情'],[data-testid=task-manager-page] button[aria-label='Close details']");
        DeviceUi.await("Closing details did not return to the task list", 5000,
            () -> !ui.truth("!!document.querySelector('[data-testid=task-manager-page] [data-detail-tab=rule]')"));
    }

    private void seedFutureAutomation() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        String script = """
            import {pathToFileURL} from 'node:url';
            import {createServer} from 'node:http';
            const base=process.env.DROY_RUNTIME_DIR;
            const fixtureBridge=createServer((req,res)=>{
              if(req.url==='/memory') {res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result:{settings:{enabled:false,recallEnabled:true,automaticExtraction:false,privacyEverActivated:false},root:{authorized:false,checkedAt:0},execution:{ready:false,reason:'MEMORY_DISABLED'}}}));return;}
              if(req.url!=='/companion') {res.writeHead(404,{'content-type':'application/json'});res.end(JSON.stringify({ok:false,error:{code:'FIXTURE_ROUTE_MISSING',message:req.url}}));return;}
              res.writeHead(200,{'content-type':'application/json'});
              res.end(JSON.stringify({ok:true,result:{name:'Fixture',displayName:'Fixture',appearanceId:'default',personality:''}}));
            });
            await new Promise(resolve=>fixtureBridge.listen(0,'127.0.0.1',resolve));
            process.env.DROY_BRIDGE_PORT=String(fixtureBridge.address().port);
            let instance;
            try {
            await import(pathToFileURL(base+'/mobile/bootstrap.mjs'));
            const {runProfile}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh/lib/profile-boot.js'));
            const {createLaunchEnvironmentSnapshot}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js'));
            const {createUserMessage}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh-llm/lib/index.js'));
            instance=await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
              const {ctx}=instance,workspace=ctx.workspaceRegistry.list()[0];
              if((await ctx.sessionPersistence.list()).length!==0)throw Error('Fixture home was not empty');
              const created=await ctx.sessionController.create({sessionId:'droy-layout-fixture',workspaceId:workspace.id,agentPreset:'standard'});
              const session=ctx.sessions.get(created.sessionId);
              session.append('turn/start',{turn:1});
              session.append('user/message',createUserMessage({source:{kind:'user'},content:[{type:'text',text:'Fictional layout fixture. Never submitted to a model.'}]}),{surfaceOp:'append'});
              session.append('turn/end',{turn:1,reason:{kind:'blocked'}});
              await ctx.schedule.create(created.sessionId,{title:'Fictional layout task',prompt:'Fictional layout fixture. Never deliver in this test.',after_seconds:86400});
              await ctx.sessions.flush(ctx.sessions.get(created.sessionId));
              console.log('DROY_LAYOUT_SEEDED');
            } finally {
              try {await instance?.shutdown.shutdown(0);}
              finally {await new Promise(resolve=>fixtureBridge.close(resolve));}
            }
            """;
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libdroy_node.so", "--expose-internals", "--input-type=module", "-e", script);
        builder.directory(context.getFilesDir()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("DSH_HOME", isolatedHome.getAbsolutePath());env.put("HOME", isolatedHome.getAbsolutePath());
        env.put("DROY_RUNTIME_DIR", runtime.getAbsolutePath());env.put("DROY_NATIVE_DIR", nativeDir);env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());env.put("DSH_TELEMETRY_DISABLED", "1");
        env.put("DROY_WEB_TOKEN", "fictional-layout-fixture-token-123456789");env.put("DROY_BRIDGE_TOKEN", "fictional-layout-fixture-token-123456789");
        env.put("DROY_WEB_PORT", Integer.toString(port));env.put("DROY_BRIDGE_PORT", "9");
        env.put("DROY_LOCALE", NativeLocale.active());
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { RuntimeInstaller.copy(input, output); }
            catch (Exception error) { throw new AssertionError("Could not read the fictional layout fixture", error); }
        }, "droy-layout-fixture-output");
        reader.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();process.waitFor(5, TimeUnit.SECONDS);fail("The isolated layout fixture timed out");
        }
        reader.join(2000);
        String value = output.toString(StandardCharsets.UTF_8.name());
        assertEquals("The isolated fictional layout fixture failed: " + value.substring(Math.max(0, value.length() - 1800)), 0, process.exitValue());
        assertTrue("The fictional future automation was not seeded", value.contains("DROY_LAYOUT_SEEDED"));
    }

    private void assertPluginDetailAndMore() throws Exception {
        markButton("[data-plugin-panel]", "更多", "More");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The real experimental plugin list did not open", 5000,
            () -> ui.truth("['实验性插件','Experimental plugins'].includes(document.querySelector('[data-plugin-panel] h1')?.textContent.trim())"));
        capture("plugin-more");
        assertReadable("[data-plugin-panel] h1", true);
        assertNoHorizontalOverflow("[data-plugin-panel]", "experimental plugins");
        ui.tap("[data-plugin-panel] [data-plugin-package] button[aria-label^=查看],[data-plugin-panel] [data-plugin-package] button[aria-label^=View]");
        DeviceUi.await("The real plugin package details did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-plugin-detail]')"));
        capture("plugin-detail");
        assertReadable("[data-plugin-detail] [data-window-drag] > button", true);
        assertNoHorizontalOverflow("[data-plugin-panel]", "plugin detail");
        assertLastControlReachable("[data-plugin-detail]", "plugin detail");
        ui.tap("[data-plugin-detail] [data-window-drag] > button");
        DeviceUi.await("The plugin details did not return to the experimental list", 5000,
            () -> ui.truth("!!document.querySelector('[data-plugin-panel] h1')&&!document.querySelector('[data-plugin-detail]')"));
        markButton("[data-plugin-panel]", "返回插件", "Back to plugins");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The experimental list did not return to plugins", 5000,
            () -> ui.truth("['插件','Plugins'].includes(document.querySelector('[data-plugin-panel] h1')?.textContent.trim())"));
    }

    private void assertPresetNamesAndGuide() throws Exception {
        ui.visibleWebBounds(".rtSEdW_creatorButton");
        assertReadable(".rtSEdW_creatorButton", false);
        capture("preset-create-entry");
        JSONArray names = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-agent-preset-id]')].map(e=>({id:e.getAttribute('data-agent-preset-id'),name:e.querySelector('button[aria-pressed] span[title]').textContent.trim()}))");
        assertEquals("A fresh Droy profile must expose exactly two built-in modes", 2, names.length());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < names.length(); i++) ids.add(names.getJSONObject(i).getString("id"));
        Collections.sort(ids);
        assertEquals("The built-in modes must be Standard and Creator", List.of("cordis", "standard"), ids);
        for (int i = 0; i < names.length(); i++) {
            String selector = "[data-agent-preset-id=" + JSONObject.quote(names.getJSONObject(i).getString("id")) + "] button[aria-pressed] span[title]";
            ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").scrollIntoView({block:'center',behavior:'instant'})");
            assertReadable(selector, false);
            assertTrue("The built-in mode name is squeezed into word fragments", ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),c=document.createElement('canvas').getContext('2d');c.font=getComputedStyle(e).font;const needed=Math.max(...e.textContent.trim().split(/\\s+/).map(word=>c.measureText(word).width));"
                + "return e.getBoundingClientRect().width>=Math.min(needed,e.closest('[data-agent-preset-id]').clientWidth-32)-1})()"));
        }
        markButton(OPTIONS, "模式说明", "Mode details");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The actual mode explanation dialog did not open", 5000,
            () -> ui.truth("!!document.querySelector('[role=dialog] [data-guide-page]')"));
        ui.evaluate("document.querySelector('[data-guide-page]').closest('[role=dialog]').setAttribute('data-layout-modal','')");
        capture("mode-explanation");
        assertReadable("[data-layout-modal] h2", false);
        assertNoHorizontalOverflow("[data-layout-modal]", "mode explanation");
        JSONArray tabs = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-layout-modal] [role=tab]')].map(e=>e.id)");
        for (int i = 0; i < tabs.length(); i++) {
            String selector = "[id=" + JSONObject.quote(tabs.getString(i)) + "]";
            reachElementByTouch(selector);
            assertReadable(selector, false);
            ui.tap(selector);
            DeviceUi.await("The mode explanation tab did not switch", 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-selected')==='true'"));
            assertLastControlReachable("[data-layout-modal] [data-guide-page]:not([hidden])", "mode explanation content");
        }
        markButton("[data-layout-modal]", "关闭", "Close");
        reachElementByTouch("[data-layout-action]");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The mode explanation dialog did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-layout-modal]')"));
        markButton(OPTIONS, "模式说明", "Mode details");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The mode guide did not reopen after Close", 5000,
            () -> ui.truth("!!document.querySelector('[role=dialog] [data-guide-page]')"));
        ui.pressBack();
        DeviceUi.await("System Back did not close the mode guide", 5000,
            () -> !ui.truth("!!document.querySelector('[role=dialog] [data-guide-page]')"));
        String configuration = "[data-agent-preset-id=standard] button[data-tip]";
        ui.tap(configuration);
        DeviceUi.await("The actual preset configuration did not open", 5000,
            () -> ui.truth("!!document.querySelector('[role=dialog] pre')?.textContent.trim()"));
        ui.evaluate("document.querySelector('[role=dialog] pre').closest('[role=dialog]').setAttribute('data-layout-preset-viewer','')");
        capture("preset-configuration");
        assertReadable("[data-layout-preset-viewer] h2", false);
        assertNoHorizontalOverflow("[data-layout-preset-viewer]", "preset configuration");
        ui.evaluate("(()=>{const e=document.querySelector('[data-layout-preset-viewer] pre');e.scrollTop=e.scrollHeight})()");
        DeviceUi.await("The preset configuration's final line cannot be reached", 5000,
            () -> ui.truth("(()=>{const e=document.querySelector('[data-layout-preset-viewer] pre');return e.clientHeight>0&&e.scrollTop+e.clientHeight>=e.scrollHeight-1})()"));
        capture("preset-configuration-code-end");
        markButton("[data-layout-preset-viewer]", "关闭", "Close");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The preset configuration did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-layout-preset-viewer]')"));
        ui.visibleWebBounds(configuration);
        assertNoHorizontalOverflow(OPTIONS, "preset configuration tooltip");
        capture("preset-configuration-closed");
    }

    private void assertModelAddForm() throws Exception {
        markButton(OPTIONS, "添加模型提供商", "Add model provider");
        ui.tap("[data-layout-action]");
        DeviceUi.await("The model provider editor did not open", 5000,
            () -> ui.truth("document.querySelectorAll('.zGbnIq_addModes [role=tab]').length===2"));
        for (String mode : new String[]{"catalog", "custom"}) {
            String selector = ".zGbnIq_addModes [role=tab][id$='-" + mode + "']";
            ui.visibleWebBounds(selector);
            assertReadable(selector, false);
            ui.tap(selector);
            DeviceUi.await("The model provider mode did not switch: " + mode, 5000,
                () -> ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");"
                    + "const panel=document.getElementById(e.getAttribute('aria-controls'));return e.getAttribute('aria-selected')==='true'&&!!panel&&!panel.hidden})()"));
            capture("model-add-" + mode);
            assertNoHorizontalOverflow(OPTIONS, "add model provider/" + mode);
            assertLastControlReachable(OPTIONS, "add model provider/" + mode);
        }
        markButton(".zGbnIq_addBlock", "取消", "Cancel");
        ui.tap("[data-layout-action]");
        DeviceUi.await("Cancel did not close the model provider editor", 5000,
            () -> !ui.truth("!!document.querySelector('.zGbnIq_addModes')"));
        ui.hideKeyboard();
    }

    private void assertPluginPresetSwitcher() throws Exception {
        String selector = "[data-plugin-scope=preset] button[aria-haspopup=menu]";
        ui.visibleWebBounds(selector);
        assertReadable(selector, false);
        String selectedLabel = String.valueOf(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").textContent.trim()"));
        ui.tap(selector);
        DeviceUi.await("The session plugin preset menu did not open", 5000,
            () -> ui.truth("!!document.querySelector('[role=menu] [role=menuitemradio],[role=menu] [role=menuitem]')"));
        ui.pressBack();
        DeviceUi.await("Back did not close the session plugin preset menu", 5000,
            () -> !ui.truth("!!document.querySelector('[role=menu]')"));
        ui.visibleWebBounds(selector);
        assertReadable(selector, false);
        assertEquals("Dismissing the preset menu changed the selected preset label", selectedLabel,
            String.valueOf(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").textContent.trim()")));
        capture("plugins-preset-selector");
    }

    private void assertNestedTabs(String section) throws Exception {
        JSONArray ids = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-droy-settings-options] [role=tablist] [role=tab]')].filter(e=>e.getClientRects().length&&!e.closest('[hidden]')).map(e=>e.id)");
        for (int index = 0; index < ids.length(); index++) {
            String selector = "[id=" + JSONObject.quote(ids.getString(index)) + "]";
            ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");document.querySelectorAll('[data-layout-tablist]').forEach(e=>e.removeAttribute('data-layout-tablist'));e.closest('[role=tablist]').setAttribute('data-layout-tablist','');e.closest('[role=tablist]').scrollIntoView({block:'nearest',behavior:'instant'})})()");
            reachTabByTouch(selector, "[data-layout-tablist]");
            assertReadable(selector, false);
            ui.tap(selector);
            DeviceUi.await("A nested settings tab did not switch: " + section, 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-selected')==='true'"));
            awaitSettingsContent();
            capture("settings-" + section + "-nested-" + index);
            assertNoHorizontalOverflow(OPTIONS, "settings/" + section + "/" + index);
            assertLastControlReachable(OPTIONS, "settings/" + section + "/" + index);

        }
    }

    private void awaitSettingsContent() throws Exception {
        DeviceUi.await("The selected settings content stayed empty or busy", 15000,
            () -> ui.truth("(()=>{const e=document.querySelector('[data-droy-settings-options]');return !!e&&e.textContent.trim().length>0&&!e.querySelector('[aria-busy=true]')})()"));
        // Let React's lazy settings section and browser layout settle before geometry sampling.
        SystemClock.sleep(150);
    }

    private void reachTabByTouch(String selector, String stripSelector) throws Exception {
        for (int attempt = 0; attempt < 14; attempt++) {
            String[] previous = {null}; long[] unchangedSince = {0};
            DeviceUi.await("The settings tab strip did not stop moving", 5000, () -> {
                String current = String.valueOf(ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                    + "),c=document.querySelector(" + JSONObject.quote(stripSelector) + "),r=e.getBoundingClientRect();return JSON.stringify([c.scrollLeft,c.scrollWidth,c.clientWidth,r.left,r.right])})()"));
                if (!current.equals(previous[0])) { previous[0] = current; unchangedSince[0] = SystemClock.elapsedRealtime(); }
                return SystemClock.elapsedRealtime() - unchangedSince[0] >= 200;
            });
            JSONObject target = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),c=document.querySelector(" + JSONObject.quote(stripSelector) + ");if(!e||!c)return {};const r=e.getBoundingClientRect(),b=c.getBoundingClientRect();"
                + "return {visible:r.left>=b.left-1&&r.right<=b.right+1,left:r.left<b.left,width:r.width,stripWidth:b.width,"
                + "targetLeft:r.left,targetRight:r.right,stripLeft:b.left,stripRight:b.right,scrollLeft:c.scrollLeft,scrollWidth:c.scrollWidth,clientWidth:c.clientWidth,"
                + "delta:r.left<b.left?b.left-r.left:r.right-b.right}})()");
            assertTrue("The real settings tab disappeared: " + selector, target.has("visible"));
            assertTrue("A settings label is wider than its usable strip: " + selector + "; " + target,
                target.getDouble("width") <= target.getDouble("stripWidth") + 1);
            if (target.getBoolean("visible")) return;
            Rect strip = ui.webBounds(stripSelector);
            boolean left = target.getBoolean("left");
            float inset = Math.max(12, strip.width() * 0.16f);
            float scale = (float) (strip.width() / target.getDouble("stripWidth"));
            float distance = Math.min(strip.width() * 0.6f,
                (float) target.getDouble("delta") * scale + ViewConfiguration.get(context).getScaledTouchSlop() + 8);
            float start = left ? strip.left + inset : strip.right - inset;
            ui.swipe(start, strip.centerY(), start + (left ? distance : -distance), strip.centerY(), 300);
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_SETTINGS_TAB_TOUCH_SCROLL " + selector + " " + target + "\n");
            instrumentation.sendStatus(2, status);
        }
        fail("A settings tab cannot be reached by horizontal touch scrolling: " + selector);
    }

    private void assertReadable(String selector, boolean avoidMenu) throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");if(!e)return {errors:['missing']};"
            + "const r=e.getBoundingClientRect(),root=getComputedStyle(document.documentElement),px=n=>parseFloat(root.getPropertyValue(n))||0,errors=[];"
            + "if(r.width<=0||r.height<=0)errors.push('empty');"
            + "if(r.left<px('--droy-safe-left')-1||r.right>innerWidth-px('--droy-safe-right')+1||r.top<px('--droy-safe-top')-1||r.bottom>innerHeight-Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))+1)errors.push('system-safe-area');"
            + "const walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let node;while(node=walker.nextNode()){if(!node.textContent.trim()||node.parentElement.closest('svg,[aria-hidden=true]'))continue;"
            + "const range=document.createRange();range.selectNodeContents(node);for(const t of range.getClientRects()){if(t.width<=0||t.height<=0)continue;"
            + "if(t.left<r.left-1||t.right>r.right+1||t.top<r.top-1||t.bottom>r.bottom+1)errors.push('text-outside-label');"
            + "for(let a=node.parentElement;a;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();"
            + "if(/hidden|clip|auto|scroll/.test(s.overflowX)&&(t.left<b.left-1||t.right>b.right+1))errors.push('horizontal-text-clipping');"
            + "if(/hidden|clip|auto|scroll/.test(s.overflowY)&&(t.top<b.top-1||t.bottom>b.bottom+1))errors.push('vertical-text-clipping');}"
            + "const hit=document.elementFromPoint((t.left+t.right)/2,(t.top+t.bottom)/2);if(!hit||!(e===hit||e.contains(hit)))errors.push('text-obscured');}}"
            + (avoidMenu ? "const menu=document.querySelector('[data-droy-menu]'),m=menu?.getBoundingClientRect();if(m&&Math.min(r.right,m.right)>Math.max(r.left,m.left)&&Math.min(r.bottom,m.bottom)>Math.max(r.top,m.top))errors.push('menu-overlap');" : "")
            + "return {errors:[...new Set(errors)],label:e.textContent.trim(),rect:{left:r.left,top:r.top,width:r.width,height:r.height},viewport:{width:innerWidth,height:innerHeight}}})()");
        assertEquals("The visible title/tab is clipped or obscured: " + selector + "; " + result, 0, result.getJSONArray("errors").length());
    }

    private void assertNoHorizontalOverflow(String selector, String location) throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const p=document.querySelector(" + JSONObject.quote(selector) + ");if(!p)return {errors:['missing']};const b=p.getBoundingClientRect(),errors=[];"
            + "const root=getComputedStyle(document.documentElement),px=n=>parseFloat(root.getPropertyValue(n))||0;"
            + "if(b.left<px('--droy-safe-left')-1||b.right>innerWidth-px('--droy-safe-right')+1)errors.push('container-outside-viewport');"
            + "if(p.scrollWidth>p.clientWidth+1)errors.push('container-horizontal-overflow');"
            + "for(const e of p.querySelectorAll('h1,h2,h3,label,input,textarea,select,button')){const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
            + "if(!r.width||!r.height||s.visibility==='hidden'||e.closest('[hidden],svg')||s.clip!=='auto'||s.clipPath!=='none')continue;"
            + "if(e.closest('[role=tablist]'))continue;"
            + "if(r.left<b.left-1||r.right>b.right+1)errors.push(e.tagName.toLowerCase()+':outside-content');}"
            + "const outliers=[...p.querySelectorAll('*')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&!e.closest('[hidden],svg')&&(r.left<b.left-1||r.right>b.right+1)}).slice(0,20).map(e=>({tag:e.tagName,className:e.className,label:e.textContent.trim().slice(0,160),rect:e.getBoundingClientRect().toJSON()}));"
            + "return {errors:[...new Set(errors)],width:b.width,scrollWidth:p.scrollWidth,clientWidth:p.clientWidth,outliers}})()");
        assertEquals("Horizontal overflow in " + location + "; " + result, 0, result.getJSONArray("errors").length());
    }

    private void assertLastControlReachable(String selector, String location) throws Exception {
        boolean found = ui.truth("(()=>{document.querySelectorAll('[data-layout-last]').forEach(e=>{e.removeAttribute('data-layout-last');e.removeAttribute('data-layout-last-text')});const p=document.querySelector(" + JSONObject.quote(selector) + ");"
            + "const elements=[...p.querySelectorAll('button,input:not([type=hidden]),textarea,select,summary,a[href]')].filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
            + "return r.width>1&&r.height>1&&s.visibility!=='hidden'&&!e.closest('[hidden]')&&!(e.closest('details:not([open])')&&!e.matches('summary'))&&s.clip==='auto'&&s.clipPath==='none'});"
            + "const e=elements.at(-1)||[...p.querySelectorAll('p,li,h2,h3')].filter(e=>e.getClientRects().length).at(-1);if(!e)return false;e.setAttribute('data-layout-last','');if(!elements.length)e.setAttribute('data-layout-last-text','');e.scrollIntoView({block:'end',inline:'nearest',behavior:'instant'});return true})()");
        if (!found) return; // Read-only/empty sections can legitimately have no controls.
        reachLastControlByTouch();
        ui.awaitPaint();
        capture("last-control-" + location.replaceAll("[^a-zA-Z0-9-]", "-"));
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-layout-last]'),r=" + LAST_CONTROL_RECT + ",root=getComputedStyle(document.documentElement),px=n=>parseFloat(root.getPropertyValue(n))||0,errors=[];"
            + "if(r.top<px('--droy-safe-top')-1||r.bottom>innerHeight-Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))+1)errors.push('last-control-behind-system-bar');"
            + "for(let a=e.parentElement;a;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();if(/hidden|clip|auto|scroll/.test(s.overflowY)&&(r.top<b.top-1||r.bottom>b.bottom+1))errors.push('last-control-clipped');}"
            + "const hit=document.elementFromPoint((r.left+r.right)/2,(r.top+r.bottom)/2);if(!e.disabled&&(!hit||!(e===hit||e.contains(hit))))errors.push('last-control-obscured');"
            + "const ancestors=[];for(let a=e.parentElement;a;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();ancestors.push({tag:a.tagName,className:a.className,overflowY:s.overflowY,scrollTop:a.scrollTop,scrollHeight:a.scrollHeight,clientHeight:a.clientHeight,rect:b.toJSON()});}"
            + "return {errors:[...new Set(errors)],tag:e.tagName,textBounds:e.hasAttribute('data-layout-last-text'),label:e.textContent.trim(),rect:{top:r.top,bottom:r.bottom,left:r.left,right:r.right},ancestors}})()");
        if (result.getJSONArray("errors").length() != 0) capture("last-control-failure-" + location.replaceAll("[^a-zA-Z0-9-]", "-"));
        assertEquals("The last control cannot be scrolled into view in " + location + "; " + result, 0, result.getJSONArray("errors").length());
    }

    private void reachElementByTouch(String selector) throws Exception {
        assertTrue("The actual scroll target is missing: " + selector, ui.truth("(()=>{document.querySelectorAll('[data-layout-last]').forEach(e=>{e.removeAttribute('data-layout-last');e.removeAttribute('data-layout-last-text')});"
            + "const e=document.querySelector(" + JSONObject.quote(selector) + ");if(!e)return false;e.setAttribute('data-layout-last','');return true})()"));
        reachLastControlByTouch();
    }

    private void reachLastControlByTouch() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            String[] previous = {null}; long[] unchangedSince = {0};
            DeviceUi.await("The last control layout did not settle", 5000, () -> {
                String current = String.valueOf(ui.evaluate("(()=>{const e=document.querySelector('[data-layout-last]'),r=" + LAST_CONTROL_RECT + ",ancestors=[];"
                    + "for(let a=e.parentElement;a;a=a.parentElement)ancestors.push([a.clientHeight,a.scrollHeight,a.scrollTop]);"
                    + "return JSON.stringify([r.top,r.bottom,r.left,r.right,ancestors])})()"));
                if (!current.equals(previous[0])) { previous[0] = current; unchangedSince[0] = SystemClock.elapsedRealtime(); }
                return SystemClock.elapsedRealtime() - unchangedSince[0] >= 200;
            });
            JSONObject position = (JSONObject) ui.evaluate("(()=>{document.querySelectorAll('[data-layout-last-viewport]').forEach(e=>e.removeAttribute('data-layout-last-viewport'));"
                + "const e=document.querySelector('[data-layout-last]'),r=" + LAST_CONTROL_RECT + ",root=getComputedStyle(document.documentElement),px=n=>parseFloat(root.getPropertyValue(n))||0;"
                + "let top=px('--droy-safe-top'),bottom=innerHeight-Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom')),viewport=null;"
                + "for(let a=e.parentElement;a;a=a.parentElement){const s=getComputedStyle(a);if(/auto|scroll/.test(s.overflowY)&&a.scrollHeight>a.clientHeight+1){viewport=a;break;}}"
                + "for(let a=viewport||e.parentElement;a;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();"
                + "if(/hidden|clip|auto|scroll/.test(s.overflowY)){top=Math.max(top,b.top);bottom=Math.min(bottom,b.bottom);}}"
                + "if(viewport)viewport.setAttribute('data-layout-last-viewport','');"
                + "return {fits:r.top>=top-1&&r.bottom<=bottom+1,canScroll:!!viewport,top,bottom,height:r.height,"
                + "viewportTop:viewport?.getBoundingClientRect().top||0,viewportHeight:viewport?.getBoundingClientRect().height||0,"
                + "delta:r.top<top?r.top-top:r.bottom-bottom}})()");
            if (position.getBoolean("fits") || !position.getBoolean("canScroll")
                    || position.getDouble("height") > position.getDouble("bottom") - position.getDouble("top")) return;
            Rect bounds = ui.webBounds("[data-layout-last-viewport]");
            float scale = (float) (bounds.height() / position.getDouble("viewportHeight"));
            float top = bounds.top + (float) (position.getDouble("top") - position.getDouble("viewportTop")) * scale;
            float bottom = bounds.top + (float) (position.getDouble("bottom") - position.getDouble("viewportTop")) * scale;
            float available = bottom - top;
            if (available <= 0) return;
            float delta = (float) position.getDouble("delta");
            float distance = Math.min(available * 0.6f, Math.abs(delta) * scale + ViewConfiguration.get(context).getScaledTouchSlop() + 8);
            float start = delta > 0 ? bottom - available * 0.2f : top + available * 0.2f;
            ui.swipe(bounds.centerX(), start, bounds.centerX(), start + (delta > 0 ? -distance : distance), 350);
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_LAST_CONTROL_TOUCH_SCROLL " + position + "\n");
            instrumentation.sendStatus(2, status);
        }
    }

    private void markButton(String root, String chinese, String english) throws Exception {
        DeviceUi.await("The real UI action is missing: " + chinese, 10000, () -> ui.truth(
            "(()=>{document.querySelectorAll('[data-layout-action]').forEach(e=>e.removeAttribute('data-layout-action'));const p=document.querySelector(" + JSONObject.quote(root) + ");"
            + "const e=[...p.querySelectorAll('button')].find(e=>e.getClientRects().length&&!e.disabled&&[" + JSONObject.quote(chinese) + "," + JSONObject.quote(english)
            + "].some(label=>e.textContent.trim()===label||e.getAttribute('aria-label')===label));if(!e)return false;e.setAttribute('data-layout-action','');return true})()"));
    }

    private void openPanel(String chinese, String english, String content) throws Exception {
        openDrawer();
        ui.tap("[data-droy-sidebar] button[aria-label='" + chinese + "'],[data-droy-sidebar] button[aria-label='" + english + "']");
        DeviceUi.await("The feature panel did not open: " + english, 15000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(content) + ")"));
        awaitMainMenu();
        SystemClock.sleep(200);
    }

    private void returnFromPanel(String selector) throws Exception {
        ui.pressBack();
        DeviceUi.await("System Back did not return to chat", 5000,
            () -> !ui.truth("!!document.querySelector(" + JSONObject.quote(selector) + ")"));
        awaitMainMenu();
    }

    private void openDrawer() throws Exception {
        ui.hideKeyboard();
        if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) {
            awaitMainMenu();
            ui.tap("[data-droy-menu]");
        }
        DeviceUi.await("The sidebar did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        ui.awaitPaint();
        JSONObject drawer = (JSONObject) ui.evaluate("(()=>{if(innerHeight>480)return {errors:[]};const e=document.querySelector('[data-droy-sidebar] .hHd-Xa_root'),r=e.getBoundingClientRect(),s=getComputedStyle(document.documentElement),px=n=>parseFloat(s.getPropertyValue(n))||0,errors=[];"
            + "if(r.height<=0||r.top<px('--droy-safe-top')-1||r.bottom>innerHeight-Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))+1)errors.push('drawer-scrollport-outside-safe-area');"
            + "return {errors,rect:r.toJSON(),scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,safeTop:px('--droy-safe-top'),safeBottom:Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom'))}})()");
        assertEquals("The short drawer scroller must remain inside the system safe area: " + drawer,
            0, drawer.getJSONArray("errors").length());
    }

    private void awaitMainMenu() throws Exception {
        DeviceUi.await("The main menu remains obscured", 10000, () -> ui.truth("(()=>{"
            + "for(const title of document.querySelectorAll('[role=dialog] h2')){if(!['开始使用','Get started'].includes(title.textContent.trim()))continue;"
            + "const modal=title.closest('[role=dialog]'),close=modal.querySelector('button[aria-label=关闭],button[aria-label=Close]');if(modal.getClientRects().length&&close&&!close.disabled){close.click();return false}}"
            + "const e=document.querySelector('[data-droy-menu]');if(!e||document.querySelector('[data-droy-drawer-open=true]'))return false;const r=e.getBoundingClientRect();"
            + "return document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('[data-droy-menu]')===e})()"));
    }

    private void releaseInput() throws Exception {
        if (ui == null || ui.web() == null) return;
        WebView web = ui.web();
        ui.evaluate("document.activeElement?.blur()");
        InputMethodManager input = activity.getSystemService(InputMethodManager.class);
        instrumentation.runOnMainSync(() -> {
            View sink = new View(activity);
            sink.setFocusable(true);sink.setFocusableInTouchMode(true);
            sink.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ((ViewGroup) activity.getWindow().getDecorView()).addView(sink, new ViewGroup.LayoutParams(1, 1));
            web.clearFocus();sink.requestFocus();
            input.hideSoftInputFromWindow(web.getWindowToken(), 0);
        });
        ui.hideKeyboard();
        instrumentation.waitForIdleSync();
    }

    private void capture(String stage) throws Exception {
        if (stage.equals("plugin-page")) {
            JSONObject metrics = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,devicePixelRatio,bodyFont:getComputedStyle(document.body).fontSize,titleFont:getComputedStyle(document.querySelector('[data-plugin-panel] h1')).fontSize,dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size'),dark:document.body.hasAttribute('data-ds-dark-theme')})");
            int[] zoom = {0};
            WebView current = ui.web();
            instrumentation.runOnMainSync(() -> zoom[0] = current.getSettings().getTextZoom());
            metrics.put("webViewTextZoom", zoom[0]);metrics.put("androidFontScale", context.getResources().getConfiguration().fontScale);
            Bundle status = new Bundle();status.putString("stream", "\nDROY_LAYOUT_VIEWPORT " + metrics + "\n");instrumentation.sendStatus(2, status);
        }
        String tag = InstrumentationRegistry.getArguments().getString("layoutCaptureTag");
        if (tag == null) return;
        assertTrue("Use a simple filename for the screenshot matrix label", tag.matches("[a-z0-9-]+"));
        File directory = new File(context.getCacheDir(), "droy-layout-captures/" + tag);
        assertTrue("Could not create the synthetic layout screenshot directory", directory.isDirectory() || directory.mkdirs());
        ui.evaluate("window.__layoutCaptureFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__layoutCaptureFrame=true))");
        DeviceUi.await("The layout screenshot did not reach a painted animation frame", 5000,
            () -> ui.truth("window.__layoutCaptureFrame===true"));
        CountDownLatch painted = new CountDownLatch(1);
        WebView current = ui.web();
        instrumentation.runOnMainSync(() -> current.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        assertTrue("The WebView did not publish its current visual state", painted.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        SystemClock.sleep(100);
        String landscape = InstrumentationRegistry.getArguments().getString("layoutLandscape");
        if (landscape != null) assertTrue("The screenshot viewport left the requested orientation: " + stage,
            ui.truth(landscape.equals("true") ? "innerWidth>innerHeight" : "innerHeight>innerWidth"));
        if (Build.VERSION.SDK_INT == 30 && preferences.getString("themeColorScheme", "light").equals("dark"))
            instrumentation.runOnMainSync(() -> assertEquals("Dark web pages must clear API 30 legacy light-system-bar flags", 0,
                activity.getWindow().getDecorView().getSystemUiVisibility()
                    & (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)));
        Bitmap bitmap = ui.automation().takeScreenshot();
        assertNotNull("Android did not capture the synthetic fixture screen", bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(directory, stage + ".png"))) {
            if (landscape != null) assertTrue("The screenshot bitmap left the requested orientation: " + stage,
                landscape.equals("true") ? bitmap.getWidth() > bitmap.getHeight() : bitmap.getHeight() > bitmap.getWidth());
            assertTrue("Could not encode the synthetic fixture screenshot", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private void assertNoModelOrToolRequests() throws Exception {
        if (!isolatedHome.isDirectory()) return;
        List<Path> logs = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(isolatedHome.toPath())) {
            files.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p)).forEach(logs::add);
        }
        for (Path log : logs) for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String type = new JSONObject(line).optString("type");
            assertFalse("Layout exploration must not invoke a model or tool",
                List.of("request/header", "assistant/attempt", "assistant/message", "tool/call").contains(type));
        }
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.forEach(paths::add); }
        Collections.sort(paths);
        for (Path path : paths) {
            String relative = root.relativize(path).toString();
            if (Files.isSymbolicLink(path)) result.put(relative, "link:" + Files.readSymbolicLink(path));
            else if (Files.isDirectory(path)) result.put(relative, "directory");
            else result.put(relative, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
}

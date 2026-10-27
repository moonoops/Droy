package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real full-profile UI and attachment/export routes, using only fictional emulator history. */
@RunWith(AndroidJUnit4.class)
public final class FullDshUiDeviceTest {
    private static final String SESSION = "droy-full-ui-fixture";
    private static final String FILE_NAME = "droy-full-ui-upload.txt";
    private static final String FILE_CONTENT = "Fictional DSH upload fixture 你好";
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private File runtime, isolatedHome, preservedHome;
    private boolean homeOwned, hadOriginalHome, fixtureSeeded;
    private Map<String, String> originalFiles;
    private Boolean savedSetupCompleted;
    private boolean setupCompletionOwned;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Full DSH fixtures require a dedicated emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop the existing engine before full DSH tests", RuntimeService.canManageHistory());
        RuntimeInstaller.installCertificate(context);
        runtime = RuntimeInstaller.install(context);
        isolatedHome = new File(context.getFilesDir(), "dsh-home");
        preservedHome = new File(context.getFilesDir(), "droy-full-ui-home-backup-" + UUID.randomUUID());
        hadOriginalHome = isolatedHome.exists();
        originalFiles = snapshot(isolatedHome.toPath());
        if (hadOriginalHome) assertTrue("Could not preserve the original DSH home", isolatedHome.renameTo(preservedHome));
        homeOwned = true;
        assertTrue("Could not create the isolated full DSH home", isolatedHome.mkdirs());
        JSONObject seeded = runFixtureProfile(true);
        assertEquals("The standard fixture must be created through DSH", 1, seeded.getInt("sessions"));
        assertEquals("The fixture must belong to the real workspace", 1, seeded.getInt("accounted"));
        fixtureSeeded = true;
        android.content.SharedPreferences setup = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        savedSetupCompleted = setup.contains("completed") ? setup.getBoolean("completed", false) : null;
        assertTrue(setup.edit().putBoolean("completed", true).commit());
        setupCompletionOwned = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        ui.hideKeyboard();
        DeviceUi.await("The full DSH fixture window did not gain focus", 5000,
            () -> ui.nativeNavigationState().getBoolean("activityFocus"));
        ui.evaluate("(()=>{window.__fullUiTouches=[];for(const type of ['pointerdown','pointerup','click'])document.addEventListener(type,event=>{"
            + "window.__fullUiTouches.push({type,tag:event.target?.tagName,className:String(event.target?.className||''),menu:!!event.target?.closest('[data-droy-menu]'),trusted:event.isTrusted,pointerType:event.pointerType,"
            + "drawer:!!document.querySelector('[data-droy-drawer-open=true]'),x:event.clientX,y:event.clientY});if(window.__fullUiTouches.length>12)window.__fullUiTouches.shift()},{capture:true})})()");
    }

    @After public void tearDown() throws Exception {
        if (!homeOwned) return;
        try {
            if (activity != null) {
                try { releaseFixtureInput(); }
                finally {
                    instrumentation.runOnMainSync(activity::finish);
                    DeviceUi.await("The full DSH Activity did not finish", 10000, () -> {
                        boolean[] destroyed = {false};
                        instrumentation.runOnMainSync(() -> destroyed[0] = activity.isDestroyed());
                        return destroyed[0];
                    });
                }
            }
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Full DSH cleanup left a runtime alive", 15000, RuntimeService::canManageHistory);
            if (fixtureSeeded) {
                JSONObject stored = runFixtureProfile(false);
                assertEquals("UI exploration must preserve the fictional session", 1, stored.getInt("sessions"));
                assertEquals("UI exploration must not submit a turn", 1, stored.getInt("turns"));
                assertEquals("Only the explicitly seeded statistics sample may exist", statsFixture() ? 1 : 0, stored.getInt("modelOrToolEvents"));
                assertEquals("Any additional blank UI session must remain unsubmitted", 0, stored.getInt("extraTurns"));
                assertEquals("An additional UI session must not invoke a model or tool", 0, stored.getInt("extraModelOrToolEvents"));
            }
        } finally {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Full DSH cleanup left a runtime alive", 15000, RuntimeService::canManageHistory);
            assertTrue("Original home remains backed up until the fixture engine stops", RuntimeService.canManageHistory());
            HistoryData.deleteTree(isolatedHome.toPath());
            if (hadOriginalHome) assertTrue("Could not restore the preserved DSH home", preservedHome.renameTo(isolatedHome));
            assertEquals("Original DSH data changed during isolated full UI tests", originalFiles, snapshot(isolatedHome.toPath()));
            assertFalse("The original home backup should have been restored", preservedHome.exists());
            homeOwned = false;
            if (setupCompletionOwned) {
                android.content.SharedPreferences.Editor setup = context.getSharedPreferences("installation", Context.MODE_PRIVATE).edit();
                if (savedSetupCompleted == null) setup.remove("completed"); else setup.putBoolean("completed", savedSetupCompleted);
                assertTrue(setup.commit());
            }
        }
    }

    @Test public void fullProfileExposesPanelsCommandsUploadsExportAndNativeBrowser() throws Exception {
        openPanel("插件", "Plugins", "[data-plugin-panel]");
        returnFromPanel("[data-plugin-panel]");
        openPanel("自动化任务", "Automation tasks", "[data-testid=task-manager-page]");
        returnFromPanel("[data-testid=task-manager-page]");
        selectFixture();
        assertCommandsDiscoverable();
        assertAttachmentsIntakeAndUpload();
        assertExportStreamsRealSessionLog();
        assertBrowserEntryOpensTheSharedDshPage();
    }

    @Test public void upgradedMoreMenusAndUnsentDraftWorkInBothLanguages() throws Exception {
        android.content.SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String savedLanguage = preferences.getString("languagePreference", null);
        try {
            for (String language : new String[]{"zh", "en"}) {
                selectUpgradeLanguage(language);
                selectFixture();
                openDrawer();
                exerciseUpgradeMenu("[data-droy-sidebar] button[aria-label='更多'],[data-droy-sidebar] button[aria-label='More']", language + "-sidebar");
                selectFixture();
                exerciseUpgradeMenu("button[aria-label='更多会话视图'],button[aria-label='More conversation views']", language + "-conversation");
                selectFixture();
                ui.tap("button[aria-label='打开右侧边栏'],button[aria-label='Open right sidebar']");
                DeviceUi.await("The right tools panel did not open", 10000,
                    () -> ui.truth("!!document.querySelector('[data-sidebar-right-open]')"));
                exerciseUpgradeMenu("button[aria-label='更多工具'],button[aria-label='More tools']", language + "-tools");
                if (ui.truth("!!document.querySelector('[data-sidebar-right-open]')")) {
                    ui.pressBack();
                    DeviceUi.await("The right panel did not close", 5000,
                        () -> ui.truth("!document.querySelector('[data-sidebar-right-open]')"));
                }
                selectBlankBrowserFixture();
                String draft = "DSH upgrade unsent draft " + language;
                ui.tap("[data-composer-input][contenteditable=true]");
                DeviceUi.await("Draft editing did not open the keyboard", 10000, ui::keyboardVisible);
                instrumentation.sendStringSync(draft);
                DeviceUi.await("The real input did not accept the draft", 5000,
                    () -> ui.truth("document.querySelector('[data-composer-input]').textContent.includes(" + JSONObject.quote(draft) + ")"));
                ui.evaluate("document.activeElement?.blur()");
                ui.hideKeyboard();
                String blankId = String.valueOf(ui.evaluate("document.querySelector('[data-conversation-session][data-content-phase=hero]').getAttribute('data-conversation-session')"));
                selectFixture();
                selectBlankBrowserFixture();
                DeviceUi.await("New session did not restore the original unsent draft", 10000,
                    () -> ui.truth("document.querySelector('[data-conversation-session][data-content-phase=hero]')?.getAttribute('data-conversation-session')==="
                        + JSONObject.quote(blankId) + "&&document.querySelector('[data-composer-input]').textContent.includes(" + JSONObject.quote(draft) + ")"));
                try {
                    DeviceUi.await("The restored draft did not settle to one visible input", 15000,
                        () -> ui.truth("[...document.querySelectorAll('[data-composer-input]')].filter(e=>{const r=e.getBoundingClientRect();"
                            + "return r.width>0&&r.height>0&&getComputedStyle(e).display!=='none'&&!['hidden','collapse'].includes(getComputedStyle(e).visibility)}).length===1"));
                } catch (AssertionError failure) {
                    captureUpgrade(language + "-restored-draft");
                    Object diagnostic = ui.evaluate("[...document.querySelectorAll('[data-composer-input]')].map(e=>{const r=e.getBoundingClientRect(),ancestors=[];"
                        + "for(let p=e;p&&ancestors.length<8;p=p.parentElement){const b=p.getBoundingClientRect(),s=getComputedStyle(p);ancestors.push({"
                        + "tag:p.tagName,className:String(p.className),session:p.getAttribute('data-conversation-session'),phase:p.getAttribute('data-content-phase'),"
                        + "hidden:p.hidden,inert:p.inert,ariaHidden:p.getAttribute('aria-hidden'),display:s.display,visibility:s.visibility,opacity:s.opacity,"
                        + "rect:{left:b.left,top:b.top,width:b.width,height:b.height}})}return {editable:e.getAttribute('contenteditable'),"
                        + "rect:{left:r.left,top:r.top,width:r.width,height:r.height},ancestors}})");
                    throw new AssertionError(failure.getMessage() + "; input geometry=" + diagnostic, failure);
                }
                captureUpgrade(language + "-restored-draft");
            }
        } finally {
            android.content.SharedPreferences.Editor restore = preferences.edit();
            if (savedLanguage == null) restore.remove("languagePreference"); else restore.putString("languagePreference", savedLanguage);
            assertTrue("The original native language preference was not restored", restore.commit());
        }
    }

    private void selectUpgradeLanguage(String language) throws Exception {
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("General settings did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-settings-panel] button[aria-haspopup=menu]')].find(e=>['中文','English'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-upgrade-language','');return true})()"));
        ui.tap("[data-upgrade-language]");
        String label = language.equals("zh") ? "中文" : "English";
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&e.textContent.trim()==="
            + JSONObject.quote(label) + ");if(!e)return false;e.setAttribute('data-upgrade-language-option','');return true})()"));
        ui.tap("[data-upgrade-language-option]");
        DeviceUi.await("The selected language did not reach the real UI", 10000,
            () -> ui.truth("document.documentElement.lang.startsWith(" + JSONObject.quote(language) + ")"));
        String coding = "[role=switch][aria-label='显示代码工作视图'],[role=switch][aria-label='Show coding view']";
        if (!ui.truth("document.querySelector(" + JSONObject.quote(coding) + ")?.getAttribute('aria-checked')==='true'")) {
            ui.tap(coding);
            DeviceUi.await("Coding view did not enable its conversation views", 10000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(coding) + ")?.getAttribute('aria-checked')==='true'"));
        }
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings did not close", 5000,
            () -> ui.truth("!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void exerciseUpgradeMenu(String anchor, String evidence) throws Exception {
        ui.tap(anchor);
        DeviceUi.await("The new More menu did not open: " + evidence, 5000,
            () -> ui.truth("[...document.querySelectorAll('[role=menu]')].some(e=>e.getClientRects().length&&/浏览更多插件|Browse other plugins/.test(e.textContent))"));
        assertTrue("More menu text is clipped or outside the viewport: " + evidence, ui.truth(
            "[...document.querySelectorAll('[role=menu]')].filter(e=>e.getClientRects().length).every(menu=>{const r=menu.getBoundingClientRect();"
            + "return r.left>=0&&r.right<=innerWidth+1&&r.top>=0&&r.bottom<=innerHeight+1&&[...menu.querySelectorAll('[role=menuitem]')].every(e=>{"
            + "const b=e.getBoundingClientRect(),t=document.createRange();t.selectNodeContents(e);return [...t.getClientRects()].every(x=>x.left>=b.left-1&&x.right<=b.right+1&&x.top>=b.top-1&&x.bottom<=b.bottom+1)})})"));
        captureUpgrade(evidence);
        ui.pressBack();
        DeviceUi.await("Android Back did not dismiss the More menu: " + evidence, 5000,
            () -> ui.truth("![...document.querySelectorAll('[role=menu]')].some(e=>e.getClientRects().length)"));
        assertTrue("Dismissing More left the app", ui.nativeNavigationState().getBoolean("activityFocus"));
        ui.tap(anchor);
        DeviceUi.await("More could not be reopened", 5000,
            () -> ui.truth("[...document.querySelectorAll('[role=menuitem]')].some(e=>e.getClientRects().length&&['浏览更多插件','Browse other plugins'].includes(e.textContent.trim()))"));
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem]')].find(e=>e.getClientRects().length&&['浏览更多插件','Browse other plugins'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-upgrade-browse','');return true})()"));
        ui.tap("[data-upgrade-browse]");
        DeviceUi.await("Browse plugins did not reach the plugin list", 10000,
            () -> ui.truth("!!document.querySelector('[data-plugin-panel]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
        ui.pressBack();
        DeviceUi.await("Back from plugins did not return to the conversation", 10000,
            () -> ui.truth("!document.querySelector('[data-plugin-panel]')&&!!document.querySelector('[data-composer-input]')"));
        // Global navigation preserves the previously opened tools panel. Close
        // that layer with another Back before expecting the main menu target.
        if (ui.truth("!!document.querySelector('[data-sidebar-right-open]')")) {
            ui.pressBack();
            DeviceUi.await("Back did not close the retained tools panel", 5000,
                () -> ui.truth("!document.querySelector('[data-sidebar-right-open]')"));
        }
        awaitMainMenuHitTarget();
    }

    private void captureUpgrade(String name) throws Exception {
        DeviceUi.await("The upgrade screenshot transition did not settle: " + name, 5000,
            () -> ui.truth("!document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getComputedTiming().iterations!==Infinity)"));
        ui.awaitPaint();
        android.graphics.Bitmap image = ui.automation().takeScreenshot();
        assertNotNull(image);
        File directory = new File(context.getExternalFilesDir(null), "dsh-upgrade-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        if (name.endsWith("-restored-draft")) {
            JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const describe=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {"
                + "tag:e.tagName,id:e.id,className:String(e.className),data:Object.fromEntries([...e.attributes].filter(a=>a.name.startsWith('data-')).map(a=>[a.name,a.value])),"
                + "hidden:e.hidden,inert:e.inert,ariaHidden:e.getAttribute('aria-hidden'),role:e.getAttribute('role'),editable:e.getAttribute('contenteditable'),"
                + "display:s.display,visibility:s.visibility,opacity:s.opacity,position:s.position,zIndex:s.zIndex,overflowX:s.overflowX,overflowY:s.overflowY,"
                + "rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}}};"
                + "const selectors='[data-conversation-content],[data-conversation-session],[data-composer-seat],[data-composer-input]';"
                + "return {language:document.documentElement.lang,viewport:{width:innerWidth,height:innerHeight,scale:visualViewport?.scale},"
                + "elements:[...document.querySelectorAll(selectors)].map(e=>{const ancestors=[];for(let p=e.parentElement;p;p=p.parentElement){ancestors.push(describe(p));if(p===document.body)break}"
                + "return {...describe(e),ancestors,fallbackChain:[...e.querySelectorAll('*')].filter(n=>[...n.attributes].some(a=>a.name.startsWith('data-')&&/slot|chain|fallback/.test(a.name))"
                + "||String(n.className).toLowerCase().includes('fallback')).map(describe)}})}})()");
            Files.write(new File(directory, name + ".json").toPath(), (geometry.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }

    private boolean statsFixture() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("composerStatsFixture"));
    }

    @Test public void composerStatisticsKeepShortLabelsAndTouchableDetails() throws Exception {
        org.junit.Assume.assumeTrue("Pass composerStatsFixture=true for synthetic statistics", statsFixture());
        selectFixture();
        String activityStat = "[data-composer-stat=activity]";
        String usageStat = "[data-composer-stat=usage]";
        DeviceUi.await("Short statistics did not render", 10000, () -> ui.truth("(()=>{const a=document.querySelector('" + activityStat
            + "'),u=document.querySelector('" + usageStat + "');return !!a&&!!u&&/^[\\d.]+ tps$/.test(a.textContent.trim())&&u.textContent.trim()==='900 tok'})()"));
        assertTrue("Composer statistics overflow the viewport", ui.truth("[...document.querySelectorAll('[data-composer-stat]')].every(e=>{const r=e.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&e.scrollWidth<=e.clientWidth+1})"));
        assertTrue("Statistics must align left, center and right across the composer", ui.truth("(()=>{const d=document.querySelector('[data-composer-dock]').getBoundingClientRect(),a=document.querySelector('[data-composer-stat=activity]').getBoundingClientRect(),u=document.querySelector('[data-composer-stat=usage]').getBoundingClientRect(),c=document.querySelector('[data-droy-context-meter]').getBoundingClientRect();return Math.abs(a.left-d.left)<1&&Math.abs(u.left+u.width/2-d.left-d.width/2)<1&&Math.abs(c.right-d.right)<1&&Math.abs(a.top-u.top)<1&&Math.abs(c.top-u.top)<1})()"));
        ui.tap(activityStat + " button");
        DeviceUi.await("Speed details did not open", 5000, () -> ui.truth("!!document.querySelector('[data-session-stats-details]')"));
        ui.tap(activityStat + " button");
        DeviceUi.await("Speed details did not close", 5000, () -> ui.truth("!document.querySelector('[data-session-stats-details]')"));
        ui.tap(usageStat + " button");
        DeviceUi.await("Token details did not open", 5000, () -> ui.truth("!!document.querySelector('[data-session-stats-usage]')"));
        assertTrue("Cache hit details were lost", ui.truth("document.querySelector('[data-session-stats-usage]').textContent.includes('75%')"));
        ui.tap(usageStat + " button");
        DeviceUi.await("Token details did not close", 5000, () -> ui.truth("!document.querySelector('[data-session-stats-usage]')"));
        assertTrue("Closed statistics must not retain the touch hover background", ui.truth("[...document.querySelectorAll('[data-composer-stat] button[aria-expanded=false]')].every(e=>getComputedStyle(e).backgroundColor==='rgba(0, 0, 0, 0)')"));
        ui.awaitPaint();
        android.graphics.Bitmap image = ui.automation().takeScreenshot();
        assertNotNull(image);
        File evidence = new File(context.getExternalFilesDir(null), "composer-stats.png");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(evidence)) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }

    private void openDrawer() throws Exception {
        ui.hideKeyboard();
        if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) {
            awaitMainMenuHitTarget();
            ui.tap("[data-droy-menu]");
        }
        try {
            DeviceUi.await("The actual DSH sidebar did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "; native=" + ui.nativeNavigationState()
                + "; input=" + ui.evaluate("window.__fullUiTouches"), failure);
        }
        SystemClock.sleep(250);
    }

    private void openPanel(String chinese, String english, String content) throws Exception {
        openDrawer();
        ui.tap("[data-droy-sidebar] button[aria-label='" + chinese + "'],[data-droy-sidebar] button[aria-label='" + english + "']");
        DeviceUi.await("The DSH panel entry did not open its actual content: " + english, 15000,
            () -> ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(content)
                + ");return !!e&&e.getClientRects().length>0&&!e.closest('[inert]')})()"));
        awaitMainMenuHitTarget();
    }

    private void awaitMainMenuHitTarget() throws Exception {
        // The full profile adds an asynchronous account welcome dialog. Dismiss
        // only that known prompt through its public Close control. Also wait for
        // the drawer animation's real pointer target, not just its React state.
        try { DeviceUi.await("The closing drawer still covers the main menu", 5000,
            () -> ui.truth("(()=>{for(const title of document.querySelectorAll('[role=dialog] h2')){"
                + "if(!['开始使用','Get started'].includes(title.textContent.trim()))continue;"
                + "const modal=title.closest('[role=dialog]');if(!modal.getClientRects().length)continue;"
                + "const close=modal.querySelector('button[aria-label=关闭],button[aria-label=Close]');"
                + "if(close&&!close.disabled){close.click();return false}}"
                + "const menu=document.querySelector('[data-droy-menu]');if(!menu||document.querySelector('[data-droy-drawer-open=true]'))return false;"
                + "const r=menu.getBoundingClientRect();return document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('[data-droy-menu]')===menu})()"));
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "; hit=" + ui.evaluate("(()=>{const m=document.querySelector('[data-droy-menu]'),r=m?.getBoundingClientRect();"
                + "const e=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;return {rect:r?.toJSON(),menuStyle:m?{pointer:getComputedStyle(m).pointerEvents,z:getComputedStyle(m).zIndex}:null,"
                + "ancestors:(()=>{const a=[];for(let n=e;n&&a.length<5;n=n.parentElement)a.push({tag:n.tagName,cls:String(n.className||''),role:n.getAttribute('role')});return a})()}})()"), failure);
        }
    }

    @Test public void completedTaskReturnsWithPetOffAndOnlyForANewEnabledCompletion() throws Exception {
        android.content.SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> savedPreferences = preferences.getAll();
        Map<java.lang.reflect.Field, Object> savedProgress = new java.util.HashMap<>();
        synchronized (ToolProgress.class) {
            for (String name : new String[]{"currentTask", "currentPhase", "summary", "summaryKind", "pending", "active", "startedAt", "activeTasks"}) {
                java.lang.reflect.Field field = ToolProgress.class.getDeclaredField(name); field.setAccessible(true);
                savedProgress.put(field, field.get(null));
            }
        }
        try {
            ToolProgress.interruptAll();
            assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").putBoolean("returnToDroyOnCompletion", false).commit());
            PetOverlay.refresh(context);
            selectFixture();
            WebView originalWeb = ui.web();
            openDrawer();
            completionGoHome();
            postCompletionProgress("thinking", true);
            postCompletionProgress("completed", false);
            assertRemainsOutsideDroy("A disabled completion-return preference brought Droy forward");
            // Enabling the setting does not replay the completion already delivered while it was off.
            assertTrue(preferences.edit().remove("returnToDroyOnCompletion").commit());
            postCompletionProgress("completed", false);
            RuntimeService.refreshExecutionProgress();
            assertRemainsOutsideDroy("Repeated completion or notification refresh replayed navigation");
            postCompletionProgress("thinking", true);
            postCompletionProgress("interrupted", false);
            assertRemainsOutsideDroy("An interrupted task was treated as completed");
            postCompletionProgress("thinking", true);
            postCompletionProgress("completed", false);
            DeviceUi.await("A new completion did not return to Droy with the pet disabled", 10000,
                () -> ui.nativeNavigationState().getBoolean("activityFocus"));
            String row = "[data-droy-session-row='" + SESSION + "'][aria-selected=true]";
            DeviceUi.await("Completion did not reveal the completed task conversation", 10000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(row)
                    + ")&&!!document.querySelector('[data-composer-input][contenteditable=true]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
            AtomicReference<String> revealed = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> revealed.set(activity.getIntent().getStringExtra("petTaskId")));
            assertEquals("The returned Activity received another task", SESSION, revealed.get());
            assertSame("Completion return replaced the live conversation WebView", originalWeb, ui.web());
            assertFalse("Completion return enabled the pet", preferences.getBoolean("petEnabled", true));
            completionGoHome();
            postCompletionProgress("completed", false);
            assertRemainsOutsideDroy("Duplicate completion brought Droy forward a second time");
        } finally {
            android.content.SharedPreferences.Editor restore = preferences.edit();
            for (String key : new String[]{"petEnabled", "petExternalDisplay", "petInternalDisplay", "returnToDroyOnCompletion"}) {
                Object value = savedPreferences.get(key);
                if (value instanceof Boolean enabled) restore.putBoolean(key, enabled);
                else if (value instanceof String mode) restore.putString(key, mode); else restore.remove(key);
            }
            assertTrue("Could not restore completion preferences", restore.commit());
            synchronized (ToolProgress.class) {
                for (Map.Entry<java.lang.reflect.Field, Object> entry : savedProgress.entrySet()) entry.getKey().set(null, entry.getValue());
            }
            instrumentation.runOnMainSync(() -> MainActivity.revealTask(context, SESSION));
            DeviceUi.await("Could not restore the fixture Activity for cleanup", 10000,
                () -> ui.nativeNavigationState().getBoolean("activityFocus"));
        }
    }

    private void completionGoHome() throws Exception {
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("The completion fixture did not leave Droy", 5000,
            () -> !ui.nativeNavigationState().getBoolean("activityFocus"));
        instrumentation.getUiAutomation().waitForIdle(300, 5000);
    }

    private void assertRemainsOutsideDroy(String message) throws Exception {
        long until = SystemClock.uptimeMillis() + 1200;
        do {
            assertFalse(message, ui.nativeNavigationState().getBoolean("activityFocus"));
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < until);
    }

    private void postCompletionProgress(String phase, boolean active) throws Exception {
        java.lang.reflect.Field bridgeField = RuntimeService.class.getDeclaredField("bridge"); bridgeField.setAccessible(true);
        ProductBridge bridge = (ProductBridge) bridgeField.get(RuntimeService.instance);
        java.lang.reflect.Field tokenField = ProductBridge.class.getDeclaredField("token"); tokenField.setAccessible(true);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + bridge.getListeningPort() + "/progress").openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + tokenField.get(bridge));
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("taskId", SESSION).put("phase", phase).put("active", active)
                .put("summary", "").put("pending", JSONObject.NULL).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            assertEquals("The real progress endpoint rejected the fixture", 200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream(); RuntimeInstaller.copy(input, response);
                assertTrue(new JSONObject(response.toString(StandardCharsets.UTF_8.name())).getBoolean("ok"));
            }
        } finally { connection.disconnect(); }
    }

    private void returnFromPanel(String selector) throws Exception {
        ui.pressBack();
        DeviceUi.await("Android Back did not return the global panel to chat", 10000,
            () -> ui.truth("!document.querySelector(" + JSONObject.quote(selector)
                + ")&&!!document.querySelector('[data-composer-input]')"));
        awaitMainMenuHitTarget();
    }

    private void selectFixture() throws Exception {
        openDrawer();
        String row = "[data-droy-session-row='" + SESSION + "']";
        if (!ui.truth("!!document.querySelector(" + JSONObject.quote(row) + ")"))
            ui.tap("[data-droy-sidebar] [role=treeitem][aria-expanded=false]");
        ui.tap(row + " [data-droy-session-surface]");
        DeviceUi.await("The standard DSH session did not open", 15000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(row + "[aria-selected=true]")
                + ")&&!!document.querySelector('[data-composer-input][contenteditable=true]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
        ui.hideKeyboard();
    }

    private void assertCommandsDiscoverable() throws Exception {
        ui.tap("[data-droy-composer-add]");
        DeviceUi.await("The official command menu did not open", 5000,
            () -> ui.truth("document.querySelectorAll('[data-trigger-menu] [role=option]').length>0"));
        // Read the real registered/filtered menu. Expected public commands are
        // acceptance criteria, not a synthetic replacement command catalog.
        // DSH omits the command alias when its label differs only in case
        // (for example English "Goal"); Chinese labels retain the raw alias.
        JSONArray names = (JSONArray) ui.evaluate("[...document.querySelectorAll('[data-trigger-menu] [role=option] span')].filter(e=>"
            + "[...e.classList].some(c=>c.endsWith('_itemName')||c.endsWith('_itemAlias'))).map(e=>e.textContent.trim())");
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < names.length(); i++) actual.add(names.getString(i).toLowerCase(java.util.Locale.ROOT));
        for (String command : new String[]{"goal", "plan", "feedback", "compact", "permission", "model", "export", "file"})
            assertTrue("The standard-session menu omitted /" + command + ": " + actual, actual.contains(command));
        ui.tap("[data-droy-composer-add]");
        DeviceUi.await("The command menu did not dismiss", 5000,
            () -> ui.truth("!document.querySelector('[data-trigger-menu]')"));
    }

    private void assertAttachmentsIntakeAndUpload() throws Exception {
        assertTrue("The standard composer omitted its native file input", ui.truth(
            "!!document.querySelector('[data-composer-card] input[type=file]:not(:disabled)')"));
        ui.evaluate("(()=>{window.__fullUiUpload='pending';const c=document.createElement('canvas');c.width=24;c.height=16;"
            + "const g=c.getContext('2d');g.fillStyle='#247be0';g.fillRect(0,0,24,16);c.toBlob(blob=>{try{"
            + "if(!blob)throw Error('JPEG fixture encoding failed');const transfer=new DataTransfer();"
            + "transfer.items.add(new File([blob],'droy-full-ui-image.jpg',{type:'image/jpeg'}));"
            + "transfer.items.add(new File([" + JSONObject.quote(FILE_CONTENT) + "]," + JSONObject.quote(FILE_NAME) + ",{type:'text/plain'}));"
            + "const input=document.querySelector('[data-composer-card] input[type=file]');input.files=transfer.files;"
            + "input.dispatchEvent(new Event('change',{bubbles:true}));window.__fullUiUpload='dispatched';"
            + "}catch(error){window.__fullUiUpload='error:'+error.message}},'image/jpeg');})()");
        DeviceUi.await("The original composer file event failed", 5000,
            () -> "dispatched".equals(ui.evaluate("window.__fullUiUpload")));
        DeviceUi.await("The original composer did not render the JPEG preview", 10000,
            () -> ui.truth("[...document.querySelectorAll('[data-composer-card] img')].some(e=>e.complete&&e.naturalWidth===24&&e.naturalHeight===16)"));
        DeviceUi.await("The original composer did not render the file attachment", 10000,
            () -> ui.truth("document.querySelector('[data-composer-card]')?.textContent.includes(" + JSONObject.quote(FILE_NAME) + ")"));
        byte[] bytes = FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
        String digest = RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        File stored = new File(isolatedHome, "attachments/v1/files/" + digest.substring(0, 2) + "/" + digest + "/" + FILE_NAME);
        DeviceUi.await("The real DSH upload route did not durably store the selected text file", 15000, stored::isFile);
        assertArrayEquals("The actual attachment store changed the uploaded bytes", bytes, Files.readAllBytes(stored.toPath()));
        // Upstream image drafts intentionally upload only with a submitted prompt.
        // This test stops at the real preview, avoiding any model invocation.
    }

    private void assertExportStreamsRealSessionLog() throws Exception {
        int port = RuntimeService.webPort;
        String address = "http://127.0.0.1:" + port + "/api/session.export?sessionId=" + SESSION + "&includeDescendants=true";
        AtomicReference<String> cookie = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> cookie.set(CookieManager.getInstance().getCookie(address)));
        assertNotNull("The live WebView has no authenticated DSH session", cookie.get());
        HttpURLConnection probe = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        probe.setRequestMethod("HEAD"); probe.setRequestProperty("Cookie", cookie.get());
        probe.setConnectTimeout(10000); probe.setReadTimeout(10000); probe.setInstanceFollowRedirects(false);
        try {
            assertEquals("The actual DSH export route failed", 200, probe.getResponseCode());
            assertEquals("The export endpoint did not return ZIP content", "application/zip", probe.getContentType());
        } finally { probe.disconnect(); }
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        long count = ChatDownload.copy(address, port, cookie.get(), archive,
            () -> RuntimeService.ready && RuntimeService.webPort == port);
        assertTrue("Session export returned no archive", count > 0);
        boolean foundLog = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().matches("session(?:\\.v[0-9]+)?\\.jsonl")) {
                    ByteArrayOutputStream log = new ByteArrayOutputStream();
                    RuntimeInstaller.copy(zip, log);
                    String text = log.toString(StandardCharsets.UTF_8.name());
                    assertTrue("The ZIP log is not the fictional session", text.contains(SESSION));
                    assertTrue("The ZIP lost the fictional stored message", text.contains("Fictional full DSH UI fixture."));
                    foundLog = true;
                }
                zip.closeEntry();
            }
        }
        assertTrue("The exported ZIP has no canonical session log", foundLog);
    }

    private void assertBrowserEntryOpensTheSharedDshPage() throws Exception {
        ui.openPermissions();
        ui.click("[data-droy-settings-section=droy-permissions] details summary, .droy-advanced summary");
        ui.tap("[data-droy-action=openBrowser]");
        DeviceUi.await("The browser entry did not reveal the real DSH browser page", 10000,
            () -> ui.truth("(()=>{const roots=[...document.querySelectorAll('.SB_kFW_root')].filter(e=>e.getClientRects().length);"
                + "return roots.length===1&&roots[0].querySelectorAll('.SB_kFW_toolbar').length===1"
                + "&&!!roots[0].querySelector('.SB_kFW_address')&&!document.querySelector('[data-shortcut-modal=settings]')})()"));
        assertTrue("The shared browser opened outside the existing Activity", ui.nativeNavigationState().getBoolean("activityFocus"));
        assertTrue("The shared browser contains retired handover or duplicate browser controls", ui.truth(
            "!document.querySelector('[data-droy-native-browser]')&&![...document.querySelectorAll('button')].some(e=>"
                + "['用户接管','允许助手继续','在 Droy 浏览器中继续'].includes(e.textContent.trim()))"));
        assertFalse("An idle browser tab must not obscure DSH's start page with an empty native surface", hasBrowserSurface());
    }

    // Browser regressions share this fixture's preserved home and zero-model assertions.
    DeviceUi browserFixtureUi() { return ui; }
    MainActivity browserFixtureActivity() { return activity; }
    void selectBrowserFixture() throws Exception { selectFixture(); }
    void selectBlankBrowserFixture() throws Exception {
        openDrawer();
        ui.tap("[data-droy-sidebar] button[aria-label='新建会话'],[data-droy-sidebar] button[aria-label='New session']");
        ui.awaitPage();
        awaitMainMenuHitTarget();
        DeviceUi.await("The browser fixture must use an unsubmitted blank conversation",10000,()->ui.truth(
            "!!document.querySelector('[data-content-phase=hero] [data-composer-input][contenteditable=true]')"
            + "&&!document.querySelector('[data-droy-drawer-open=true]')"));
        ui.hideKeyboard();
    }
    private boolean hasBrowserSurface() {
        boolean[] found = {false};
        instrumentation.runOnMainSync(() -> found[0] = findSurface(activity.getWindow().getDecorView()));
        return found[0];
    }
    private static boolean findSurface(View view) {
        if (view instanceof android.view.SurfaceView && view.isShown()) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (findSurface(group.getChildAt(i))) return true;
        return false;
    }

    private void releaseFixtureInput() throws Exception {
        if (ui == null) return;
        WebView view = ui.web();
        if (view == null) return;
        ui.evaluate("document.activeElement?.blur()");
        InputMethodManager input = activity.getSystemService(InputMethodManager.class);
        assertNotNull("The fixture cannot verify input cleanup without Android's input manager", input);
        instrumentation.runOnMainSync(() -> {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View focusSink = new View(activity);
            focusSink.setTag("droy-test-input-cleanup");
            focusSink.setFocusable(true);
            focusSink.setFocusableInTouchMode(true);
            focusSink.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            decor.addView(focusSink, new ViewGroup.LayoutParams(1, 1));
            view.clearFocus();
            assertTrue("The disposing fixture could not move native focus away from its WebView", focusSink.requestFocus());
            input.hideSoftInputFromWindow(view.getWindowToken(), 0);
        });
        ui.hideKeyboard();
        instrumentation.waitForIdleSync();
        DeviceUi.await("Android retained the disposing fixture WebView's active input connection", 5000, () -> {
            boolean[] inactive = {false};
            instrumentation.runOnMainSync(() -> inactive[0] = !input.isActive(view));
            return inactive[0];
        });
        // The focus sink stays attached until Activity destruction; removing it
        // here could return native focus to the WebView before finish completes.
    }

    private JSONObject runFixtureProfile(boolean seed) throws Exception {
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
                const {createUserMessage,createAssistantMessage}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh-llm/lib/index.js'));
                instance=await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
                  const {ctx}=instance,workspace=ctx.workspaceRegistry.list()[0];
                  if(process.env.DROY_FULL_UI_SEED==='1') {
                    if((await ctx.sessionPersistence.list()).length!==0)throw Error('Fixture home was not empty');
                    const id='droy-full-ui-fixture';
                    // The full profile emits creation events. Its controller must
                    // establish the persistence writer before publishing those events.
                    const created=await ctx.sessionController.create({sessionId:id,workspaceId:workspace.id,agentPreset:'standard'});
                    if(created.sessionId!==id)throw Error('Fixture controller returned another session');
                    const session=ctx.sessions.get(id);
                    if(!session)throw Error('Fixture controller did not publish the live session');
                    session.append('turn/start',{turn:1});
                    session.append('user/message',createUserMessage({source:{kind:'user'},content:[{type:'text',text:'Fictional full DSH UI fixture. Never submitted to a model.'}]}),{surfaceOp:'append'});
                    if(process.env.DROY_COMPOSER_STATS_FIXTURE==='1') {
                      session.append('request/context',{contextWindow:10000});
                      session.append('step/start',{turn:1,step:1});
                      const first=Date.now();
                      await new Promise(resolve=>setTimeout(resolve,100));
                      session.append('assistant/message',{turn:1,step:1,
                        message:createAssistantMessage({source:{provider:'fictional',model:'fixture'},content:[{type:'text',text:'Synthetic statistics sample.'}]}),
                        stream:[{type:'chunk',time:first,chunk:{type:'text-delta',text:'Synthetic statistics sample.'}}],
                        usage:{inputTokens:200,cacheReadTokens:600,cacheWriteTokens:0,outputTokens:100,totalTokens:900}},{surfaceOp:'append'});
                      session.append('step/end',{turn:1,step:1});
                    }
                    session.append('turn/end',{turn:1,reason:{kind:'blocked'}});
                    ctx.sessionTitle.rename(session,'Full DSH UI fixture');
                    await ctx.sessions.flush(session);
                  }
                  const allHeaders=(await ctx.sessionPersistence.list()).map(s=>s.header);
                  const headers=allHeaders.filter(h=>h.id.startsWith('droy-full-ui-'));
                  let turns=0,modelOrToolEvents=0,extraTurns=0,extraModelOrToolEvents=0;
                  for(const header of allHeaders) {
                    const handle=await ctx.sessionPersistence.open(header.id,'read');
                    try {
                      const {events}=await handle.read();
                      const turnCount=events.filter(e=>e.type==='turn/start').length;
                      const requestCount=events.filter(e=>['request/header','assistant/attempt','assistant/message','tool/call'].includes(e.type)).length;
                      if(header.id.startsWith('droy-full-ui-')) {turns+=turnCount;modelOrToolEvents+=requestCount;}
                      else {extraTurns+=turnCount;extraModelOrToolEvents+=requestCount;}
                    } finally {await handle.close();}
                  }
                  console.log('DROY_FULL_UI_RESULT '+JSON.stringify({sessions:headers.length,workspaces:ctx.workspaceRegistry.list().length,accounted:workspace.sessionIds.filter(id=>id.startsWith('droy-full-ui-')).length,turns,modelOrToolEvents,extraTurns,extraModelOrToolEvents}));
                } finally {
                  try {await instance?.shutdown.shutdown(0);}
                  finally {await new Promise(resolve=>fixtureBridge.close(resolve));}
                }
                """;
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libdroy_node.so", "--expose-internals", "--input-type=module", "-e", script);
        builder.directory(context.getFilesDir()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("DSH_HOME", isolatedHome.getAbsolutePath());
        env.put("HOME", isolatedHome.getAbsolutePath());
        env.put("DROY_RUNTIME_DIR", runtime.getAbsolutePath());
        env.put("DROY_NATIVE_DIR", nativeDir);
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("DSH_TELEMETRY_DISABLED", "1");
        env.put("DROY_WEB_TOKEN", "fictional-full-ui-fixture-token-123456789");
        env.put("DROY_BRIDGE_TOKEN", "fictional-full-ui-fixture-token-123456789");
        env.put("DROY_WEB_PORT", Integer.toString(port));
        env.put("DROY_BRIDGE_PORT", "9");
        env.put("DROY_FULL_UI_SEED", seed ? "1" : "0");
        env.put("DROY_COMPOSER_STATS_FIXTURE", statsFixture() ? "1" : "0");
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { RuntimeInstaller.copy(input, output); }
            catch (Exception error) { throw new AssertionError("Could not read the fictional fixture process", error); }
        }, "droy-full-ui-fixture-output");
        reader.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();process.waitFor(5, TimeUnit.SECONDS);
            fail("The isolated public-API DSH fixture timed out");
        }
        reader.join(2000);
        String value = output.toString(StandardCharsets.UTF_8.name());
        // Only this process's isolated fictional profile contributes output.
        // Bound it so a fixture bootstrap failure remains useful to diagnose.
        assertEquals("The isolated public-API DSH fixture failed: "
                + value.substring(Math.max(0, value.length() - 2500)), 0, process.exitValue());
        String prefix = "DROY_FULL_UI_RESULT ";
        int marker = value.lastIndexOf(prefix);
        assertTrue("The DSH fixture did not report its safe session counters", marker >= 0);
        return new JSONObject(value.substring(marker + prefix.length()).split("\\R", 2)[0]);
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

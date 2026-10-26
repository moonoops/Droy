package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Fresh-install checks in an emulator or an explicitly selected development-app fixture. */
@RunWith(AndroidJUnit4.class)
public final class SetupFlowDeviceTest {
    private static final String ROOT = "[data-droy-setup]";
    private static final String NEXT = "[data-droy-setup-next]";
    private static final String MODEL_TEST = "[data-droy-setup-model-test]";
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences product, installation, wake;
    private Map<String, ?> originalProduct, originalInstallation, originalWake;
    private MainActivity activity;
    private DeviceUi ui;
    private File home, preserved;
    private Map<String, String> originalFiles;
    private boolean owned, hadHome, rotated, passed;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        boolean emulator = Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone");
        boolean developmentPhone = context.getPackageName().equals("io.droyagent.app.dev")
            && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && "true".equals(InstrumentationRegistry.getArguments().getString("setupDevelopmentPhoneFixture"));
        assertTrue("Installation fixtures require an emulator or an explicitly selected development app", emulator || developmentPhone);
        assertTrue("Stop tasks and the engine before installation tests", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt audio capture", AudioCapture.get(context).uiStatus().isNull("active"));
        product = ProductPermissions.preferences(context);
        installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        wake = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        originalProduct = new HashMap<>(product.getAll());
        originalInstallation = new HashMap<>(installation.getAll());
        originalWake = new HashMap<>(wake.getAll());
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "setup-flow-home-backup-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        owned = true;
        assertTrue(product.edit().clear().commit());
        assertTrue(installation.edit().clear().commit());
        SetupManager.initialize(context);
        JSONObject fresh = SetupManager.status(context);
        assertTrue("An empty installation must enter the wizard", fresh.getBoolean("required"));
        assertEquals(1, fresh.getInt("step"));
        assertFalse("Fresh installation already selected a permission profile", fresh.getBoolean("modeSelected"));
        for (String key : SetupManager.capabilityKeys(context))
            assertFalse("Fresh setup enabled " + key, ProductPermissions.allowed(product, key));
        Bundle args = InstrumentationRegistry.getArguments();
        String theme = args.getString("layoutTheme", "light");
        assertTrue("Unsupported theme fixture", theme.equals("light") || theme.equals("dark"));
        assertTrue(product.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off")
            .putString("petInternalDisplay", "off").putBoolean("backgroundRuntimeEnabled", false)
            .putBoolean(BackgroundRuntime.STOPPED, false).putBoolean("darkTheme", theme.equals("dark")).commit());
        assertTrue(wake.edit().putBoolean("enabled", false).commit());
        File profile = new File(home, "profiles/mobile");
        assertTrue(profile.mkdirs());
        File provider = new File(home, "setup-fixture.mjs");
        Files.write(provider.toPath(), SetupFixtureProvider.source().getBytes(StandardCharsets.UTF_8));
        JSONArray patch = new JSONArray().put(new JSONObject().put("id", "agent-default-model")
            .put("config", new JSONObject().put("provider", SetupFixtureProvider.PROVIDER).put("model", SetupFixtureProvider.MODEL)))
            .put(new JSONObject().put("insert", new JSONArray().put(new JSONObject().put("id", "setup-local-fixture")
                .put("name", provider.getAbsolutePath()))));
        String locale = args.getString("setupLocale");
        if (locale != null) {
            assertTrue("Unsupported installation language fixture", locale.equals("zh") || locale.equals("en"));
            assertTrue(product.edit().putString("languagePreference", locale).commit());
            patch.put(new JSONObject().put("id", "locale").put("config", new JSONObject().put("preference", locale)));
        }
        Files.write(new File(profile, "cordis.patch.yml").toPath(), patch.toString().getBytes(StandardCharsets.UTF_8));
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated = true;
        launch();
        awaitStep(1);
        DeviceUi.await("Local installation model fixture did not initialize", 20000,
            () -> new File(home, "setup-fixture-ready.json").isFile());
        boolean landscape = "true".equals(args.getString("layoutLandscape"));
        assertTrue(automation.setRotation(landscape ? UiAutomation.ROTATION_FREEZE_90 : UiAutomation.ROTATION_FREEZE_0));
        DeviceUi.await("The requested installation orientation did not apply", 5000,
            () -> ui.truth(landscape ? "innerWidth>innerHeight" : "innerHeight>innerWidth"));
        configureMaximumTextFont();
    }

    @After public void restore() throws Exception {
        if (!owned) return;
        try {
            if (!passed && ui != null && activity != null && !activity.isDestroyed()) {
                try { capture("failure"); }
                catch (Exception failure) { report("Could not capture failed installation state: " + failure.getClass().getSimpleName()); }
            }
            if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
            DeviceUi.await("Installation fixture cleanup left the engine running", 25000, RuntimeService::canManageHistory);
        } finally {
            if (rotated) instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .setRotation(UiAutomation.ROTATION_UNFREEZE);
            assertTrue("Keep the original home backup until the engine stops", RuntimeService.canManageHistory());
            HistoryData.deleteTree(home.toPath());
            if (hadHome) assertTrue("Could not restore the original DSH home", preserved.renameTo(home));
            assertEquals("Installation tests changed the original DSH home", originalFiles, snapshot(home.toPath()));
            restorePreferences(product, originalProduct);
            restorePreferences(installation, originalInstallation);
            restorePreferences(wake, originalWake);
            owned = false;
        }
    }

    @Test public void dialogueCompletesSixPagesAndResumesWithoutGrantingPhoneCapabilities() throws Exception {
        captureAndCheck("welcome");
        selectMode("dialogue");
        inspectEmbeddedModelSettings();
        testModel();
        next(3);
        captureAndCheck("environment");
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        captureAndCheck("personalization");
        type("#droy-setup-name", "Momo");
        capture("personalization-keyboard");
        type("#droy-setup-address", "Friend");
        type("#droy-setup-personality", "Calm and clear.");
        ui.hideKeyboard();
        DeviceUi.await("The web setup did not receive the closed keyboard insets", 5000,
            () -> ui.truth("document.documentElement.getAttribute('data-droy-ime-visible')!=='true'"
                + "&&parseFloat(document.documentElement.style.getPropertyValue('--droy-ime-bottom')||'0')===0"));
        ui.awaitPaint();
        next(5);
        assertEquals("Momo", product.getString("petName", ""));
        assertEquals("Friend", product.getString("userAddress", ""));
        assertEquals("Calm and clear.", product.getString("companionPersonality", ""));
        restart();
        awaitStep(5);
        assertEquals("Interruption restarted model checks", 2, modelEvents().size());
        verifySelectedCapabilities();
        assertTrue("Dialogue must complete without Android Use permissions", SetupManager.status(context).getBoolean("canComplete"));
        for (String key : SetupManager.capabilityKeys(context))
            assertFalse("Dialogue setup enabled " + key, ProductPermissions.allowed(product, key));
        captureAndCheck("permissions-dialogue");
        next(6);
        captureAndCheck("welcome-use");
        finishWizard();
        assertModelIsolation();
        Map<String, ?> configured = new HashMap<>(product.getAll());
        SetupManager.initialize(context);
        assertEquals("Reinitialization overwrote saved choices", configured, product.getAll());
        restart();
        awaitChatReady();
        assertTrue(SetupManager.status(context).getBoolean("completed"));
        assertFalse("A completed installation reopened the wizard", ui.truth("!!document.querySelector('" + ROOT + "')"));
        assertEquals("Restart sent another model request", 2, modelEvents().size());
        passed = true;
    }

    @Test public void assistantWithoutAccessibilityCannotCompleteTheWizard() throws Exception {
        assertFalse("Run this case with system accessibility disabled before instrumentation",
            io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        selectMode("assistant");
        testModel();
        next(3);
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        next(5);
        verifySelectedCapabilities();
        JSONObject state = SetupManager.status(context);
        assertFalse("Missing accessibility was reported as installation success", state.getBoolean("canComplete"));
        if (!ui.truth("document.querySelector('" + NEXT + "').disabled")) {
            ui.tap(NEXT);
            DeviceUi.await("The missing permission did not produce actionable feedback", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-setup-error]')"));
        }
        awaitStep(5);
        assertThrows(IllegalStateException.class, () -> SetupManager.complete(context));
        assertFalse(SetupManager.status(context).getBoolean("completed"));
        captureAndCheck("permissions-missing-accessibility");
        assertModelIsolation();
        passed = true;
    }

    @Test public void assistantPracticeChecksRealObservationInputClickAndReadback() throws Exception {
        assertTrue("Run this case with system accessibility enabled before instrumentation",
            io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        selectMode("assistant");
        testModel();
        next(3);
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        next(5);
        assertFalse("Granting accessibility cannot substitute for the operation test", SetupManager.status(context).getBoolean("canComplete"));
        ui.tap("[data-droy-setup-device-test]");
        DeviceUi.await("The actual operation practice did not pass", 25000,
            () -> SetupManager.status(context).getJSONObject("deviceVerification").optBoolean("passed"));
        awaitStep(5);
        verifySelectedCapabilities();
        JSONObject state = SetupManager.status(context);
        assertTrue("Selected capabilities did not become ready", state.getBoolean("canComplete"));
        assertEquals("accessibility", state.getJSONObject("deviceVerification").getString("channel"));
        DeviceUi.await("Practice left an active execution indicator", 5000,
            () -> !ToolProgress.current(context).optBoolean("active"));
        captureAndCheck("permissions-operation-verified");
        next(6);
        finishWizard();
        assertModelIsolation();
        passed = true;
    }

    private void launch() throws Exception {
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        awaitChatReady();
    }

    private void awaitChatReady() throws Exception {
        DeviceUi.await("The DSH installation page did not become ready: " + RuntimeService.status, 120000,
            () -> RuntimeService.ready && ui.web() != null && ui.nativeUiReady() && !ui.loadingOverlayShown()
                && ui.truth("typeof window.DroyMobile?.handleBack==='function'&&document.documentElement.dataset.droyUiReady==='true'"));
    }

    private void restart() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
        DeviceUi.await("The fixture engine did not stop for restart", 25000, RuntimeService::canManageHistory);
        launch();
    }

    private void awaitStep(int step) throws Exception {
        try {
            DeviceUi.await("Installation did not reach step " + step, 15000,
                () -> ui.truth("document.querySelector('" + ROOT + "')?.getAttribute('data-droy-setup-step')==='" + step + "'"));
        } catch (AssertionError failure) {
            JSONObject state = SetupManager.status(context);
            report("DROY_SETUP_TRANSITION " + new JSONObject().put("expectedStep", step).put("nativeStep", state.getInt("step"))
                .put("completed", state.getBoolean("completed")).put("source", state.getString("source"))
                .put("clicks", ui.evaluate("window.__setupClicks||[]")).put("completeRequests", ui.evaluate("window.__setupCompleteRequests||0")));
            throw failure;
        }
    }

    private void selectMode(String mode) throws Exception {
        ui.evaluate("window.__setupClicks=[];window.__setupCompleteRequests=0;const original=window.fetch;window.fetch=function(...args){if(String(args[0]).endsWith('/droy/setup/complete'))window.__setupCompleteRequests++;return original.apply(this,args)};document.addEventListener('click',e=>{const b=e.target.closest?.('[data-droy-setup-next],[data-droy-setup-finish]');if(b)window.__setupClicks.push({step:document.querySelector('[data-droy-setup]')?.getAttribute('data-droy-setup-step'),action:b.hasAttribute('data-droy-setup-finish')?'finish':'next',trusted:e.isTrusted,detail:e.detail,time:e.timeStamp})},true)");
        ui.tap("[data-droy-setup-mode=" + mode + "]");
        next(2);
        assertEquals(mode, SetupManager.status(context).getString("mode"));
        assertTrue(SetupManager.status(context).getBoolean("modeSelected"));
    }

    private void next(int expected) throws Exception {
        DeviceUi.await("The installation next action remained disabled", 15000,
            () -> ui.truth("!!document.querySelector('" + NEXT + "')&&!document.querySelector('" + NEXT + "').disabled"));
        ui.tap(NEXT);
        awaitStep(expected);
    }

    private void inspectEmbeddedModelSettings() throws Exception {
        String panel = "[data-droy-setup-model-settings]";
        DeviceUi.await("The shared DSH model provider directory did not load", 15000,
            () -> ui.truth("Array.from(document.querySelectorAll('" + panel + " button')).some(b=>!b.disabled&&['Add model provider','添加模型提供商'].includes(b.textContent.trim()))"));
        assertFalse("Embedding models must not open a second settings shell", ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        capture("existing-model-settings");
        ui.evaluate("Array.from(document.querySelectorAll('" + panel + " button')).find(b=>!b.disabled&&['Add model provider','添加模型提供商'].includes(b.textContent.trim())).setAttribute('data-droy-test-add-provider','true')");
        ui.tap("[data-droy-test-add-provider]");
        DeviceUi.await("DSH did not open a model provider configuration form", 10000,
            () -> ui.truth("!!document.querySelector('" + panel + " select[aria-label]')&&!!document.querySelector('" + panel + " input')"));
        capture("existing-model-provider-form");
        ui.evaluate("Array.from(document.querySelectorAll('" + panel + " button')).find(b=>!b.disabled&&['Cancel','取消'].includes(b.textContent.trim())).setAttribute('data-droy-test-cancel-provider','true')");
        ui.tap("[data-droy-test-cancel-provider]");
        awaitStep(2);
    }

    private void testModel() throws Exception {
        DeviceUi.await("The local model did not become selectable", 15000,
            () -> ui.truth("!!document.querySelector('" + MODEL_TEST + "')&&!document.querySelector('" + MODEL_TEST + "').disabled"));
        captureAndCheck("models");
        ui.tap(MODEL_TEST);
        DeviceUi.await("The local model connection and tool protocol did not verify", 20000, () -> {
            JSONObject model = SetupManager.status(context).getJSONObject("modelVerification");
            return model.optBoolean("passed") && model.optBoolean("toolsPassed");
        });
        DeviceUi.await("The model test UI did not settle", 5000,
            () -> ui.truth("!!document.querySelector('" + NEXT + "')&&!document.querySelector('" + NEXT + "').disabled"));
        assertEquals("One model test should make exactly two short local requests", 2, modelEvents().size());
        captureAndCheck("models-verified");
    }

    private void verifySelectedCapabilities() throws Exception {
        ui.tap("[data-droy-setup-verify]");
        DeviceUi.await("Installation verification UI did not settle", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-setup-verify]')&&!document.querySelector('[data-droy-setup-verify]').disabled"));
        DeviceUi.await("Installation checks were not rendered", 5000,
            () -> ui.truth("document.querySelectorAll('[data-droy-setup-check]').length>0"));
    }

    private void finishWizard() throws Exception {
        ui.tap("[data-droy-setup-finish]");
        DeviceUi.await("Installation did not complete", 15000,
            () -> SetupManager.status(context).getBoolean("completed") && !ui.truth("!!document.querySelector('" + ROOT + "')"));
    }

    private void type(String selector, String value) throws Exception {
        ui.awaitPaint();
        try { ui.showKeyboard(selector); }
        catch (AssertionError failure) {
            report("DROY_SETUP_INPUT_GEOMETRY " + ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),s=e&&getComputedStyle(e),r=document.documentElement;return {ime:r.getAttribute('data-droy-ime-visible'),imeBottom:r.style.getPropertyValue('--droy-ime-bottom'),safeTop:r.style.getPropertyValue('--droy-safe-top'),viewport:visualViewport&&{height:visualViewport.height,offsetTop:visualViewport.offsetTop,scale:visualViewport.scale},rootHeight:document.querySelector('[data-droy-setup]')?.getBoundingClientRect().height,style:s&&{height:s.height,minHeight:s.minHeight,margin:s.scrollMarginBlock,zoom:s.zoom},landscape:matchMedia('(orientation:landscape)').matches}})()"));
            throw failure;
        }
        DeviceUi.await("The installation field did not receive touch focus", 5000,
            () -> ui.truth("document.activeElement===document.querySelector(" + JSONObject.quote(selector) + ")"));
        int existing = ((String) ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")).length();
        if (existing > 0) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END);
            for (int index = 0; index < existing; index++) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
        }
        instrumentation.sendStringSync(value);
        DeviceUi.await("Installation input did not accept actual keyboard events", 5000,
            () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")));
    }

    private List<JSONObject> modelEvents() throws Exception {
        File path = new File(home, "setup-model-events.jsonl");
        List<JSONObject> rows = new ArrayList<>();
        if (path.isFile()) for (String line : Files.readAllLines(path.toPath()))
            if (!line.isBlank()) rows.add(new JSONObject(line));
        return rows;
    }

    private void assertModelIsolation() throws Exception {
        List<JSONObject> calls = modelEvents();
        assertEquals("The installation made unexpected model requests", 2, calls.size());
        for (JSONObject call : calls) {
            assertFalse("Installation included Agent prompt context", call.toString().contains(SetupFixtureProvider.CONTEXT_CANARY));
            JSONArray messages = call.getJSONArray("messages");
            assertEquals("Installation included conversation history", 1, messages.length());
            assertEquals("user", messages.getJSONObject(0).getString("role"));
        }
        String greeting = calls.get(0).getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue("The greeting must remain fixed public test content", Set.of("你好", "Hello").contains(greeting));
        assertEquals(0, calls.get(0).getJSONArray("tools").length());
        assertEquals("The protocol check exposed real phone tools", "droy_setup_echo",
            calls.get(1).getJSONArray("tools").getString(0));
        assertEquals(1, calls.get(1).getJSONArray("tools").length());
    }

    private void configureMaximumTextFont() throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("layoutMaxTextFont"))) return;
        ui.evaluate("window.DroyMobile.openPermissions()");
        DeviceUi.await("Layout settings did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        ui.tap("[data-droy-font-expander]");
        String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
        for (int n = 0; n < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); n++) ui.tap(increase);
        assertTrue("The DSH font control did not reach its maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        ui.pressBack();
        DeviceUi.await("Layout settings did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        awaitStep(1);
    }

    private void captureAndCheck(String stage) throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const root=document.querySelector('" + ROOT + "'),r=root.getBoundingClientRect();"
            + "let fits=true;for(const e of root.querySelectorAll('p,h1,h2,h3,h4,label,summary,button')){if(!e.getClientRects().length)continue;"
            + "const w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(w.nextNode()){if(!w.currentNode.textContent.trim())continue;"
            + "const x=document.createRange();x.selectNodeContents(w.currentNode);for(const b of x.getClientRects())if(b.width>.1&&(b.left<r.left-1||b.right>r.right+1))fits=false;}}"
            + "const small=[];for(const e of root.querySelectorAll('button,select,input:not([type=checkbox]):not([type=radio]),textarea')){"
            + "if(!e.getClientRects().length)continue;const t=e.getAttribute('role')==='switch'?e.closest('label')||e:e,b=t.getBoundingClientRect();"
            + "if(b.width<47||b.height<47)small.push({tag:t.tagName,label:t.getAttribute('aria-label'),width:b.width,height:b.height});}"
            + "return {fits,small,overflow:root.scrollWidth>root.clientWidth+2||document.documentElement.scrollWidth>innerWidth+1}})()");
        assertTrue("Installation text is clipped: " + geometry, geometry.getBoolean("fits"));
        assertEquals("Installation controls are smaller than 48 CSS pixels: " + geometry, 0, geometry.getJSONArray("small").length());
        assertFalse("Installation has horizontal overflow: " + geometry, geometry.getBoolean("overflow"));
        capture(stage);
    }

    private void capture(String stage) throws Exception {
        ui.awaitPaint(".droy-setup-progress");
        Bitmap image = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "setup-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("evidenceLabel", "setup").replaceAll("[^A-Za-z0-9_-]", "_");
            File path = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream output = new FileOutputStream(path)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            JSONObject conditions = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,textFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),"
                + "theme:document.documentElement.getAttribute('data-ds-theme-source'),step:document.querySelector('[data-droy-setup]')?.getAttribute('data-droy-setup-step')||null})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            conditions.put("requestedLocale", InstrumentationRegistry.getArguments().getString("setupLocale", "system"));
            conditions.put("cloudModel", false).put("source", "local_scripted_dsh_adapter");
            Files.write(new File(directory, path.getName() + ".json").toPath(), conditions.toString(2).getBytes(StandardCharsets.UTF_8));
            report("DROY_SETUP_EVIDENCE " + path.getAbsolutePath() + "\nDROY_SETUP_CONDITIONS " + conditions);
        } finally { image.recycle(); }
    }

    private void report(String message) {
        Bundle status = new Bundle(); status.putString("stream", "\n" + message + "\n"); instrumentation.sendStatus(2, status);
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?> set) {
                Set<String> copy = new HashSet<>(); for (Object item : set) copy.add((String) item); editor.putStringSet(key, copy);
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue("Could not restore installation fixture preferences", editor.commit());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> rows = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return rows;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) rows.put(name, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) rows.put(name, "directory");
                else rows.put(name, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return rows;
    }
}

package io.droyagent.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.WebView;
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

/** The shipped pet settings, real Android touches and native preview, without a model turn. */
@RunWith(AndroidJUnit4.class)
public final class PetSettingsDeviceTest {
    private static final String PET = "[data-droy-general='pet']";
    private static final String INPUT = "#droy-pet-name";
    private static final String SAVE = "[data-droy-action='savePetName']";
    private static final String PREVIEW = "[data-droy-action='previewPet']";
    private static final String NAME = "Droy UI 42";
    private static final String[] PREF_KEYS = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "petId", "petName", "petLeft", "petX", "petY", "returnToDroyOnCompletion"};
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private boolean preferencesOwned, startedRuntime, accessibilityFlagsChanged;
    private int originalAccessibilityFlags;
    private String originalTheme, originalTextFont;
    private Boolean originalFontsExpanded;
    private boolean layoutAdjustmentStarted;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        boolean adjustLayout = "dark".equals(arguments.getString("layoutTheme")) || "true".equals(arguments.getString("layoutMaxTextFont"));
        if (adjustLayout) assertTrue("Theme/font matrix conditions require a dedicated emulator",
                Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                    || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop existing tasks and runtime before pet settings tests", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> saved = preferences.getAll();
        for (String key : PREF_KEYS) if (saved.containsKey(key)) originalPreferences.put(key, saved.get(key));
        preferencesOwned = true;
        // Keep the pet away from the lower settings controls when the test enables it.
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off")
                .putString("petId", "default").putString("petName", "Droy").remove("returnToDroyOnCompletion")
                .putBoolean("petLeft", false).putFloat("petX", 1f).putFloat("petY", .15f).commit());
        AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
        originalAccessibilityFlags = service.flags;
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(service);
        accessibilityFlagsChanged = true;
        startedRuntime = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        DeviceUi.await("Droy did not receive window focus", 5000, () -> {
            java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> focused.set(activity.hasWindowFocus())); return focused.get();
        });
        instrumentation.getUiAutomation().waitForIdle(300, 5000);
        openGeneral();
        if (adjustLayout) {
            DeviceUi.await("Original DSH theme/font controls did not load", 10000,
                    () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')&&document.querySelectorAll('[data-droy-theme-choice]').length===3"));
            originalTheme = String.valueOf(ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')"));
            assertTrue("Unknown original theme source", java.util.Set.of("light", "dark", "system").contains(originalTheme));
            originalTextFont = textFontValue(); originalFontsExpanded = fontsExpanded();
            layoutAdjustmentStarted = true;
            if ("true".equals(arguments.getString("layoutMaxTextFont"))) maximizeDshTextFont();
            if ("dark".equals(arguments.getString("layoutTheme"))) chooseTheme("dark");
        }
    }

    @After public void tearDown() throws Exception {
        try {
            if (startedRuntime) {
                try {
                    if (layoutAdjustmentStarted) {
                        assertTrue("Cannot restore layout choices without the live settings surface", ui != null && activity != null && !activity.isDestroyed() && RuntimeService.ready);
                        if (!ui.truth("!!document.querySelector('[data-droy-settings-panel]')")) openGeneral();
                        ui.hideKeyboard();
                        if (!originalTextFont.equals(textFontValue())) restoreTextFont();
                        if (originalFontsExpanded != null) setFontsExpanded(originalFontsExpanded);
                        if (!originalTheme.equals(String.valueOf(ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')")))) chooseTheme(originalTheme);
                        assertEquals("Text font was not restored through its original UI", originalTextFont, textFontValue());
                        assertEquals("Font expansion was not restored", originalFontsExpanded.booleanValue(), fontsExpanded());
                        awaitDurableLayout(originalTheme, originalTextFont);
                    }
                } finally {
                    if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                    instrumentation.runOnMainSync(RuntimeService::requestStop);
                    DeviceUi.await("Pet settings test left its runtime active", 15000, RuntimeService::canManageHistory);
                    PetOverlay.runtimeStopped();
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            try {
                if (preferencesOwned) {
                    SharedPreferences.Editor restore = preferences.edit();
                    for (String key : PREF_KEYS) {
                        Object value = originalPreferences.get(key);
                        if (value instanceof Boolean bool) restore.putBoolean(key, bool);
                        else if (value instanceof Float number) restore.putFloat(key, number);
                        else if (value instanceof String text) restore.putString(key, text);
                        else restore.remove(key);
                    }
                    assertTrue("Could not restore pet settings", restore.commit());
                }
            } finally {
                if (accessibilityFlagsChanged) {
                    AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
                    service.flags = originalAccessibilityFlags;
                    instrumentation.getUiAutomation().setServiceInfo(service);
                }
            }
        }
    }

    @Test public void petSettingsAllowRenameAndPreviewWhileOffAndPersistIndependentModes() throws Exception {
        WebView originalWeb = ui.web();
        assertTrue(modeSelected("petExternalDisplay", "off"));
        assertTrue(modeSelected("petInternalDisplay", "off"));
        assertTrue("A disabled pet must still allow nickname editing", ui.truth("!document.querySelector('" + INPUT + "').disabled"));
        assertTrue("In-app preview must remain available while display modes are off", ui.truth("!document.querySelector(" + JSONObject.quote(PREVIEW) + ").disabled"));
        assertEquals("Overlay permission status must reflect the system grant", Settings.canDrawOverlays(context),
                ui.truth("document.querySelector('[data-droy-pet-permission]').textContent.includes('已允许')"));
        assertLayout();
        scrollPetIntoView();
        capture("settings-off");

        ui.tap(INPUT);
        DeviceUi.await("A real touch did not focus the nickname input", 5000,
                () -> ui.truth("document.activeElement===document.querySelector('" + INPUT + "')"));
        long now = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
        instrumentation.sendStringSync(NAME);
        DeviceUi.await("Android input did not update the nickname draft", 5000,
                () -> NAME.equals(ui.evaluate("document.querySelector('" + INPUT + "').value")));
        ui.hideKeyboard(); awaitKeyboardSettled(false);
        awaitStableControl(SAVE);
        ui.tap(SAVE);
        try {
            DeviceUi.await("A real Save touch did not persist the nickname", 10000,
                    () -> NAME.equals(preferences.getString("petName", ""))
                            && ui.truth("document.querySelector(" + JSONObject.quote(SAVE) + ").disabled"));
        } catch (AssertionError failure) { capture("save-failure"); throw failure; }
        assertTrue("Renaming a disabled pet unexpectedly enabled it", modeSelected("petExternalDisplay", "off") && modeSelected("petInternalDisplay", "off"));
        assertLayout();

        setMode("petExternalDisplay", "adaptive");
        assertTrue("External mode changed the independent in-app choice", modeSelected("petInternalDisplay", "off"));
        setMode("petExternalDisplay", "always");
        setMode("petExternalDisplay", "off");
        setMode("petInternalDisplay", "adaptive");
        setMode("petInternalDisplay", "always");
        assertTrue("Enabled and authorized pet cannot be previewed", ui.truth("!document.querySelector(" + JSONObject.quote(PREVIEW) + ").disabled"));
        ui.tap(PREVIEW);
        try {
            DeviceUi.await("The preview touch did not show the renamed pet's actual native message", 5000, this::previewVisible);
        } catch (AssertionError failure) {
            capture("preview-failure");
            StringBuilder state = new StringBuilder();
            for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
                try {
                    state.append("window=").append(window.getTitle()).append(";");
                    if (!"Droy pet message".contentEquals(window.getTitle() == null ? "" : window.getTitle())) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) try { describePetNode(root, state); } finally { root.recycle(); }
                } finally { window.recycle(); }
            }
            throw new AssertionError("Pet preview accessibility: " + state, failure);
        }
        capture("settings-preview");
        Rect pet = new Rect();
        instrumentation.runOnMainSync(() -> pet.set(PetOverlay.get(context).petBounds()));
        assertFalse("The preview has no visible pet touch target", pet.isEmpty());
        ui.swipe(pet.centerX(), pet.centerY(), pet.centerX(), pet.centerY(), 48);
        DeviceUi.await("Tapping the pet did not dismiss its preview", 5000, () -> !previewVisible());
        setMode("petInternalDisplay", "off");
        DeviceUi.await("Disabling the pet left its native preview open", 5000, () -> !previewVisible());
        assertLayout();
        scrollPetIntoView();
        capture("settings-saved");

        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("The settings Back touch did not return to the conversation", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')||!!document.querySelector('[data-droy-drawer-open=true]')"));
        assertSame("Pet settings replaced the conversation WebView", originalWeb, ui.web());
        openGeneral();
        assertEquals(NAME, ui.evaluate("document.querySelector('" + INPUT + "').value"));
        assertTrue("Reopening settings lost the external display preference", modeSelected("petExternalDisplay", "off"));
        assertTrue("Reopening settings lost the internal display preference", modeSelected("petInternalDisplay", "off"));
        ui.pressBack();
        DeviceUi.await("System Back did not close pet settings", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    @Test public void completionReturnPreferenceIsDefaultOffAndEditableWithPetOff() throws Exception {
        String row = "[data-droy-general=returnToDroyOnCompletion]";
        String toggle = row + " [role=switch]";
        assertTrue(modeSelected("petExternalDisplay", "off") && modeSelected("petInternalDisplay", "off"));
        assertTrue(ui.truth("document.querySelector(" + JSONObject.quote(toggle) + ").getAttribute('aria-checked')==='false'"));
        assertTrue(ui.truth("document.querySelector(" + JSONObject.quote(row) + ").textContent.includes('任务完成后总是返回DroyAPP')"));
        ui.tap(toggle);
        DeviceUi.await("Completion return switch did not save the enabled preference", 5000,
            () -> preferences.getBoolean("returnToDroyOnCompletion", false)
                && ui.truth("document.querySelector(" + JSONObject.quote(toggle) + ").getAttribute('aria-checked')==='true'"));
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("The settings Back touch did not return to the conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')||!!document.querySelector('[data-droy-drawer-open=true]')"));
        openGeneral();
        assertTrue(ui.truth("document.querySelector(" + JSONObject.quote(toggle) + ").getAttribute('aria-checked')==='true'"));
        ui.tap(toggle);
        DeviceUi.await("Completion return switch did not save the disabled preference", 5000,
            () -> !preferences.getBoolean("returnToDroyOnCompletion", true)
                && ui.truth("document.querySelector(" + JSONObject.quote(toggle) + ").getAttribute('aria-checked')==='false'"));
        assertTrue("The completion-return setting must not enable the pet", modeSelected("petExternalDisplay", "off") && modeSelected("petInternalDisplay", "off"));
        ui.evaluate("document.querySelector(" + JSONObject.quote(row) + ").scrollIntoView({block:'center',behavior:'instant'})");
        capture("completion-return-settings");
    }

    @Test public void petCreationExplainsPersonalityAndKeepsUnsavedInputInsideSettings() throws Exception {
        assertTrue("The settings must identify the same companion used in conversation", ui.truth("document.querySelector('[data-droy-general=pet]').textContent.includes('Droy伙伴')"));
        assertTrue("Companion identity must have a named input", ui.truth("document.querySelector('#droy-pet-name').getAttribute('aria-label')==='伙伴姓名'"));
        ui.tap("[data-droy-action=openPetStudio]");
        DeviceUi.await("Pet creation did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-pet-studio]')"));
        assertTrue(ui.truth("document.querySelector('[data-droy-pet-studio]').textContent.includes('性格与说话习惯')"));
        assertTrue("Applying an appearance must explain which identity and history are retained", ui.truth("document.querySelector('[data-droy-pet-studio]').textContent.includes('保留伙伴姓名和已有对话')"));
        assertTrue("Empty appearance must not dispatch generation", ui.truth("document.querySelector('[data-droy-action=createPet]').disabled"));
        boolean landscape = activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        ui.tap("#droy-pet-create-name"); awaitKeyboardSettled(true); instrumentation.sendStringSync("Fixture Companion");
        if (landscape) { ui.pressBack(); awaitKeyboardSettled(false); }
        ui.tap("#droy-pet-description"); awaitKeyboardSettled(true); instrumentation.sendStringSync("A small black cat");
        if (landscape) { ui.pressBack(); awaitKeyboardSettled(false); }
        ui.showKeyboard("#droy-pet-personality");
        DeviceUi.await("Personality field cannot be focused", 5000,
            () -> ui.truth("document.activeElement===document.querySelector('#droy-pet-personality')"));
        instrumentation.sendStringSync("A quiet and curious companion");
        awaitKeyboardSettled(true);
        String create = "[data-droy-action=createPet]";
        capture("pet-keyboard-before-scroll");
        // A landscape IME can occupy the entire form; Android Back must restore the same draft.
        if (landscape) { ui.pressBack(); awaitKeyboardSettled(false); }
        gestureDiagnostic("before-scroll", -1, null);
        // Scroll as a user does while an IME is open; scrollIntoView can pan the WebView itself.
        for (int attempt = 0; attempt < 16; attempt++) {
            Rect scrollArea = ui.webBounds("[data-droy-settings-options]");
            final int[] available = new int[4];
            instrumentation.runOnMainSync(() -> {
                android.view.View root = activity.getWindow().getDecorView();
                int[] location = new int[2]; root.getLocationOnScreen(location);
                available[0] = root.getWidth();
                // Landscape navigation also reserves side edges; keep the entire swipe inside app-owned space.
                android.graphics.Insets insets = root.getRootWindowInsets().getInsets(WindowInsets.Type.ime()
                    | WindowInsets.Type.navigationBars() | WindowInsets.Type.systemGestures());
                available[1] = location[1] + root.getHeight() - insets.bottom;
                available[2] = location[0] + insets.left;
                available[3] = location[0] + root.getWidth() - insets.right;
            });
            int top = Math.max(scrollArea.top, 0), bottom = Math.min(scrollArea.bottom, available[1]);
            assertTrue("The settings scroller has no usable space above the keyboard", bottom - top > 40);
            Rect target = ui.webBounds(create);
            if (target.top >= top + 12 && target.bottom <= bottom - 12) break;
            boolean above = target.top < top + 12;
            float margin = (float) (available[0] / ((Number) ui.evaluate("innerWidth")).doubleValue() * 6);
            float safeLeft = Math.max(scrollArea.left, available[2]) + margin;
            float safeRight = Math.min(scrollArea.right, available[3]) - margin;
            assertTrue("The settings scroller has no touch space outside system gestures", safeRight > safeLeft);
            float x = safeRight;
            float fromY = top + (bottom - top) * (above ? .15f : .85f), toY = top + (bottom - top) * (above ? .85f : .15f);
            JSONObject swipe = new JSONObject().put("x", x).put("fromY", fromY).put("toY", toY)
                .put("top", top).put("bottom", bottom).put("scrollArea", scrollArea.toShortString()).put("target", target.toShortString());
            gestureDiagnostic("before-swipe", attempt, swipe);
            ui.swipe(x, fromY, x, toY, 450);
            instrumentation.getUiAutomation().waitForIdle(250, 3000);
            gestureDiagnostic("after-swipe", attempt, swipe);
        }
        Rect buttonBounds = ui.webBounds(create);
        DeviceUi.await("Completed fictional description did not enable its save/generate action", 5000, () -> ui.truth("!document.querySelector('" + create + "').disabled"));
        final int[] keyboardTop = new int[1];
        instrumentation.runOnMainSync(() -> {
            android.view.View root = activity.getWindow().getDecorView(); int[] location = new int[2]; root.getLocationOnScreen(location);
            keyboardTop[0] = location[1] + root.getHeight() - root.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
        });
        if (buttonBounds.bottom > keyboardTop[0]) capture("pet-keyboard-covered-failure");
        assertTrue("The final creation action is covered by the actual IME; button=" + buttonBounds + "; keyboardTop=" + keyboardTop[0], buttonBounds.bottom <= keyboardTop[0]);
        assertTrue("The final creation action cannot be hit while the keyboard is open", ui.truth("(()=>{const e=document.querySelector('" + create + "'),r=e.getBoundingClientRect();return e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()"));
        gestureDiagnostic("before-hit", -1, new JSONObject().put("button", buttonBounds.toShortString()));
        capture("pet-keyboard-before-hit");
        final int[] webGeometry = new int[5]; android.webkit.WebView measuredWeb = ui.web();
        instrumentation.runOnMainSync(() -> { android.webkit.WebView view = measuredWeb; view.getLocationOnScreen(webGeometry); webGeometry[2] = view.getWidth(); webGeometry[3] = view.getHeight(); webGeometry[4] = view.getScrollY(); });
        Object beforeHit = ui.evaluate("(()=>{const e=document.querySelector('[data-droy-action=createPet]'),v=visualViewport;return {target:e.getBoundingClientRect().toJSON(),focus:document.activeElement.id,innerHeight,scrollY,visual:{offsetTop:v.offsetTop,pageTop:v.pageTop,height:v.height,scale:v.scale}}})()");
        // Intercept only this fixture touch before React: verify a trusted hit without dispatching paid generation.
        ui.evaluate("window.__petCreateHit=false;window.__petCreateEvents=[];window.__petBlockCreate=e=>{window.__petCreateEvents.push({type:e.type,trusted:e.isTrusted,tag:e.target.tagName,id:e.target.id,action:e.target.closest('[data-droy-action]')?.getAttribute('data-droy-action'),x:e.clientX,y:e.clientY,target:document.querySelector('[data-droy-action=createPet]').getBoundingClientRect().toJSON(),visualTop:visualViewport.offsetTop});if(e.target.closest('[data-droy-action=createPet]')){if(e.type==='pointerdown')window.__petCreateHit=e.isTrusted;e.preventDefault();e.stopImmediatePropagation()}};document.addEventListener('pointerdown',window.__petBlockCreate,true);document.addEventListener('click',window.__petBlockCreate,true)");
        try {
            ui.swipe(buttonBounds.centerX(), buttonBounds.centerY(), buttonBounds.centerX(), buttonBounds.centerY(), 40);
            if (!ui.truth("window.__petCreateHit===true")) {
                capture("pet-keyboard-hit-failure");
                fail("Android touch did not reach the final creation action; before=" + beforeHit + "; bounds=" + buttonBounds + "; web=" + java.util.Arrays.toString(webGeometry) + "; safe events=" + ui.evaluate("window.__petCreateEvents")
                    + "; target=" + ui.evaluate("document.querySelector('[data-droy-action=createPet]').getBoundingClientRect().toJSON()"));
            }
            if (landscape) ui.hideKeyboard();
            awaitKeyboardSettled(!landscape);
            capture(landscape ? "pet-personality-after-keyboard-back" : "pet-personality-keyboard");
        } finally {
            ui.evaluate("document.removeEventListener('pointerdown',window.__petBlockCreate,true);document.removeEventListener('click',window.__petBlockCreate,true);delete window.__petBlockCreate;delete window.__petCreateHit;delete window.__petCreateEvents");
        }
        ui.hideKeyboard(); awaitKeyboardSettled(false);
        assertEquals("A quiet and curious companion", ui.evaluate("document.querySelector('#droy-pet-personality').value"));
        assertLayout();
        ui.visibleWebBounds(create); awaitStableControl(create);
        capture("pet-personality-form");
        ui.tap("[data-droy-action=openPetStudio]");
        DeviceUi.await("Pet creation did not collapse", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-pet-studio]')"));
        assertTrue("Collapsing creation closed the settings page", ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void openGeneral() throws Exception {
        ui.hideKeyboard();
        ui.tap("[data-droy-menu]");
        DeviceUi.await("The real menu touch did not open the drawer", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The settings page did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("Droy pet settings did not become interactive", 10000,
                () -> ui.truth("!!document.querySelector('" + INPUT + "')&&!document.querySelector('" + INPUT + "').disabled"));
    }

    private String textFontValue() throws Exception {
        return String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
    }

    private boolean fontsExpanded() throws Exception {
        return ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'");
    }

    private void setFontsExpanded(boolean expanded) throws Exception {
        if (fontsExpanded() != expanded) ui.tap("[data-droy-font-expander]");
        DeviceUi.await("Original font controls did not change expansion state", 5000,
                () -> fontsExpanded() == expanded && ui.truth("!!document.querySelector('[data-droy-font-advanced]')") == expanded);
    }

    private void maximizeDshTextFont() throws Exception {
        setFontsExpanded(true);
        String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
        for (int i = 0; i < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); i++) stepTextFont(increase);
        assertTrue("The actual DSH font control did not reach its supported maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
    }

    private void stepTextFont(String selector) throws Exception {
        String previous = textFontValue();
        assertFalse("The required font step is unavailable", ui.truth("document.querySelector(" + JSONObject.quote(selector) + ").disabled"));
        ui.tap(selector);
        DeviceUi.await("A real DSH font step touch did not apply", 5000, () -> !previous.equals(textFontValue()));
    }

    private void restoreTextFont() throws Exception {
        setFontsExpanded(true);
        double desired = Double.parseDouble(originalTextFont.replace("px", "").trim());
        for (int i = 0; i < 60 && !originalTextFont.equals(textFontValue()); i++) {
            double current = Double.parseDouble(textFontValue().replace("px", "").trim());
            stepTextFont("[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=" + (current > desired ? "decrease" : "increase") + "]");
        }
        assertEquals("Original DSH text font could not be restored", originalTextFont, textFontValue());
    }

    private void awaitDurableLayout(String theme, String textFont) throws Exception {
        // ThemeRuntime publishes its optimistic CSS before its asynchronous Host write completes.
        // Do not stop the runtime until the original UI choices are durably restored.
        DeviceUi.await("The original DSH theme/font did not finish saving", 10000, () -> {
            File file = new File(context.getFilesDir(), "dsh-home/profiles/mobile/cordis.patch.yml");
            String source = file.isFile() ? new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
            java.util.regex.Matcher section = java.util.regex.Pattern.compile("(?ms)^- id: ui-theme\\R(?:(?!^- id:).)*").matcher(source);
            String value = section.find() ? section.group() : "";
            java.util.regex.Matcher preference = java.util.regex.Pattern.compile("(?m)^\\s+preference: [\"']?(system|light|dark)").matcher(value);
            java.util.regex.Matcher size = java.util.regex.Pattern.compile("(?m)^\\s+fontSize: (\\d+)").matcher(value);
            return theme.equals(preference.find() ? preference.group(1) : "system")
                    && textFont.equals((size.find() ? size.group(1) : "14") + "px");
        });
    }

    private void chooseTheme(String theme) throws Exception {
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("The original DSH theme control did not apply", 10000,
                () -> theme.equals(preferences.getString("themePreference", ""))
                    && ui.truth("document.querySelector('[data-droy-theme-choice=" + theme + "]')?.getAttribute('aria-pressed')==='true'&&document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme))
                    && (!theme.equals("dark") || ui.truth("document.body.hasAttribute('data-ds-dark-theme')")));
    }

    private static String modeSelector(String key, String value) {
        return "[data-droy-pet-mode='" + key + "'][data-value='" + value + "']";
    }

    private boolean modeSelected(String key, String value) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(modeSelector(key, value)) + ").getAttribute('aria-pressed')==='true'");
    }

    private void setMode(String key, String value) throws Exception {
        assertFalse("The fixture must exercise a real mode change", modeSelected(key, value));
        String selector = modeSelector(key, value);
        ui.tap(selector);
        DeviceUi.await("The pet display mode touch was not accepted", 10000,
                () -> value.equals(preferences.getString(key, "")) && modeSelected(key, value)
                        && ui.truth("!document.querySelector(" + JSONObject.quote(selector) + ").disabled"));
    }

    private void scrollPetIntoView() throws Exception {
        ui.evaluate("document.querySelector(" + JSONObject.quote(PET) + ").scrollIntoView({block:'center',behavior:'instant'})");
    }

    private void assertLayout() throws Exception {
        JSONObject shape = (JSONObject) ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(PET) + "),r=row.getBoundingClientRect();"
                + "const widths=e=>({client:e.clientWidth,scroll:e.scrollWidth});"
                + "const rect=e=>{const b=e.getBoundingClientRect();return {left:b.left,right:b.right,top:b.top,bottom:b.bottom,width:b.width,height:b.height}};"
                + "return {viewport:{width:innerWidth,height:innerHeight},row:rect(row),root:widths(document.documentElement),"
                + "options:widths(document.querySelector('[data-droy-settings-options]')),content:widths(row),"
                + "controls:[...row.querySelectorAll('input,textarea,button,[role=switch],label,.droy-row-title,.droy-description,.droy-status')].map(e=>({"
                + "tag:e.tagName,rect:rect(e),inside:(()=>{const b=e.getBoundingClientRect();return b.width>0&&b.height>0&&b.left>=r.left-1&&b.right<=r.right+1&&b.top>=r.top-1&&b.bottom<=r.bottom+1})()})),"
                + "input:rect(row.querySelector('input')),save:rect(row.querySelector('[data-droy-action=savePetName]'))}})()");
        for (String part : new String[]{"root", "options", "content"}) {
            JSONObject width = shape.getJSONObject(part);
            assertTrue("Pet settings requires horizontal scrolling: " + shape,
                    width.getDouble("scroll") <= width.getDouble("client") + 1);
        }
        JSONArray controls = shape.getJSONArray("controls");
        assertTrue("Pet settings contains no rendered controls", controls.length() >= 5);
        for (int i = 0; i < controls.length(); i++)
            assertTrue("A pet setting is clipped outside its row: " + shape, controls.getJSONObject(i).getBoolean("inside"));
        assertTrue("Necessary pet settings text is clipped inside its own layout", ui.truth("(()=>{const root=document.querySelector(" + JSONObject.quote(PET) + ");"
                + "for(const e of root.querySelectorAll('button,label,.droy-row-title,.droy-description,.droy-status')){if(!e.getClientRects().length)continue;"
                + "const bounds=e.getBoundingClientRect(),walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let node;while(node=walker.nextNode()){"
                + "if(!node.textContent.trim()||node.parentElement.closest('textarea,input,[aria-hidden=true]'))continue;const range=document.createRange();range.selectNodeContents(node);"
                + "for(const r of range.getClientRects()){if(r.width<.1||r.height<.1)continue;if(r.left<bounds.left-1||r.right>bounds.right+1||r.top<bounds.top-1||r.bottom>bounds.bottom+1)return false;"
                + "for(let a=node.parentElement;a&&a!==root;a=a.parentElement){const c=getComputedStyle(a),b=a.getBoundingClientRect();"
                + "if(/hidden|clip/.test(c.overflowX)&&(r.left<b.left-1||r.right>b.right+1))return false;"
                + "if(/hidden|clip/.test(c.overflowY)&&(r.top<b.top-1||r.bottom>b.bottom+1))return false}}}}return true})()"));
        JSONObject input = shape.getJSONObject("input"), save = shape.getJSONObject("save");
        assertTrue("Nickname input overlaps its Save button: " + shape,
                input.getDouble("right") <= save.getDouble("left") + 1
                        || input.getDouble("bottom") <= save.getDouble("top") + 1);
    }

    private boolean previewVisible() {
        for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
            try {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) try {
                    if (containsText(root, NAME) && containsText(root, "查看任务")) return true;
                } finally { root.recycle(); }
            } finally { window.recycle(); }
        }
        return false;
    }
    private void awaitKeyboardSettled(boolean shown) throws Exception {
        final String[] previous = {""}; final long[] stableSince = {0};
        DeviceUi.await("The IME and page layout did not finish their transition", 7000, () -> {
            final int[] nativeInsets = new int[2];
            instrumentation.runOnMainSync(() -> {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                nativeInsets[0] = insets.isVisible(WindowInsets.Type.ime()) ? 1 : 0;
                nativeInsets[1] = insets.getInsets(WindowInsets.Type.ime()).bottom;
            });
            JSONObject geometry = (JSONObject) ui.evaluate("({height:innerHeight,ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0,viewport:visualViewport?.height||innerHeight})");
            boolean state = shown ? nativeInsets[0] == 1 && nativeInsets[1] > 0 && geometry.getDouble("ime") > 0
                    : nativeInsets[0] == 0 && nativeInsets[1] == 0 && geometry.getDouble("ime") < 1;
            String current = nativeInsets[0] + ":" + nativeInsets[1] + ":" + geometry;
            if (!state || !current.equals(previous[0])) { previous[0] = current; stableSince[0] = SystemClock.elapsedRealtime(); return false; }
            return SystemClock.elapsedRealtime() - stableSince[0] >= 600;
        });
    }

    private void awaitStableControl(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        ui.evaluate("(()=>{window.__petControlStable=false;let last='',same=0;const sample=()=>{const r=document.querySelector(" + JSONObject.quote(selector)
            + ").getBoundingClientRect();const next=[r.x,r.y,r.width,r.height,innerHeight].join(',');same=next===last?same+1:0;last=next;"
            + "if(same>=4)window.__petControlStable=true;else requestAnimationFrame(sample)};requestAnimationFrame(sample)})()");
        DeviceUi.await("The setting control kept moving after keyboard dismissal", 5000, () -> ui.truth("window.__petControlStable===true"));
        ui.evaluate("delete window.__petControlStable");
    }

    private boolean containsText(AccessibilityNodeInfo node, String expected) {
        if (node.isVisibleToUser() && expected.contentEquals(node.getText() == null ? "" : node.getText())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { if (containsText(child, expected)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    private void describePetNode(AccessibilityNodeInfo node, StringBuilder text) {
        text.append("[").append(node.getClassName()).append(" visible=").append(node.isVisibleToUser()).append(" text=").append(node.getText()).append("]");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { describePetNode(child, text); } finally { child.recycle(); }
        }
    }

    private void capture(String stage) throws Exception {
        ui.evaluate("window.__droyPetCaptureReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__droyPetCaptureReady=true))");
        DeviceUi.await("The pet settings screenshot did not reach a painted frame", 5000,
                () -> ui.truth("window.__droyPetCaptureReady"));
        ui.evaluate("delete window.__droyPetCaptureReady");
        instrumentation.waitForIdleSync();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Could not capture pet settings", image);
        try {
            File external = context.getExternalFilesDir(null); assertNotNull(external);
            File directory = new File(external, "pet-ui-evidence");
            assertTrue("Could not create pet UI evidence directory", directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("petEvidenceLabel", "settings")
                    .replaceAll("[^A-Za-z0-9_-]", "_");
            int cssWidth = ((Number) ui.evaluate("Math.round(innerWidth)")).intValue();
            int font = Math.round(activity.getResources().getConfiguration().fontScale * 100);
            String orientation = image.getWidth() > image.getHeight() ? "landscape" : "portrait";
            JSONObject conditions = (JSONObject) ui.evaluate("({themeSource:document.documentElement.getAttribute('data-ds-theme-source'),theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),fontsExpanded:document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true',cssWidth:innerWidth,cssHeight:innerHeight})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale).put("orientation", orientation);
            String dshFont = conditions.getString("dshTextFont").replaceAll("[^A-Za-z0-9_-]", "_");
            File output = File.createTempFile(label + "-" + stage + "-" + orientation + "-"
                    + image.getWidth() + "x" + image.getHeight() + "-css" + cssWidth + "-font" + font
                    + "-dsh" + dshFont + "-" + conditions.getString("theme") + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue("Could not save pet settings screenshot", image.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_PET_EVIDENCE " + output.getAbsolutePath() + "\nDROY_PET_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }

    private void gestureDiagnostic(String stage, int attempt, JSONObject swipe) throws Exception {
        int[] rootGeometry = new int[4], webGeometry = new int[5];
        int[][] edges = new int[5][4];
        boolean[] state = new boolean[2];
        Rect visible = new Rect();
        WebView web = ui.web();
        instrumentation.runOnMainSync(() -> {
            android.view.View root = activity.getWindow().getDecorView();
            root.getLocationOnScreen(rootGeometry); rootGeometry[2] = root.getWidth(); rootGeometry[3] = root.getHeight();
            web.getLocationOnScreen(webGeometry); webGeometry[2] = web.getWidth(); webGeometry[3] = web.getHeight(); webGeometry[4] = web.getScrollY();
            root.getWindowVisibleDisplayFrame(visible);
            WindowInsets insets = root.getRootWindowInsets();
            int[] types = {WindowInsets.Type.ime(), WindowInsets.Type.navigationBars(), WindowInsets.Type.systemGestures(),
                WindowInsets.Type.mandatorySystemGestures(), WindowInsets.Type.systemBars()};
            for (int i = 0; i < types.length; i++) {
                android.graphics.Insets value = insets.getInsets(types[i]);
                edges[i] = new int[]{value.left, value.top, value.right, value.bottom};
            }
            state[0] = activity.hasWindowFocus(); state[1] = insets.isVisible(WindowInsets.Type.ime());
        });
        JSONObject diagnostic = new JSONObject().put("stage", stage).put("attempt", attempt).put("swipe", swipe)
            .put("root", new JSONArray(rootGeometry)).put("web", new JSONArray(webGeometry)).put("visible", visible.toShortString())
            .put("windowFocus", state[0]).put("imeVisible", state[1]);
        String[] names = {"ime", "navigationBars", "systemGestures", "mandatorySystemGestures", "systemBars"};
        for (int i = 0; i < names.length; i++) diagnostic.put(names[i], new JSONArray(edges[i]));
        diagnostic.put("navigation", ui.nativeNavigationState());
        diagnostic.put("page", ui.evaluate("(()=>{const v=visualViewport,s=document.querySelector('[data-droy-settings-options]'),r=s?.getBoundingClientRect();return {innerWidth,innerHeight,scrollY,visibility:document.visibilityState,focus:document.hasFocus(),scroller:r?.toJSON(),scrollTop:s?.scrollTop,visual:v?{width:v.width,height:v.height,offsetLeft:v.offsetLeft,offsetTop:v.offsetTop,pageLeft:v.pageLeft,pageTop:v.pageTop,scale:v.scale}:null}})()"));
        Bundle status = new Bundle();
        status.putString("stream", "\nDROY_COMPANION_GESTURE " + diagnostic + "\n");
        instrumentation.sendStatus(2, status);
        assertTrue("The companion fixture lost foreground focus at " + stage + "; " + diagnostic, state[0]);
    }
}

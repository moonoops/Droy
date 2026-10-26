package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Theme and identity checks use local artwork in a disposable emulator profile. */
@RunWith(AndroidJUnit4.class)
public final class BrandThemeDeviceTest {
    private static final String[] KEYS = {"assistantName", "petId", "petName", "petEnabled", "petExternalDisplay",
        "petInternalDisplay", "backgroundRuntimeEnabled", "themePreference", "themeColorScheme", "themeBackgroundColor",
        "resetTheme", "darkTheme", "brandLogoBackgrounds"};
    private static final String HERO = "[data-droy-center] [data-droy-brand-mark]";
    private static final String SIDEBAR = "[data-droy-sidebar] [data-droy-brand-mark]";
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private File home, preserved;
    private Map<String, String> originalFiles;
    private String darkFixture, lightFixture;
    private boolean owned, launched, systemThemeChanged;
    private int originalNightMode;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Brand theme fixtures require a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Finish existing tasks and stop the engine before theme UI tests", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> current = preferences.getAll();
        for (String key : KEYS) if (current.containsKey(key)) savedPreferences.put(key, current.get(key));
        originalNightMode = context.getSystemService(UiModeManager.class).getNightMode();
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "droy-brand-theme-backup-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath());
        if (home.exists()) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        owned = true;
        assertTrue("Could not create the disposable theme profile", home.mkdirs());
        darkFixture = installFixture(Color.rgb(12, 12, 12), "深色标志夹具");
        lightFixture = installFixture(Color.rgb(247, 247, 247), "浅色标志夹具");
        SharedPreferences.Editor edit = preferences.edit();
        for (String key : KEYS) edit.remove(key);
        assertTrue(edit.putString("assistantName", "Droy").putString("petName", "Droy").putString("petId", "default")
            .putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off")
            .putBoolean("backgroundRuntimeEnabled", false).commit());
        launched = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        awaitPage();
        if ("true".equals(InstrumentationRegistry.getArguments().getString("layoutMaxTextFont"))) {
            openGeneral();
            ui.tap("[data-droy-font-expander]");
            String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
            for (int step = 0; step < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); step++) {
                String before = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
                ui.tap(increase);
                DeviceUi.await("The text font touch did not apply", 5000,
                    () -> !before.equals(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()")));
            }
            assertTrue("The font control did not reach its maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
            closeSettings();
        }
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            if (launched) {
                try {
                    if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                } finally {
                    instrumentation.runOnMainSync(RuntimeService::requestStop);
                    DeviceUi.await("Theme UI cleanup left an engine active", 20000, RuntimeService::canManageHistory);
                    PetOverlay.runtimeStopped();
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            if (systemThemeChanged) shell("cmd uimode night " + (originalNightMode == UiModeManager.MODE_NIGHT_YES ? "yes"
                : originalNightMode == UiModeManager.MODE_NIGHT_NO ? "no" : "auto"));
            assertTrue("Keep the home backup until the fixture engine stops", RuntimeService.canManageHistory());
            HistoryData.deleteTree(home.toPath());
            if (preserved.exists()) assertTrue("Could not restore the original DSH home", preserved.renameTo(home));
            assertEquals("Theme UI tests changed original DSH data", originalFiles, snapshot(home.toPath()));
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : KEYS) {
                Object value = savedPreferences.get(key);
                if (value instanceof String text) restore.putString(key, text);
                else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                else if (value instanceof Integer number) restore.putInt(key, number);
                else if (value instanceof Float number) restore.putFloat(key, number);
                else restore.remove(key);
            }
            assertTrue("Could not restore theme preferences", restore.commit());
            owned = false;
        }
    }

    @Test public void sameLogoAndPlateGeometrySurviveThemeTouchesAndReload() throws Exception {
        chooseTheme("light");
        JSONObject heroLight = appearance(HERO);
        capture("welcome-light");
        openDrawer();
        JSONObject drawerLight = appearance(SIDEBAR);
        capture("sidebar-light");
        closeDrawer();
        chooseTheme("dark");
        JSONObject heroDark = appearance(HERO);
        assertSameGeometryAndSource(heroLight, heroDark);
        assertNotEquals("The dark background did not adapt", heroLight.getString("background"), heroDark.getString("background"));
        capture("welcome-dark");
        openDrawer();
        JSONObject drawerDark = appearance(SIDEBAR);
        assertSameGeometryAndSource(drawerLight, drawerDark);
        assertEquals("Welcome and sidebar use different dark plates", heroDark.getString("background"), drawerDark.getString("background"));
        capture("sidebar-dark");
        closeDrawer();
        android.webkit.WebView view = ui.web();
        instrumentation.runOnMainSync(view::reload);
        awaitPage();
        assertEquals("dark", preferences.getString("themePreference", ""));
        assertTrue("Saved dark theme did not return", ui.truth("document.body.hasAttribute('data-ds-dark-theme')"));
        assertSameGeometryAndSource(heroDark, appearance(HERO));
    }

    @Test public void customDarkAndLightArtworkKeepTheirIdentityAndPreviewBothModes() throws Exception {
        assertTrue(preferences.edit().putString("brandLogoBackgrounds", new JSONObject().put(lightFixture,
            new JSONObject().put("light", "#223344").put("dark", "#1A2B3C")).toString()).commit());
        ui.evaluate("window.__brandThemeRefreshed=false;window.DroyMobile.refresh().then(()=>window.__brandThemeRefreshed=true)");
        DeviceUi.await("Saved fixture colors did not refresh", 10000, () -> ui.truth("window.__brandThemeRefreshed"));
        for (String id : new String[]{darkFixture, lightFixture}) {
            openGeneral();
            ui.tap("[data-droy-action=openPetStudio]");
            String card = "[data-droy-pet-candidate='" + id + "']";
            DeviceUi.await("The local candidate is missing", 10000, () -> ui.truth("!!document.querySelector(" + JSONObject.quote(card) + ")"));
            String light = card + " [data-droy-brand-preview=light] [data-droy-brand-mark]";
            String dark = card + " [data-droy-brand-preview=dark] [data-droy-brand-mark]";
            JSONObject previewLight = appearance(light), previewDark = appearance(dark);
            assertSameGeometryAndSource(previewLight, previewDark);
            assertNotEquals("Dual previews must show their distinct backgrounds", previewLight.getString("background"), previewDark.getString("background"));
            if (id.equals(lightFixture)) {
                assertEquals("Inactive candidate lost its saved light background", "rgb(34, 51, 68)", previewLight.getString("background"));
                assertEquals("Inactive candidate lost its saved dark background", "rgb(26, 43, 60)", previewDark.getString("background"));
            }
            int foreground = id.equals(darkFixture) ? Color.rgb(12, 12, 12) : Color.rgb(247, 247, 247);
            assertArtworkContrast(foreground, previewLight.getString("background"));
            assertArtworkContrast(foreground, previewDark.getString("background"));
            ui.visibleWebBounds(card + " .droy-brand-previews");
            capture(id.equals(darkFixture) ? "candidate-dark-artwork" : "candidate-light-artwork");
            ui.tap(card + " [data-droy-action=selectPet]");
            DeviceUi.await("The real candidate selection did not persist", 10000,
                () -> id.equals(preferences.getString("petId", "")));
            closeSettings();
            chooseTheme("light");
            JSONObject heroLight = appearance(HERO);
            assertTrue("The custom logo was not installed in the welcome", heroLight.getString("src").contains(id));
            chooseTheme("dark");
            assertSameGeometryAndSource(heroLight, appearance(HERO));
            openDrawer();
            assertEquals("Custom sidebar does not use the welcome logo", heroLight.getString("src"), appearance(SIDEBAR).getString("src"));
            capture(id.equals(darkFixture) ? "selected-dark-artwork" : "selected-light-artwork");
            closeDrawer();
        }
    }

    @Test public void systemPreferenceFollowsActualSystemAppearance() throws Exception {
        chooseTheme("system");
        systemThemeChanged = true;
        shell("cmd uimode night no");
        awaitSystemScheme(false);
        JSONObject light = appearance(HERO);
        shell("cmd uimode night yes");
        awaitSystemScheme(true);
        JSONObject dark = appearance(HERO);
        assertSameGeometryAndSource(light, dark);
        assertNotEquals(light.getString("background"), dark.getString("background"));
        assertEquals("System appearance overwrote the user's follow-system preference", "system", preferences.getString("themePreference", ""));
        capture("follow-system-dark");
    }

    @Test public void userBackgroundsSaveForTheSelectedLogoAndCanReturnToAutomatic() throws Exception {
        chooseTheme("light");
        JSONObject automatic = appearance(HERO);
        openGeneral();
        openBrandDetails();
        replaceField("#droy-logo-background-light", "#E4D8C8");
        replaceField("#droy-logo-background-dark", "#B8ACA0");
        ui.visibleWebBounds("[data-droy-logo-background-save]");
        JSONObject[] previous = {null};
        DeviceUi.await("The save button is moving after editing", 5000, () -> {
            JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-droy-logo-background-save]'),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return {rect:r.toJSON(),hit:!!hit&&(e===hit||e.contains(hit))}})()");
            boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
            previous[0] = current;
            return stable && current.getBoolean("hit");
        });
        ui.evaluate("window.__logoSaveTouches=[];for(const type of ['pointerdown','pointerup','click'])document.querySelector('[data-droy-logo-background-save]').addEventListener(type,e=>window.__logoSaveTouches.push({type:e.type,trusted:e.isTrusted}))");
        ui.tap("[data-droy-logo-background-save]");
        try {
            DeviceUi.await("The user background colors did not persist", 10000,
                () -> preferences.getString("brandLogoBackgrounds", "").contains("#E4D8C8")
                    && preferences.getString("brandLogoBackgrounds", "").contains("#B8ACA0"));
        } catch (AssertionError failure) {
            capture("save-background-failure");
            throw new AssertionError(failure.getMessage() + "; " + ui.evaluate("({light:document.querySelector('#droy-logo-background-light').value,dark:document.querySelector('#droy-logo-background-dark').value,disabled:document.querySelector('[data-droy-logo-background-save]').disabled,errors:[...document.querySelectorAll('.droy-error')].map(e=>e.textContent),events:window.__logoSaveTouches})"), failure);
        }
        JSONObject previewLight = appearance("[data-droy-brand-settings] [data-droy-brand-preview=light] [data-droy-brand-mark]");
        JSONObject previewDark = appearance("[data-droy-brand-settings] [data-droy-brand-preview=dark] [data-droy-brand-mark]");
        assertEquals("rgb(228, 216, 200)", previewLight.getString("background"));
        assertEquals("rgb(184, 172, 160)", previewDark.getString("background"));
        ui.visibleWebBounds("[data-droy-brand-settings] .droy-brand-previews");
        capture("user-backgrounds");
        ui.visibleWebBounds(".droy-logo-background-settings .droy-actions");
        capture("background-controls");
        closeSettings();
        assertEquals(previewLight.getString("background"), appearance(HERO).getString("background"));
        chooseTheme("dark");
        assertEquals(previewDark.getString("background"), appearance(HERO).getString("background"));
        android.webkit.WebView view = ui.web();
        instrumentation.runOnMainSync(view::reload);
        awaitPage();
        assertEquals("Custom dark background was lost on reload", previewDark.getString("background"), appearance(HERO).getString("background"));
        openGeneral();
        ui.tap("[data-droy-action=openPetStudio]");
        ui.tap("[data-droy-pet-candidate='" + darkFixture + "'] [data-droy-action=selectPet]");
        DeviceUi.await("Could not switch away from the configured logo", 10000,
            () -> darkFixture.equals(preferences.getString("petId", "")));
        closeSettings();
        assertNotEquals("The default logo's custom background leaked into another logo", previewDark.getString("background"), appearance(HERO).getString("background"));
        openGeneral();
        ui.tap("[data-droy-action=openPetStudio]");
        ui.tap("[data-droy-pet-studio] .droy-pet-card:not([data-droy-pet-candidate]) [data-droy-action=selectPet]");
        DeviceUi.await("Could not switch back to the default logo", 10000,
            () -> "default".equals(preferences.getString("petId", "")));
        closeSettings();
        assertEquals("Switching logos lost the saved default background", previewDark.getString("background"), appearance(HERO).getString("background"));
        openGeneral();
        openBrandDetails();
        ui.tap("[data-droy-logo-background-reset]");
        DeviceUi.await("Automatic background reset did not persist", 10000,
            () -> !preferences.getString("brandLogoBackgrounds", "").contains("#E4D8C8")
                && !preferences.getString("brandLogoBackgrounds", "").contains("#B8ACA0"));
        closeSettings();
        chooseTheme("light");
        assertEquals("Reset did not return to the original automatic palette", automatic.getString("background"), appearance(HERO).getString("background"));
    }

    private void awaitSystemScheme(boolean dark) throws Exception {
        DeviceUi.await("The system appearance did not reach the real App", 20000, () -> {
            AtomicReference<MainActivity> resumed = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof MainActivity main) resumed.set(main);
            });
            if (resumed.get() == null) return false;
            if (resumed.get() != activity) { activity = resumed.get(); ui = new DeviceUi(instrumentation, activity); }
            return ui.web() != null && ui.truth("document.documentElement.dataset.droyUiReady==='true'&&"
                + "document.body.hasAttribute('data-ds-dark-theme')===" + dark
                + "&&matchMedia('(prefers-color-scheme: dark)').matches===" + dark);
        });
        awaitPage();
    }

    private void chooseTheme(String theme) throws Exception {
        openGeneral();
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("The actual theme control did not apply", 10000,
            () -> theme.equals(preferences.getString("themePreference", ""))
                && ui.truth("document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
        closeSettings();
    }

    private JSONObject appearance(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        DeviceUi.await("The logo artwork is not decoded: " + selector, 10000,
            () -> ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + ");return !!e&&e.complete&&e.naturalWidth>0})()"));
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
            + "),s=getComputedStyle(e),r=e.getBoundingClientRect();return {src:e.src,background:s.backgroundColor,radius:s.borderRadius,"
            + "width:r.width,height:r.height,padding:s.padding,objectFit:s.objectFit,filter:s.filter}})()");
        assertFalse("The plate must be visible", result.getString("background").equals("rgba(0, 0, 0, 0)")
            || result.getString("background").equals("transparent"));
        assertTrue("The plate needs rounded corners", Double.parseDouble(result.getString("radius").replaceAll("[^0-9.].*$", "")) > 0);
        assertEquals("Logo must preserve the artwork proportions", "contain", result.getString("objectFit"));
        assertEquals("Logo must not be recolored by a CSS filter", "none", result.getString("filter"));
        assertEquals(result.getDouble("width"), result.getDouble("height"), .1);
        assertTrue("Logo is smaller than its expected visual slot", result.getDouble("width") >= 30);
        return result;
    }

    private static void assertSameGeometryAndSource(JSONObject before, JSONObject after) throws Exception {
        assertEquals("Theme changed the artwork", before.getString("src"), after.getString("src"));
        assertEquals("Theme changed the plate radius", before.getString("radius"), after.getString("radius"));
        assertEquals("Theme changed the artwork inset", before.getString("padding"), after.getString("padding"));
        assertEquals("Theme changed the plate width", before.getDouble("width"), after.getDouble("width"), .1);
        assertEquals("Theme changed the plate height", before.getDouble("height"), after.getDouble("height"), .1);
    }

    private static void assertArtworkContrast(int foreground, String background) {
        String[] channels = background.replaceAll("[^0-9,.]", "").split(",");
        assertTrue("Expected an opaque RGB plate: " + background, channels.length >= 3);
        if (channels.length == 4) assertEquals("Plate must remain opaque", 1, Double.parseDouble(channels[3]), .001);
        double front = luminance(Color.red(foreground), Color.green(foreground), Color.blue(foreground));
        double back = luminance(Double.parseDouble(channels[0]), Double.parseDouble(channels[1]), Double.parseDouble(channels[2]));
        assertTrue("The role's silhouette merges into its automatic plate: " + background,
            (Math.max(front, back) + .05) / (Math.min(front, back) + .05) >= 3);
    }

    private static double luminance(double red, double green, double blue) {
        double[] values = {red / 255, green / 255, blue / 255};
        for (int i = 0; i < values.length; i++) values[i] = values[i] <= .04045 ? values[i] / 12.92 : Math.pow((values[i] + .055) / 1.055, 2.4);
        return values[0] * .2126 + values[1] * .7152 + values[2] * .0722;
    }

    private void awaitPage() throws Exception {
        ui.awaitPage(); ui.hideKeyboard();
        DeviceUi.await("The theme page did not regain native focus", 5000,
            () -> ui.nativeNavigationState().getBoolean("activityFocus"));
    }

    private void openDrawer() throws Exception {
        // A disposable profile has no model credentials; its setup dialog can arrive after reload.
        ui.awaitPage();
        ui.hideKeyboard();
        if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) {
            ui.visibleWebBounds("[data-droy-menu]");
            String[] previous = {null};
            DeviceUi.await("The menu is moving or obscured", 5000, () -> {
                JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-droy-menu]'),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return {rect:r.toJSON(),hit:!!hit&&(e===hit||e.contains(hit))}})()");
                boolean stable = current.toString().equals(previous[0]); previous[0] = current.toString();
                return stable && current.getBoolean("hit");
            });
            ui.tap("[data-droy-menu]");
        }
        try {
            DeviceUi.await("The real menu touch did not open the drawer", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        } catch (AssertionError failure) {
            capture("menu-failure");
            throw new AssertionError(failure.getMessage() + "; native=" + ui.nativeNavigationState(), failure);
        }
    }

    private void closeDrawer() throws Exception {
        ui.tap("[data-droy-drawer-close]");
        DeviceUi.await("Drawer did not close", 5000, () -> !ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
    }

    private void openGeneral() throws Exception {
        openDrawer();
        SettingsEntryDeviceUi.openFromDrawer(ui);
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("General settings did not become interactive", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-theme-choice=light]')&&!document.querySelector('#droy-assistant-name')?.disabled"));
    }

    private void closeSettings() throws Exception {
        ui.hideKeyboard();
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings did not return to the conversation", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void openBrandDetails() throws Exception {
        if (!ui.truth("document.querySelector('[data-droy-brand-settings] details')?.open"))
            ui.tap("[data-droy-brand-settings] summary");
        DeviceUi.await("Logo background fields did not become available", 5000,
            () -> ui.truth("!!document.querySelector('#droy-logo-background-light')"));
        ui.visibleWebBounds("#droy-logo-background-light");
        capture("background-editor");
    }

    private void replaceField(String selector, String value) throws Exception {
        try {
            ui.tap(selector);
            DeviceUi.await("Android did not focus the color field", 5000,
                () -> ui.truth("document.activeElement===document.querySelector(" + JSONObject.quote(selector) + ")"));
            long now = SystemClock.uptimeMillis();
            instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
            instrumentation.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
            instrumentation.sendStringSync(value);
            DeviceUi.await("Trusted input did not update the color field", 5000,
                () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")));
            // Leave Android's Ctrl+A selection mode before touching the next field.
            ui.hideKeyboard();
            ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").blur()");
            ui.tap("[data-droy-settings-header]>span");
        } catch (AssertionError failure) {
            capture("background-input-failure");
            throw new AssertionError(failure.getMessage() + "; field=" + selector + "; geometry="
                + ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                    + "),a=document.activeElement,r=e?.getBoundingClientRect();return {target:r?.toJSON(),"
                    + "active:{tag:a?.tagName,id:a?.id,rect:a?.getBoundingClientRect().toJSON()},"
                    + "viewport:{width:innerWidth,height:innerHeight,top:visualViewport.offsetTop,visibleHeight:visualViewport.height}}})()"), failure);
        }
    }

    private String installFixture(int color, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        File directory = new File(home, "pets/" + id);
        assertTrue(directory.mkdirs());
        JSONObject manifest;
        try (InputStream input = context.getAssets().open("pet/pet.json")) {
            manifest = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        manifest.put("id", id).put("name", name).put("description", "本地主题适配夹具");
        manifest.put("branding", new JSONObject().put("logo", "brand-logo.png"));
        JSONObject atlases = manifest.getJSONObject("atlases");
        for (java.util.Iterator<String> keys = atlases.keys(); keys.hasNext();) {
            String file = atlases.getJSONObject(keys.next()).getString("file");
            try (InputStream input = context.getAssets().open("pet/" + file)) {
                Files.copy(input, new File(directory, file).toPath());
            }
        }
        Files.write(new File(directory, "pet.json").toPath(), manifest.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject draft = new JSONObject().put("id", id).put("name", name).put("description", "本地主题适配夹具")
            .put("personality", "简洁").put("persona", manifest.getJSONObject("persona")).put("model", "gpt-image-1.5")
            .put("groups", new JSONObject()).put("status", "ready").put("stage", "本地夹具已准备")
            .put("referenceReady", true).put("referenceCurrent", true).put("brandLogoReady", true)
            .put("createdAt", System.currentTimeMillis()).put("updatedAt", System.currentTimeMillis());
        Files.write(new File(directory, "draft.json").toPath(), draft.toString().getBytes(StandardCharsets.UTF_8));
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(color);
            canvas.drawOval(96, 112, 416, 432, paint);
            for (String file : new String[]{"reference.png", "brand-logo.png"})
                try (FileOutputStream output = new FileOutputStream(new File(directory, file))) {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
        } finally { image.recycle(); }
        return id;
    }

    private void capture(String stage) throws Exception {
        DeviceUi.await("The screenshot surface is still animating", 5000,
            () -> ui.truth("document.getAnimations().every(a=>a.playState!=='running'||a.effect?.getTiming().iterations===Infinity)"));
        ui.evaluate("window.__brandThemePainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__brandThemePainted=true))");
        DeviceUi.await("The screenshot did not reach a painted frame", 5000, () -> ui.truth("window.__brandThemePainted"));
        android.webkit.WebView view = ui.web();
        java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> view.postVisualStateCallback(SystemClock.uptimeMillis(), new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long id) {
                view.postOnAnimation(() -> {
                    view.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    view.invalidate();
                });
            }
        }));
        assertTrue("The screenshot WebView frame did not commit", committed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try {
            File folder = new File(context.getExternalFilesDir(null), "brand-theme-evidence");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("brandingEvidenceLabel", "theme").replaceAll("[^A-Za-z0-9_-]", "_");
            File output = File.createTempFile(label + "-" + stage + "-", ".png", folder);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            JSONObject conditions = (JSONObject) ui.evaluate("({cssWidth:innerWidth,cssHeight:innerHeight,dark:document.body.hasAttribute('data-ds-dark-theme'),source:document.documentElement.getAttribute('data-ds-theme-source'),dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_BRAND_THEME_EVIDENCE " + output.getAbsolutePath()
                + "\nDROY_BRAND_THEME_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }

    private void shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))) {
            String output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse("The system appearance command failed", output.contains("Error") || output.contains("Exception"));
        }
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

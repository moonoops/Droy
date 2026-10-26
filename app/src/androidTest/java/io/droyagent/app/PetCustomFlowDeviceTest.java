package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises a preinstalled fictional package through real settings; never calls a cloud generator. */
@RunWith(AndroidJUnit4.class)
public final class PetCustomFlowDeviceTest {
    private static final String[] PREF_KEYS = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "petId", "petName", "petLeft", "petX", "petY"};
    private final Map<String, Object> original = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private String fixtureId, fixtureName, card, preview;
    private boolean preferencesOwned, runtimeOwned;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Custom pet fixtures require a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        fixtureId = InstrumentationRegistry.getArguments().getString("petFixtureId");
        assertNotNull("Supply -e petFixtureId with the UUID of a preinstalled fictional ready package", fixtureId);
        assertTrue("The fixture ID must be a UUID", fixtureId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
        File folder = new File(context.getFilesDir(), "dsh-home/pets/" + fixtureId);
        assertTrue("Preinstall the fixture manifest before running", new File(folder, "pet.json").isFile());
        JSONObject draft = new JSONObject(new String(Files.readAllBytes(new File(folder, "draft.json").toPath()), StandardCharsets.UTF_8));
        assertEquals("The fixture must already be ready", "ready", draft.getString("status"));
        fixtureName = draft.getString("name");
        assertTrue("Use only a clearly marked fictional loading fixture", draft.getString("description").contains("夹具"));
        assertTrue("Stop existing tasks and runtime before this test", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> saved = preferences.getAll();
        for (String key : PREF_KEYS) if (saved.containsKey(key)) original.put(key, saved.get(key));
        preferencesOwned = true;
        assertTrue(preferences.edit().putString("petId", "default").putString("petName", "Droy")
                .putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").commit());
        runtimeOwned = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity); ui.awaitPage(); ui.hideKeyboard();
        ui.tap("[data-droy-menu]");
        DeviceUi.await("Menu did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        SettingsEntryDeviceUi.openFromDrawer(ui);
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("Pet settings did not load", 10000, () -> ui.truth("!!document.querySelector('[data-droy-action=openPetStudio]')"));
        // Block unexpected creation requests while leaving the actual authenticated read endpoints intact.
        ui.evaluate("window.__petFixtureOriginalFetch=window.fetch;window.__petFixtureWrites=0;window.fetch=function(url,options){if(String(url).startsWith('/droy/pets/')&&options?.method==='POST'){window.__petFixtureWrites++;return Promise.reject(new Error('Fixture must not generate pets'))}return window.__petFixtureOriginalFetch.call(this,url,options)}");
        card = "[data-droy-pet-candidate='" + fixtureId + "']";
        preview = "[data-droy-pet-preview='" + fixtureId + "']";
    }

    @After public void tearDown() throws Exception {
        try {
            if (runtimeOwned) {
                if (ui != null && activity != null && !activity.isDestroyed() && RuntimeService.ready)
                    ui.evaluate("if(window.__petFixtureOriginalFetch)window.fetch=window.__petFixtureOriginalFetch;delete window.__petFixtureOriginalFetch;delete window.__petFixtureWrites;delete window.__petFixtureSamples");
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Custom pet fixture left the runtime active", 15000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped(); instrumentation.waitForIdleSync();
            }
        } finally {
            if (preferencesOwned) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : PREF_KEYS) {
                    Object value = original.get(key);
                    if (value instanceof String text) editor.putString(key, text);
                    else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                    else if (value instanceof Float number) editor.putFloat(key, number);
                    else editor.remove(key);
                }
                assertTrue("Could not restore the selected pet and display preferences", editor.commit());
            }
        }
    }

    @Test public void readyFixtureCanBePreviewedOnBothSidesTouchedAndSelected() throws Exception {
        ui.tap("[data-droy-action=openPetStudio]");
        DeviceUi.await("Ready fixture is missing from the real gallery", 10000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(card) + ")"));
        assertTrue("Fixture must expose model-derived voice preview", ui.truth("!!document.querySelector(" + JSONObject.quote(card + " [aria-label='说话风格预览']") + ")"));
        ui.tap(card + " [data-droy-action=previewPetCandidate]");
        DeviceUi.await("Real sprite images did not load", 15000, () -> ui.truth("(()=>{const p=document.querySelector(" + JSONObject.quote(preview)
                + ");return p&&!p.querySelector('.droy-pet-stage').disabled&&!p.querySelector('[role=alert]')})()"));
        assertPixels();
        ui.tap(preview + " [data-droy-pet-side=left]");
        DeviceUi.await("Left preview did not place the actual sprite inside the left half", 5000, () -> {
            JSONObject stats = pixels(); return stats.getInt("count") > 100 && stats.getDouble("centerX") < stats.getDouble("width") / 2;
        });
        capture("custom-left");
        ui.tap(preview + " [data-droy-pet-side=right]");
        DeviceUi.await("Right preview did not place the actual sprite inside the right half", 5000, () -> {
            JSONObject stats = pixels(); return stats.getInt("count") > 100 && stats.getDouble("centerX") > stats.getDouble("width") / 2;
        });
        capture("custom-right");
        // Wait for initial idle to settle so a changing canvas proves the injected interaction starts a new animation.
        SystemClock.sleep(2400);
        observeFrames(); ui.tap(preview + " [data-droy-preview-action=tap]"); assertFramesChanged("Click action did not animate real frames");
        SystemClock.sleep(2400);
        observeFrames(); ui.longPress(preview + " .droy-pet-stage", 650); assertFramesChanged("A trusted long press did not play affection frames");
        capture("custom-affection");
        ui.tap(preview + " [data-droy-preview-action=exit]");
        DeviceUi.await("Exit did not clear the sprite", 5000, () -> pixels().getInt("count") == 0);
        ui.tap(preview + " [data-droy-preview-action=enter]");
        DeviceUi.await("Entry did not restore the sprite", 5000, () -> pixels().getInt("count") > 100);
        assertEquals("Preview changed the selected pet before Use", "default", preferences.getString("petId", ""));
        ui.tap(card + " [data-droy-action=selectPet]");
        DeviceUi.await("Use did not persist the actual native selection while retaining the companion name", 10000,
                () -> fixtureId.equals(preferences.getString("petId", "")) && "Droy".equals(preferences.getString("petName", ""))
                    && ui.truth("document.querySelector(" + JSONObject.quote(card) + ").textContent.includes('正在使用')"));
        assertEquals("Internal display preference changed while selecting a pet", "off", preferences.getString("petInternalDisplay", ""));
        assertEquals("External display preference changed while selecting a pet", "off", preferences.getString("petExternalDisplay", ""));
        assertTrue("The local fixture flow unexpectedly requested cloud creation", ui.truth("window.__petFixtureWrites===0"));
        capture("custom-selected");
        ui.pressBack();
        DeviceUi.await("Back did not return from pet settings", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private JSONObject pixels() throws Exception {
        return (JSONObject) ui.evaluate("(()=>{const c=document.querySelector(" + JSONObject.quote(preview + " canvas")
                + "),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let count=0,sumX=0;for(let i=3;i<p.length;i+=4)if(p[i]>64){count++;sumX+=(i>>2)%c.width}return {count,centerX:count?sumX/count:0,width:c.width}})()");
    }
    private void assertPixels() throws Exception {
        DeviceUi.await("The preview canvas contains no visible sprite", 5000, () -> pixels().getInt("count") > 100);
    }
    private void observeFrames() throws Exception {
        ui.visibleWebBounds(preview + " .droy-pet-stage");
        ui.evaluate("window.__petFixtureSamples={done:false,values:[]};(()=>{const c=document.querySelector(" + JSONObject.quote(preview + " canvas")
                + "),start=performance.now();const sample=()=>{let h=2166136261;const p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;for(let i=0;i<p.length;i+=16)h=Math.imul(h^p[i]^p[i+3],16777619);window.__petFixtureSamples.values.push(h);if(performance.now()-start<1500)requestAnimationFrame(sample);else window.__petFixtureSamples.done=true};requestAnimationFrame(sample)})()");
    }
    private void assertFramesChanged(String message) throws Exception {
        DeviceUi.await("Frame observation did not finish", 5000, () -> ui.truth("window.__petFixtureSamples.done"));
        assertTrue(message, ui.truth("new Set(window.__petFixtureSamples.values).size>1"));
        assertPixels();
    }
    private void capture(String stage) throws Exception {
        ui.visibleWebBounds(preview + " .droy-pet-stage");
        instrumentation.getUiAutomation().waitForIdle(200, 3000);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try {
            File folder = new File(context.getExternalFilesDir(null), "pet-ui-evidence");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            File output = File.createTempFile(stage + "-", ".png", folder);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_PET_EVIDENCE " + output.getAbsolutePath() + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }
}

package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses saved identity and the real authenticated bridge without model calls or conversation writes. */
@RunWith(AndroidJUnit4.class)
public final class CompanionIdentityDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void identityFollowsSavedNamesAndAppearanceWithoutRequiringAnEngineRestart() throws Exception {
        // Constructing a bridge recovers interrupted progress; never interrupt a running user task for this test.
        assertFalse("Wait for active tasks to finish before testing the identity bridge",
            ToolProgress.current(context).getBoolean("active"));
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"petId", "petName", "assistantName"};
        Map<String, String> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getString(key, ""));
        PetPackage originalAppearance = PetPackage.selected(context);
        String progressPreferences = "companion-test-progress-" + UUID.randomUUID();
        Context bridgeContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name.equals("tool-progress") ? progressPreferences : name, mode);
            }
        };
        ProductBridge bridge = null;
        try {
            assertTrue(settings.edit().putString("petId", "default").remove("petName").remove("assistantName").commit());
            String token = "fictional-companion-identity-test-token";
            bridge = new ProductBridge(bridgeContext, token);
            JSONObject defaults = bridge.companionIdentity();
            assertEquals("Droy", defaults.getString("name"));
            assertEquals("Droy", defaults.getString("displayName"));
            assertEquals("default", defaults.getString("appearanceId"));
            for (String trait : new String[]{"机智", "机灵", "不啰嗦"})
                assertTrue("The shipped black cat must provide its default personality", defaults.getString("personality").contains(trait));

            assertTrue(settings.edit().putString("petName", "墨墨").putString("assistantName", "随身助理").commit());
            JSONObject renamed = bridge.companionIdentity();
            assertEquals("墨墨", renamed.getString("name"));
            assertEquals("随身助理", renamed.getString("displayName"));
            PetOverlay.select(context, "default");
            instrumentation.waitForIdleSync();
            assertEquals("Applying an appearance must retain the companion name", "墨墨", settings.getString("petName", ""));
            assertEquals("Applying an appearance must retain the page title", "随身助理", settings.getString("assistantName", ""));

            bridge.start(5000, false);
            Reply unauthorized = post(bridge.getListeningPort(), null);
            assertEquals(401, unauthorized.status);
            assertFalse(unauthorized.body.getBoolean("ok"));
            assertFalse("An unauthenticated reply must not expose identity", unauthorized.body.has("result"));
            Reply authorized = post(bridge.getListeningPort(), token);
            assertEquals(200, authorized.status);
            assertTrue(authorized.body.getBoolean("ok"));
            assertEquals("墨墨", authorized.body.getJSONObject("result").getString("name"));
            assertEquals("随身助理", authorized.body.getJSONObject("result").getString("displayName"));
            assertTrue(settings.edit().putString("petName", "小墨").commit());
            JSONObject updated = post(bridge.getListeningPort(), token).body.getJSONObject("result");
            assertEquals("小墨", updated.getString("name"));
            assertEquals("随身助理", updated.getString("displayName"));
        } finally {
            if (bridge != null) bridge.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putString(key, previous.get(key)); else restore.remove(key);
            }
            assertTrue("Could not restore the saved companion settings", restore.commit());
            PetPackage.use(originalAppearance);
            PetOverlay.refresh(context);
            instrumentation.waitForIdleSync();
            assertTrue(context.deleteSharedPreferences(progressPreferences));
        }
    }

    @Test public void compactRuntimeReadsCurrentProductChoicesAndStopState() throws Exception {
        String fixture = "companion-runtime-" + UUID.randomUUID();
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name.equals("product") || name.equals("tool-progress")
                    ? fixture + "-" + name : name, mode);
            }
        };
        ProductBridge bridge = new ProductBridge(isolated, "fictional-runtime-test-token");
        SharedPreferences settings = isolated.getSharedPreferences("product", Context.MODE_PRIVATE);
        try {
            assertTrue(bridge.companionIdentity().getJSONObject("runtime").getBoolean("stopped"));
            bridge.policy.enable();
            JSONObject enabled = bridge.companionIdentity().getJSONObject("runtime");
            assertFalse(enabled.getBoolean("stopped"));
            for (String key : new String[]{"observeAllowed", "controlAllowed", "screenshotsAllowed", "appsAllowed", "rootEnabled"})
                assertTrue(key, enabled.getBoolean(key));
            assertEquals(io.droyagent.androiduse.DroyAccessibilityService.isConnected(isolated),
                enabled.getBoolean("accessibilityConnected"));
            assertEquals("The startup summary must not expand into the full capability catalogue", 7, enabled.length());

            assertTrue(settings.edit().putBoolean("allowObserve", false).putBoolean("allowControl", false)
                .putBoolean("allowScreenshot", false).putBoolean("allowApps", false).putBoolean("allowRoot", false)
                .putBoolean(ProductPermissions.STOPPED, true).commit());
            JSONObject revoked = bridge.companionIdentity().getJSONObject("runtime");
            assertTrue(revoked.getBoolean("stopped"));
            for (String key : new String[]{"observeAllowed", "controlAllowed", "screenshotsAllowed", "appsAllowed", "rootEnabled"})
                assertFalse(key, revoked.getBoolean(key));
        } finally {
            bridge.stop();
            assertTrue(context.deleteSharedPreferences(fixture + "-product"));
            assertTrue(context.deleteSharedPreferences(fixture + "-tool-progress"));
        }
    }

    @Test public void launcherUsesDroyNameAndRendersTheShippedBlackCat() throws Exception {
        assertEquals("Droy", context.getApplicationInfo().loadLabel(context.getPackageManager()).toString());
        Drawable icon = context.getApplicationInfo().loadIcon(context.getPackageManager());
        assertNotNull(icon);
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        try {
            icon.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            icon.draw(new Canvas(bitmap));
            int black = 0, yellow = 0;
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) < 200) continue;
                if (Color.red(color) < 50 && Color.green(color) < 50 && Color.blue(color) < 50) black++;
                if (Color.red(color) > 180 && Color.green(color) > 150 && Color.blue(color) < 110) yellow++;
            }
            assertTrue("The launcher icon must render the black silhouette", black > 1000);
            assertTrue("The launcher icon must retain the yellow eyes", yellow > 100);
            File folder = new File(context.getExternalFilesDir(null), "companion-ui-evidence");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            File output = new File(folder, "launcher-icon.png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue("Could not save the actual launcher drawable", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            android.util.Log.i("DroyCompanionTest", "Launcher icon evidence: " + output.getAbsolutePath());
        } finally { bitmap.recycle(); }
    }

    private static Reply post(int port, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/companion").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false); connection.setDoOutput(true);
            // Each credential case owns its connection; rejected requests may leave an unread body.
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                assertNotNull("Identity HTTP " + status + " returned no response body", input);
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                try { return new Reply(status, new JSONObject(text)); }
                catch (org.json.JSONException invalid) {
                    throw new AssertionError("Identity HTTP " + status + " returned non-JSON (" + text.length() + " characters)", invalid);
                }
            }
        } finally { connection.disconnect(); }
    }

    private static final class Reply {
        final int status;
        final JSONObject body;
        Reply(int status, JSONObject body) { this.status = status; this.body = body; }
    }
}

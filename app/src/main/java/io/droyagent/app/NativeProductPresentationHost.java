package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Read-only product presentation; installed artwork is an APK asset, never a web request. */
public final class NativeProductPresentationHost {
    public static final String LOGO_ASSET = "app-brand-logo.png";
    private static final Set<String> KEYS = Set.of("assistantName", "brandSidebarLabel", "brandHeroBadge",
        "brandWelcomeTemplate", "brandComposerTemplate", "brandLogoBackgrounds", "languagePreference");
    private final Context context;
    private final SharedPreferences preferences;

    public NativeProductPresentationHost(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("product", Context.MODE_PRIVATE);
    }

    public enum Error { INVALID_PREFERENCES, BRANDING_UNAVAILABLE, LOGO_UNAVAILABLE }
    public static final class Failure extends Exception {
        public final Error error;
        private Failure(Error error, Throwable cause) { super("native_presentation_" + error.name(), cause); this.error = error; }
    }

    public static final class Snapshot {
        public final String appName, workbenchTitleOverride, welcomeOverride, heroBadge, composerHintOverride;
        public final Integer logoLightColor, logoDarkColor;
        public final String logoAsset = LOGO_ASSET;
        private Snapshot(String appName, String title, String welcome, String badge, String composer, Integer light, Integer dark) {
            this.appName = appName; workbenchTitleOverride = title; welcomeOverride = welcome; heroBadge = badge;
            composerHintOverride = composer; logoLightColor = light; logoDarkColor = dark;
        }
    }

    public static boolean observes(String key) { return key == null || KEYS.contains(key); }
    public void startObserving(SharedPreferences.OnSharedPreferenceChangeListener listener) { preferences.registerOnSharedPreferenceChangeListener(listener); }
    public void stopObserving(SharedPreferences.OnSharedPreferenceChangeListener listener) { preferences.unregisterOnSharedPreferenceChangeListener(listener); }

    public Snapshot snapshot() throws Failure {
        return decode(new JSONObject(), Map.of(), context.getString(R.string.droy_app_name));
    }

    static Snapshot decode(JSONObject branding, Map<String, ?> saved, String installedName) throws Failure {
        // Legacy branding overrides remain stored for compatibility, but native product copy is fixed.
        return new Snapshot(installedName, null, null, "", null, null, null);
    }

    public Bitmap loadLogo() throws Failure {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(LOGO_ASSET)) { BitmapFactory.decodeStream(input, null, bounds); }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096)
                throw new IllegalStateException("invalid_installed_logo");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (options.inSampleSize * 2) >= 256) options.inSampleSize *= 2;
            try (InputStream input = context.getAssets().open(LOGO_ASSET)) {
                Bitmap logo = BitmapFactory.decodeStream(input, null, options);
                if (logo == null) throw new IllegalStateException("unreadable_installed_logo");
                return logo;
            }
        } catch (Exception failure) { throw new Failure(Error.LOGO_UNAVAILABLE, failure); }
    }
}

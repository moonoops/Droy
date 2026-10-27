package io.droyagent.androiduse;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Locale;
import org.json.JSONObject;

/** Small shared validators and the native mirror of the saved product language. */
public final class HardwareSupport {
    private HardwareSupport() {}

    public static String text(Context context, int resourceId) {
        String language = context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("languagePreference", null);
        if (!"zh".equals(language) && !"en".equals(language)) {
            LocaleList system = Resources.getSystem().getConfiguration().getLocales();
            language = !system.isEmpty() && "zh".equals(system.get(0).getLanguage()) ? "zh" : "en";
        }
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(language));
        return context.createConfigurationContext(configuration).getString(resourceId);
    }

    public static ActionException error(Context context, String code, int resourceId) {
        return new ActionException(code, text(context, resourceId));
    }

    public static int integer(JSONObject args, String key, int defaultValue, int minimum, int maximum) throws ActionException {
        if (!args.has(key)) return defaultValue;
        Object value = args.opt(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())
                || ((Number) value).doubleValue() != ((Number) value).longValue()
                || ((Number) value).longValue() < minimum || ((Number) value).longValue() > maximum)
            throw new ActionException("invalid_argument", key).withDetails(details(key, minimum, maximum));
        return ((Number) value).intValue();
    }

    private static JSONObject details(String key, int minimum, int maximum) {
        try { return new JSONObject().put("field", key).put("min", minimum).put("max", maximum); }
        catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
    }

    public static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }

    public static byte[] unhex(String hex, int maximumBytes) throws ActionException {
        if (hex == null || hex.length() % 2 != 0 || hex.length() / 2 > maximumBytes)
            throw new ActionException("invalid_argument", "hex");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16), low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0 || hex.charAt(i * 2) > 127 || hex.charAt(i * 2 + 1) > 127)
                throw new ActionException("invalid_argument", "hex");
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}

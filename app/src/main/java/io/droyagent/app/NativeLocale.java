package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.LocaleList;
import org.json.JSONArray;
import org.json.JSONObject;

/** The native shell mirrors DSH's accepted General → Language selection. */
public final class NativeLocale {
    private static final String PREFERENCE = "languagePreference";
    private static volatile SharedPreferences preferences;
    private static volatile String processLanguage;
    private static final java.util.concurrent.ConcurrentHashMap<String, String[]> generated = new java.util.concurrent.ConcurrentHashMap<>();
    private NativeLocale() {}

    static void initialize(Context context) {
        // Application.attachBaseContext runs before getApplicationContext is bound.
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
    }

    static String normalize(String tag) {
        return tag != null && tag.replace('_', '-').split("-", 2)[0].equalsIgnoreCase("zh") ? "zh" : "en";
    }

    public static String active() {
        String mirrored = processLanguage;
        if (mirrored != null) return mirrored;
        SharedPreferences current = preferences;
        // Android initializes this in Application before any service or activity;
        // host-side unit tests have no Application and retain their Chinese fixtures.
        if (current == null) return "zh";
        String saved = current.getString(PREFERENCE, null);
        if ("zh".equals(saved) || "en".equals(saved)) return saved;
        LocaleList languages = Resources.getSystem().getConfiguration().getLocales();
        return normalize(languages.isEmpty() ? null : languages.get(0).toLanguageTag());
    }

    // Browser contents run in a separate process. Mirror the caller's language
    // over its existing Binder request, without cross-process preference writes.
    static void mirrorProcessLanguage(String language) {
        if ("zh".equals(language) || "en".equals(language)) processLanguage = language;
    }

    public static String text(String chinese, String english) {
        String[] pair = {chinese, english};
        generated.putIfAbsent(chinese, pair);
        generated.putIfAbsent(english, pair);
        return active().equals("zh") ? chinese : english;
    }

    /** Only for shell-generated status text, never screen content or user records. */
    static String generatedStatus(String status) {
        int target = active().equals("zh") ? 0 : 1;
        String[] exact = generated.get(status);
        if (exact != null) return exact[target];
        String prefix = "";
        String[] pair = null;
        for (java.util.Map.Entry<String, String[]> entry : generated.entrySet()) {
            String key = entry.getKey();
            if (key.length() > prefix.length() && status.startsWith(key)) {
                prefix = key; pair = entry.getValue();
            }
        }
        return pair == null ? status : pair[target] + status.substring(prefix.length());
    }

    static JSONObject bootstrap() throws org.json.JSONException {
        JSONArray languages = new JSONArray();
        LocaleList system = Resources.getSystem().getConfiguration().getLocales();
        for (int index = 0; index < system.size(); index++) languages.put(system.get(index).toLanguageTag());
        // One supported default is chosen from the primary installation language.
        // A secondary Chinese keyboard/language does not override an English phone.
        return new JSONObject().put("languages", new JSONArray().put(active()))
            .put("systemLanguages", languages)
            .put("preference", preferences == null || preferences.getString(PREFERENCE, null) == null ? JSONObject.NULL : preferences.getString(PREFERENCE, null))
            .put("active", active());
    }

    static void adopt(String language) {
        if (!"zh".equals(language) && !"en".equals(language)) throw new IllegalArgumentException(text("不支持的语言", "Unsupported language"));
        SharedPreferences current = preferences;
        if (current == null || language.equals(current.getString(PREFERENCE, null))) return;
        if (!current.edit().putString(PREFERENCE, language).commit()) throw new IllegalStateException(text("语言未保存，请重试", "Language could not be saved. Please try again"));
    }

    static String webBootstrapScript() {
        try {
            String failed = JSONObject.quote(text("语言同步失败", "Language synchronization failed"));
            String timedOut = JSONObject.quote(text("语言同步超时，请重新加载", "Language synchronization timed out. Please reload"));
            return "(()=>{if(window.__DSH_LOCALE__)return;const initial=" + bootstrap() + ";"
                + "let sequence=0,pending=Promise.resolve(),calls=new Map();"
                + "window.DroyLocaleNative.onmessage=event=>{const result=JSON.parse(event.data),call=calls.get(result.id);"
                + "if(!call)return;calls.delete(result.id);clearTimeout(call.timer);"
                + "if(result.ok){call.resolve();window.dispatchEvent(new Event('droy-native-locale-change'));}"
                + "else call.reject(new Error(result.error||" + failed + "));};"
                + "window.__DSH_LOCALE__={read:async()=>initial,whenSynced:()=>pending,onChange:language=>{"
                + "pending=new Promise((resolve,reject)=>{const id=++sequence;"
                + "const timer=setTimeout(()=>{calls.delete(id);reject(new Error(" + timedOut + "));},10000);"
                + "calls.set(id,{resolve,reject,timer});window.DroyLocaleNative.postMessage(JSON.stringify({id,language}));});"
                + "pending.catch(error=>console.error(error));return pending;}};})();";
        } catch (org.json.JSONException failure) { throw new IllegalStateException(text("语言初始化失败", "Language initialization failed"), failure); }
    }
}

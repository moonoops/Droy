package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Product presentation defaults are separate from engine settings and partner identity. */
final class AppBranding {
    private static JSONObject defaults;

    private static synchronized JSONObject defaults(Context context) {
        if (defaults == null) {
            try (InputStream input = context.getAssets().open("branding.json")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                defaults = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                for (String field : new String[]{"name", "sidebarLabel", "welcomeTemplate", "composerTemplate"})
                    text(defaults.get(field), field.equals("name") || field.equals("sidebarLabel") ? 40 : 240);
                plainText(defaults.get("heroBadge"), 0, 40);
            } catch (Exception failure) {
                throw new IllegalStateException(io.droyagent.app.NativeLocale.text("内置品牌配置无法读取", "Unable to read built-in branding configuration"), failure);
            }
        }
        return defaults;
    }

    static String name(Context context, SharedPreferences preferences) {
        return preferences.getString("assistantName", context.getString(R.string.droy_app_name));
    }

    static JSONObject state(Context context, SharedPreferences preferences) throws Exception {
        JSONObject base = defaults(context);
        JSONObject backgrounds = new JSONObject(preferences.getString("brandLogoBackgrounds", "{}"));
        String petId = preferences.getString("petId", "default");
        return new JSONObject().put("name", name(context, preferences))
            .put("sidebarLabel", preferences.getString("brandSidebarLabel", base.getString("sidebarLabel")))
            .put("heroBadge", preferences.getString("brandHeroBadge", base.getString("heroBadge")))
            .put("welcomeTemplate", preferences.getString("brandWelcomeTemplate", defaultTemplate(base, "welcomeTemplate")))
            .put("composerTemplate", preferences.getString("brandComposerTemplate", defaultTemplate(base, "composerTemplate")))
            .put("logoPetId", petId)
            .put("logoBackgrounds", backgrounds.opt(petId)).put("logoBackgroundsByPet", backgrounds)
            .put("logoUrl", "/droy/branding/installed-logo")
            .put("dshVersion", RuntimeInstaller.release(context).dshVersion);
    }

    private static String defaultTemplate(JSONObject base, String key) throws org.json.JSONException {
        String value = base.getString(key);
        if (key.equals("welcomeTemplate") && value.equals("与{name}开始对话"))
            return NativeLocale.text(value, "Start a conversation with {name}");
        if (key.equals("composerTemplate") && value.equals("请描述你想让{name}执行的任务，或者你的对话"))
            return NativeLocale.text(value, "Describe a task for {name}, or start a conversation");
        return value;
    }

    static boolean isPreference(String key) {
        return key.equals("brandSidebarLabel") || key.equals("brandHeroBadge") || key.equals("brandWelcomeTemplate") || key.equals("brandComposerTemplate");
    }

    static String updateLogoBackgrounds(String stored, String activePetId, JSONObject args) throws Exception {
        String petId = args.getString("petId");
        if (!petId.equals(activePetId)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("伙伴形象已切换，请重新打开底色设置", "Companion artwork changed. Please reopen background settings"));
        JSONObject palettes = new JSONObject(stored);
        if (args.has("reset") && NativeUiProtocol.booleanArgument(args, "reset")) palettes.remove(petId);
        else palettes.put(petId, new JSONObject().put("light", logoColor(args.get("light"))).put("dark", logoColor(args.get("dark"))));
        return palettes.toString();
    }

    private static String logoColor(Object value) {
        if (!(value instanceof String) || !((String) value).matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("底色需为 #RRGGBB 格式", "Background color must use #RRGGBB format"));
        return ((String) value).toUpperCase(Locale.ROOT);
    }

    static String preference(String key, Object value) {
        if (!isPreference(key)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("此品牌设置不能修改", "This branding setting cannot be changed"));
        if (key.equals("brandHeroBadge")) return plainText(value, 0, 40);
        return text(value, key.equals("brandSidebarLabel") ? 40 : 240);
    }

    static String text(Object value, int maximum) {
        return plainText(value, 1, maximum);
    }

    private static String plainText(Object value, int minimum, int maximum) {
        if (!(value instanceof String)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("品牌文案必须是文字", "Branding copy must be text"));
        String text = ((String) value).trim();
        if (text.codePointCount(0, text.length()) < minimum || text.codePointCount(0, text.length()) > maximum)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请输入 ", "Please enter ") + minimum + "–" + maximum + io.droyagent.app.NativeLocale.text(" 个字符", " characters"));
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0 || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("品牌文案需使用单行纯文字", "Branding copy must be single-line plain text"));
        return text;
    }
}

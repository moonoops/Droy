package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;

final class PetPersona {
    static final PetPersona DEFAULT = new PetPersona("curious", "机智、机灵、不啰嗦，亲近自然，工作时专注；幽默适量，不夸大进度。", null, null);
    final String temperament, description;
    private String language;

    boolean matchesLanguage() { return language != null && language.equals(NativeLocale.active()); }

    String description() {
        return this == DEFAULT ? NativeLocale.text(description, "Witty, curious, concise, approachable and natural; focused at work, with measured humor and honest progress updates.") : description;
    }
    final String[] working, idle;

    private PetPersona(String temperament, String description, String[] working, String[] idle) {
        this.temperament = temperament; this.description = description; this.working = working; this.idle = idle;
    }

    static PetPersona parse(JSONObject value) {
        if (value == null) return DEFAULT;
        String temperament = value.optString("temperament", "curious");
        if (!java.util.Set.of("curious", "calm", "playful", "shy").contains(temperament))
            throw new IllegalArgumentException(NativeLocale.text("伙伴动作性格无效", "Invalid companion temperament"));
        String description = value.optString("description", "").trim();
        if (description.codePointCount(0, description.length()) > 1000) throw new IllegalArgumentException(NativeLocale.text("伙伴性格描述过长", "Companion personality description is too long"));
        JSONObject voice = value.optJSONObject("voice");
        PetPersona result = new PetPersona(temperament, description, lines(voice, "working"), lines(voice, "idle"));
        String language = value.optString("language", "");
        result.language = language.equals("zh") || language.equals("en") ? language : inferredLanguage(result);
        return result;
    }

    private static String inferredLanguage(PetPersona persona) {
        StringBuilder samples = new StringBuilder();
        if (persona.working != null) for (String line : persona.working) samples.append(line);
        if (persona.idle != null) for (String line : persona.idle) samples.append(line);
        return samples.codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN) ? "zh" : "en";
    }

    private static String[] lines(JSONObject voice, String key) {
        if (voice == null || !voice.has(key)) return null;
        JSONArray source = voice.optJSONArray(key);
        if (source == null || source.length() < 2 || source.length() > 16) throw new IllegalArgumentException(NativeLocale.text("伙伴语气示例数量无效", "Invalid companion voice example count"));
        String[] result = new String[source.length()];
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < result.length; i++) {
            Object raw = source.opt(i);
            if (!(raw instanceof String text) || text.isBlank() || text.codePointCount(0, text.length()) > 120 || text.contains("\n"))
                throw new IllegalArgumentException(NativeLocale.text("伙伴语气示例无效", "Invalid companion voice example"));
            if (!seen.add(text)) throw new IllegalArgumentException(NativeLocale.text("伙伴语气示例不能重复", "Companion voice examples must not repeat"));
            result[i] = text;
        }
        return result;
    }

    int weight(String state) {
        String lower = state.toLowerCase(java.util.Locale.ROOT);
        return switch (temperament) {
            case "playful" -> lower.contains("jump") || lower.contains("wave") || lower.contains("tail") ? 4 : 1;
            case "shy" -> lower.contains("peek") || lower.contains("shy") || lower.contains("hide") ? 4 : 1;
            case "calm" -> lower.contains("walk") || lower.contains("stretch") || lower.equals("idle") ? 4 : 1;
            default -> lower.contains("peek") || lower.contains("tilt") ? 3 : 1;
        };
    }
}

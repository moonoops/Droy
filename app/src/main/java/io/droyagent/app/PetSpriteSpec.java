package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Frame coordinates and one-shot timing for the bundled pet. */
final class PetSpriteSpec {
    static final String[] STATES = {"idle", "working", "speaking", "waiting", "tap", "success", "peekLeft", "peekRight"};

    final Map<String, Atlas> atlases;
    final Map<String, Animation> animations;
    final Map<String, String[]> variants;
    final Map<String, Label> labels;
    final Map<String, Effect> effects;
    final boolean pixelArt;
    final PetPersona persona;

    private PetSpriteSpec(Map<String, Atlas> atlases, Map<String, Animation> animations, Map<String, String[]> variants,
            Map<String, Label> labels, Map<String, Effect> effects, boolean pixelArt, PetPersona persona) {
        this.atlases = Collections.unmodifiableMap(atlases);
        this.animations = Collections.unmodifiableMap(animations);
        this.variants = Collections.unmodifiableMap(variants); this.labels = Collections.unmodifiableMap(labels);
        this.effects = Collections.unmodifiableMap(effects); this.pixelArt = pixelArt; this.persona = persona;
    }

    static PetSpriteSpec parse(JSONObject document) throws Exception {
        int version = document.getInt("formatVersion");
        if (version != 1 && version != 2) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物素材版本无效", "Invalid companion asset version"));
        Map<String, Atlas> atlases = new LinkedHashMap<>();
        JSONObject atlasRows = document.getJSONObject("atlases");
        if (atlasRows.length() == 0 || atlasRows.length() > 12) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物图集数量无效", "Invalid companion atlas count"));
        long pixels = 0;
        for (Iterator<String> keys = atlasRows.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONObject row = atlasRows.getJSONObject(key);
            String file = row.getString("file");
            if (!file.matches("[A-Za-z0-9_-]+\\.png")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物图片文件名无效", "Invalid companion image filename"));
            int width = row.getInt("width"), height = row.getInt("height");
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (pixels += (long)width * height) > 32_000_000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物图片尺寸无效", "Invalid companion image dimensions"));
            JSONArray source = row.getJSONArray("frames");
            if (source.length() == 0 || source.length() > 1024) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物帧数量无效", "Invalid companion frame count"));
            Frame[] frames = new Frame[source.length()];
            for (int i = 0; i < frames.length; i++) {
                JSONArray rect = source.getJSONObject(i).getJSONArray("sourceRect");
                if (rect.length() != 4) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物帧坐标无效", "Invalid companion frame coordinates"));
                Frame frame = new Frame(rect.getInt(0), rect.getInt(1), rect.getInt(2), rect.getInt(3));
                if (frame.x < 0 || frame.y < 0 || frame.width <= 0 || frame.height <= 0
                        || (long) frame.x + frame.width > width || (long) frame.y + frame.height > height)
                    throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物帧超出图片范围", "Companion frame is outside the image"));
                frames[i] = frame;
            }
            atlases.put(key, new Atlas(file, width, height, frames));
        }
        Map<String, Animation> animations = new LinkedHashMap<>();
        Map<String, Label> labels = new LinkedHashMap<>();
        JSONObject animationRows = document.getJSONObject("states");
        for (String required : STATES) if (!animationRows.has(required)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("缺少宠物动作：", "Missing companion action: ") + required);
        if (animationRows.length() > 128) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作过多", "Too many companion actions"));
        for (Iterator<String> keys = animationRows.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作名称无效", "Invalid companion action name"));
            JSONObject row = animationRows.getJSONObject(key);
            JSONObject paired = row.optJSONObject("labelLocales");
            if (paired != null) labels.put(key, new Label(label(paired, "zh"), label(paired, "en")));
            String atlasKey = row.getString("atlas");
            Atlas atlas = atlases.get(atlasKey);
            if (atlas == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作引用了不存在的图片", "Companion action references a missing image"));
            JSONArray sequence = row.getJSONArray("frames"), timing = row.getJSONArray("durationsMs");
            if (sequence.length() == 0 || sequence.length() > 128 || sequence.length() != timing.length())
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作帧与时长不匹配", "Companion action frames and durations do not match"));
            Frame[] frames = new Frame[sequence.length()];
            int[] durations = new int[sequence.length()];
            long durationTotal = 0;
            for (int i = 0; i < frames.length; i++) {
                int index = sequence.getInt(i);
                if (index < 0 || index >= atlas.frames.length) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作帧编号无效", "Invalid companion frame index"));
                durations[i] = timing.getInt(i);
                if (durations[i] <= 0 || durations[i] > 10000 || (durationTotal += durations[i]) > 30000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作时长无效", "Invalid companion action duration"));
                frames[i] = atlas.frames[index];
            }
            String facing = row.optString("facing", key.startsWith("peek") ? "none" : version == 1 ? "left" : "front");
            if (!java.util.Set.of("left", "right", "front", "none").contains(facing)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物朝向无效", "Invalid companion direction"));
            animations.put(key, new Animation(atlasKey, frames, durations, facing, row.optBoolean("mirrorSafe", version == 1),
                row.optString("leftState", key), row.optString("rightState", key)));
        }
        for (Animation animation : animations.values()) {
            if (!animations.containsKey(animation.leftState) || !animations.containsKey(animation.rightState))
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物朝向引用的动作不存在", "Companion direction references a missing action"));
        }
        Map<String, String[]> variants = new LinkedHashMap<>();
        JSONObject groups = document.optJSONObject("variants");
        if (groups != null) for (Iterator<String> keys = groups.keys(); keys.hasNext();) {
            String group = keys.next();
            JSONArray names = groups.getJSONArray(group);
            if (names.length() < 1 || names.length() > 16) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作变体数量无效", "Invalid companion action variant count"));
            String[] namesArray = new String[names.length()];
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < namesArray.length; i++) {
                namesArray[i] = names.getString(i);
                if (!animations.containsKey(namesArray[i]) || !seen.add(namesArray[i])) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物动作变体无效", "Invalid companion action variant"));
            }
            variants.put(group, namesArray);
        }
        Map<String, Effect> effects = new LinkedHashMap<>();
        JSONObject effectRows = document.optJSONObject("effects");
        if (effectRows != null) {
            if (effectRows.length() > 8) throw new IllegalArgumentException(NativeLocale.text("宠物特效数量无效", "Invalid companion effect count"));
            for (Iterator<String> keys = effectRows.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!animations.containsKey(key)) throw new IllegalArgumentException(NativeLocale.text("宠物特效引用了不存在的动作", "Companion effect references a missing action"));
                JSONObject row = effectRows.getJSONObject(key);
                String atlasKey = row.getString("atlas"); Atlas atlas = atlases.get(atlasKey);
                if (atlas == null) throw new IllegalArgumentException(NativeLocale.text("宠物特效引用了不存在的图片", "Companion effect references a missing image"));
                JSONArray sequence = row.getJSONArray("frames"), timing = row.getJSONArray("durationsMs");
                if (sequence.length() == 0 || sequence.length() > 128 || sequence.length() != timing.length())
                    throw new IllegalArgumentException(NativeLocale.text("宠物特效帧与时长不匹配", "Companion effect frames and durations do not match"));
                Frame[] frames = new Frame[sequence.length()]; int[] durations = new int[sequence.length()];
                int delay = row.getInt("delayMs"); long total = delay;
                if (delay < 0 || delay > 10000) throw new IllegalArgumentException(NativeLocale.text("宠物特效延迟无效", "Invalid companion effect delay"));
                for (int i = 0; i < frames.length; i++) {
                    int index = sequence.getInt(i); durations[i] = timing.getInt(i);
                    if (index < 0 || index >= atlas.frames.length || durations[i] < 1 || durations[i] > 10000 || (total += durations[i]) > 30000)
                        throw new IllegalArgumentException(NativeLocale.text("宠物特效帧或时长无效", "Invalid companion effect frame or duration"));
                    frames[i] = atlas.frames[index];
                }
                effects.put(key, new Effect(new Animation(atlasKey, frames, durations, "front", false, key, key), delay));
            }
        }
        JSONObject rendering = document.optJSONObject("rendering");
        boolean pixelArt = rendering != null && "pixel".equals(rendering.optString("style"));
        return new PetSpriteSpec(atlases, animations, variants, labels, effects, pixelArt, PetPersona.parse(document.optJSONObject("persona")));
    }

    private static String label(JSONObject row, String language) throws Exception {
        String value = row.getString(language).trim();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 80 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(NativeLocale.text("宠物动作文字无效", "Invalid companion action label"));
        return value;
    }

    static final class Label {
        final String zh, en;
        Label(String zh, String en) { this.zh = zh; this.en = en; }
        String localized() { return NativeLocale.text(zh, en); }
    }
    static final class Effect {
        final Animation animation; final int delayMs;
        Effect(Animation animation, int delayMs) { this.animation = animation; this.delayMs = delayMs; }
        long durationMs() { return delayMs + animation.durationMs; }
    }

    static final class Atlas {
        final String file;
        final int width, height;
        final Frame[] frames;
        Atlas(String file, int width, int height, Frame[] frames) {
            this.file = file; this.width = width; this.height = height; this.frames = frames;
        }
    }

    static final class Frame {
        final int x, y, width, height;
        Frame(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    static final class Animation {
        final String atlasKey;
        final Frame[] frames;
        final int[] durations;
        final int maxWidth, maxHeight;
        final String facing, leftState, rightState;
        final boolean mirrorSafe;
        final long durationMs;

        Animation(String atlasKey, Frame[] frames, int[] durations, String facing, boolean mirrorSafe, String leftState, String rightState) {
            this.facing = facing; this.mirrorSafe = mirrorSafe; this.leftState = leftState; this.rightState = rightState;
            long duration = 0; for (int value : durations) duration += value; this.durationMs = duration;
            this.atlasKey = atlasKey; this.frames = frames; this.durations = durations;
            int width = 0, height = 0;
            for (Frame frame : frames) { width = Math.max(width, frame.width); height = Math.max(height, frame.height); }
            maxWidth = width; maxHeight = height;
        }

        boolean mirrored(boolean facingLeft) {
            return mirrorSafe && (facingLeft && "right".equals(facing) || !facingLeft && "left".equals(facing));
        }
        Animation withTotalDuration(int totalMs) {
            int[] timing = new int[frames.length];
            java.util.Arrays.fill(timing, Math.max(1, totalMs / frames.length));
            timing[timing.length - 1] += Math.max(0, totalMs - timing[0] * frames.length);
            return new Animation(atlasKey, frames, timing, facing, mirrorSafe, leftState, rightState);
        }
        int frameAt(long elapsedMs) {
            long end = 0;
            for (int i = 0; i < frames.length - 1; i++) {
                end += durations[i];
                if (elapsedMs < end) return i;
            }
            return frames.length - 1;
        }

        long nextFrameDelay(long elapsedMs) {
            int frame = frameAt(elapsedMs);
            if (frame == frames.length - 1) return 0;
            long end = 0;
            for (int i = 0; i <= frame; i++) end += durations[i];
            return end - elapsedMs;
        }

        float scaleToFit(float width, float height) {
            return Math.min(width / maxWidth, height / maxHeight);
        }
    }
}

package io.droyagent.app;

import androidx.annotation.Keep;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Worker-owned Qwen3 CustomVoice context; model resources are downloaded separately. */
final class QwenTts implements LocalTts {
    static final int SAMPLE_RATE = 24000;
    private long handle;

    static JSONArray voices() throws Exception {
        JSONArray result = new JSONArray();
        String[] ids = {"Serena", "Vivian", "Uncle_Fu"};
        String[] names = {io.droyagent.app.NativeLocale.text("Serena · 温柔女声", "Serena · Gentle female voice"), io.droyagent.app.NativeLocale.text("Vivian · 明亮女声", "Vivian · Bright female voice"), io.droyagent.app.NativeLocale.text("Uncle Fu · 低沉男声", "Uncle Fu · Deep male voice")};
        for (int i = 0; i < ids.length; i++) result.put(new JSONObject()
            .put("id", ids[i]).put("voiceId", ids[i]).put("provider", "qwen3")
            .put("name", names[i]).put("language", "zh-CN").put("networkRequired", false));
        return result;
    }

    static String validateVoice(String value) {
        String voice = value == null || value.isEmpty() ? "Serena" : value;
        if (!voice.equals("Serena") && !voice.equals("Vivian") && !voice.equals("Uncle_Fu"))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("所选 Qwen3-TTS 音色不可用", "The selected Qwen3-TTS voice is unavailable"));
        return voice;
    }

    QwenTts(File directory, BooleanSupplier cancelled) {
        check(cancelled);
        System.loadLibrary("droy_qwen_tts");
        handle = nativeLoad(directory.getAbsolutePath());
        if (cancelled.getAsBoolean()) { close(); throw new CancellationException(); }
    }

    @Override public int sampleRate() { return SAMPLE_RATE; }

    @Override public void generate(String text, String voiceId, float speed, BooleanSupplier cancelled,
                                   Predicate<float[]> output) {
        if (handle == 0) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("Qwen3-TTS 已关闭", "Qwen3-TTS is disabled"));
        if (speed != 1f) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("Qwen3-TTS 当前仅支持标准语速", "Qwen3-TTS currently supports only normal speed"));
        String voice = validateVoice(voiceId).toLowerCase(Locale.ROOT);
        for (String part : sentences(text)) {
            check(cancelled);
            nativeGenerate(handle, part.getBytes(StandardCharsets.UTF_8), voice, new AudioCallback(cancelled, output));
            check(cancelled);
        }
    }

    static java.util.List<String> sentences(String text) {
        if (text == null || text.isBlank() || text.length() > 4000)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("朗读内容为空或过长，请分句播放", "The text is empty or too long. Please play it in smaller sentences"));
        java.util.List<String> result = new java.util.ArrayList<>();
        int start = 0, count = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp); count++;
            if (count >= 80 || "。！？!?\n".indexOf(cp) >= 0) {
                String part = text.substring(start, offset).trim();
                if (!part.isEmpty()) result.add(part);
                start = offset; count = 0;
            }
        }
        String tail = text.substring(start).trim();
        if (!tail.isEmpty()) result.add(tail);
        return result;
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }

    @Keep private static final class AudioCallback {
        private final BooleanSupplier cancelled;
        private final Predicate<float[]> output;
        AudioCallback(BooleanSupplier cancelled, Predicate<float[]> output) {
            this.cancelled = cancelled; this.output = output;
        }
        @Keep public boolean accept(float[] samples) {
            return !cancelled.getAsBoolean() && output.test(samples);
        }
    }

    @Override public void close() {
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }

    @Keep private static native long nativeLoad(String directory);
    @Keep private static native void nativeGenerate(long handle, byte[] text, String speaker, AudioCallback callback);
    @Keep private static native void nativeClose(long handle);
}

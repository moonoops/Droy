package io.droyagent.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Quality and embedding checks shared by enrollment and wake verification. */
final class VoiceEnrollment {
    static final float MATCH_THRESHOLD = 0.5f;
    static final int COUNT = 5;
    private final List<float[]> samples = new ArrayList<>();
    private final float[] original;
    private boolean cancelled, committed;

    VoiceEnrollment(float[] original) { this.original = original == null ? null : normalize(original); }

    synchronized void add(float[] embedding) {
        if (cancelled) throw new CancellationException(io.droyagent.app.NativeLocale.text("录入已取消", "Enrollment cancelled"));
        if (committed) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("录入已保存", "Enrollment saved"));
        float[] value = normalize(embedding);
        if (original != null && similarity(original, value) < MATCH_THRESHOLD)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请由声纹主人继续录入", "Please ask the voice owner to continue enrollment"));
        if (!samples.isEmpty() && similarity(centroid(), value) < MATCH_THRESHOLD)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("声音与前面的录音不同，请由本人再读一次", "This voice differs from the previous recording. Please ask the same person to read again"));
        if (samples.size() >= COUNT) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("录入已完成", "Enrollment complete"));
        samples.add(value);
    }

    synchronized int size() { return samples.size(); }
    synchronized boolean complete() { return size() == COUNT; }
    synchronized void cancel() { cancelled = true; }

    interface Commit { void write(float[] embedding) throws Exception; }

    synchronized boolean commit(Commit writer) throws Exception {
        if (cancelled || committed || !complete()) return false;
        writer.write(centroid());
        committed = true;
        return true;
    }

    synchronized float[] centroid() {
        if (samples.isEmpty()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("尚未录入声音", "No voice has been enrolled"));
        float[] result = new float[samples.get(0).length];
        for (float[] sample : samples) {
            if (sample.length != result.length) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("声纹模型发生变化，请重新录入", "The voiceprint model has changed. Please enroll again"));
            for (int i = 0; i < result.length; i++) result[i] += sample[i];
        }
        return normalize(result);
    }

    static float[] normalize(float[] value) {
        if (value == null || value.length == 0) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("声纹数据为空", "Voiceprint data is empty"));
        double squared = 0;
        for (float part : value) {
            if (!Float.isFinite(part)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("声纹数据无效", "Invalid voiceprint data"));
            squared += part * part;
        }
        if (squared <= 0) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未能识别有效声音", "No usable speech was detected"));
        float[] result = value.clone();
        double length = Math.sqrt(squared);
        for (int i = 0; i < result.length; i++) result[i] /= length;
        return result;
    }

    static float similarity(float[] left, float[] right) {
        if (left.length != right.length) return -1;
        double product = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) {
            product += left[i] * right[i]; a += left[i] * left[i]; b += right[i] * right[i];
        }
        return a == 0 || b == 0 ? -1 : (float)(product / Math.sqrt(a * b));
    }

    static float[] trimAndCheck(float[] samples, int rate) {
        int block = rate / 50, first = -1, last = 0, voiced = 0;
        for (int start = 0; start < samples.length; start += block) {
            int end = Math.min(start + block, samples.length);
            double energy = 0;
            for (int i = start; i < end; i++) energy += samples[i] * samples[i];
            if (energy / Math.max(1, end - start) > 0.000009) {
                if (first < 0) first = start;
                last = end; voiced += end - start;
            }
        }
        if (first < 0 || voiced < rate * 8 / 10)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("说得有点短，请完整读一句", "That was a little short. Please read a complete sentence"));
        first = Math.max(0, first - block * 5); last = Math.min(samples.length, last + block * 5);
        double energy = 0; int clipped = 0;
        for (int i = first; i < last; i++) {
            energy += samples[i] * samples[i];
            if (Math.abs(samples[i]) >= 0.98f) clipped++;
        }
        if (Math.sqrt(energy / Math.max(1, last - first)) < 0.004)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("声音有点小，请再读一次", "Your voice is a little quiet. Please read again"));
        if (clipped > (last - first) / 20)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("声音过大，请离手机远一点", "Your voice is too loud. Please move a little farther from the phone"));
        return Arrays.copyOfRange(samples, first, last);
    }

    static String[] prompts(String chinese, String english) {
        if (!chinese.isBlank() && !english.isBlank()) return new String[] {
            chinese + "，今天有什么安排？", chinese + "，请告诉我现在的天气。",
            english + ", what is on my schedule?", english + ", please tell me the time.",
            chinese + "，我正在用自然的声音说话。"
        };
        if (!chinese.isBlank()) return new String[] {
            chinese + "，今天有什么安排？", chinese + "，请告诉我现在的天气。",
            chinese + "，帮我看一下这张图片。", chinese + "，我可以自然地停顿。",
            chinese + "，以后请听我的声音。"
        };
        if (!english.isBlank()) return new String[] {
            english + ", what is on my schedule?", english + ", please tell me the time.",
            english + ", help me look at this picture.", english + ", I can speak naturally.",
            english + ", please listen to my voice."
        };
        throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请先设置一个唤醒词", "Please set a wake word first"));
    }
}

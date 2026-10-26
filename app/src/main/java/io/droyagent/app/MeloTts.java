package io.droyagent.app;

import androidx.annotation.Keep;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import kotlin.jvm.functions.Function1;

final class MeloTts implements LocalTts {
    static final int SAMPLE_RATE = 44100;
    private final OfflineTts engine;

    // sherpa JNI requires this typed method; Java lambdas expose only invoke(Object).
    @Keep
    static final class AudioCallback implements Function1<float[], Integer> {
        private final BooleanSupplier cancelled;
        private final Predicate<float[]> output;
        private boolean stopped;
        private Throwable failure;
        AudioCallback(BooleanSupplier cancelled, Predicate<float[]> output) { this.cancelled = cancelled; this.output = output; }
        @Override public Integer invoke(float[] samples) {
            if (stopped) return 0;
            try { stopped = cancelled.getAsBoolean() || !output.test(samples); }
            catch (RuntimeException | AssertionError caught) { failure = caught; stopped = true; }
            return stopped ? 0 : 1;
        }
        void rethrowFailure() {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof AssertionError assertion) throw assertion;
        }
    }

    static JSONArray voices() throws Exception {
        return new JSONArray().put(new JSONObject().put("id", "zh").put("voiceId", "zh").put("provider", "melo")
            .put("name", io.droyagent.app.NativeLocale.text("中文女声", "Chinese female voice")).put("language", "zh-CN").put("networkRequired", false));
    }

    static void validateVoice(String voiceId) {
        if (!voiceId.isEmpty() && !voiceId.equals("zh")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("所选 MeloTTS 音色不可用", "The selected MeloTTS voice is unavailable"));
    }

    MeloTts(File directory, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(new File(directory, "model.onnx").getAbsolutePath());
        vits.setTokens(new File(directory, "tokens.txt").getAbsolutePath());
        vits.setLexicon(new File(directory, "lexicon.txt").getAbsolutePath());
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits); model.setNumThreads(2); model.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model); config.setMaxNumSentences(1);
        config.setRuleFsts(new File(directory, "phone.fst").getAbsolutePath() + ","
            + new File(directory, "date.fst").getAbsolutePath() + "," + new File(directory, "number.fst").getAbsolutePath());
        engine = new OfflineTts(null, config);
        if (engine.sampleRate() != SAMPLE_RATE || engine.numSpeakers() != 1) {
            engine.release(); throw new IOException(io.droyagent.app.NativeLocale.text("MeloTTS 模型格式不匹配", "MeloTTS model format does not match"));
        }
    }

    @Override public int sampleRate() { return SAMPLE_RATE; }

    @Override public void generate(String text, String voiceId, float speed, BooleanSupplier cancelled, Predicate<float[]> output) {
        validateVoice(voiceId);
        int offset = 0;
        while (offset < text.length()) {
            check(cancelled);
            int end = offset;
            while (end < text.length() && end - offset < 160) {
                char value = text.charAt(end++);
                if ("。！？!?\n".indexOf(value) >= 0) break;
            }
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String sentence = text.substring(offset, end); offset = end;
            if (sentence.isBlank()) continue;
            AudioCallback callback = new AudioCallback(cancelled, output);
            engine.generateWithCallback(sentence, 0, speed, callback);
            callback.rethrowFailure(); check(cancelled);
            if (callback.stopped) return;
        }
    }

    @Override public void close() { engine.release(); }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException(io.droyagent.app.NativeLocale.text("朗读已停止", "Speech stopped"));
    }
}

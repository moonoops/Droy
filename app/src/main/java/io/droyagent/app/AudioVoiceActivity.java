package io.droyagent.app;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.File;

/** One Silero state per ASR consumer, never applied to the shared PCM source. */
final class AudioVoiceActivity implements AudioTranscriptionSegments.Detector, AutoCloseable {
    private final Vad detector;

    AudioVoiceActivity(File model) {
        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel(model.getAbsolutePath()); silero.setWindowSize(AudioTranscriptionSegments.WINDOW);
        VadModelConfig config = new VadModelConfig();
        config.setSileroVadModelConfig(silero); config.setSampleRate(AudioTranscriptionSegments.RATE);
        config.setNumThreads(1); config.setProvider("cpu");
        detector = new Vad(null, config);
    }

    @Override public boolean speech(float[] window) {
        // Feed every frame to keep the recurrent model state intact. A click can
        // leave its probability elevated even after the actual signal ends.
        float probability = detector.compute(window);
        double sum = 0, squares = 0;
        for (float sample : window) { sum += sample; squares += sample * sample; }
        double mean = sum / window.length;
        double power = squares / window.length - mean * mean;
        // -60 dBFS excludes effectively silent frames (and DC offset), without
        // treating loudness as proof of speech. Silero must also agree.
        return probability >= .5f && power >= .000001;
    }
    @Override public void close() { detector.release(); }
}

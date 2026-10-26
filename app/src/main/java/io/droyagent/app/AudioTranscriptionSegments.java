package io.droyagent.app;

import java.util.Arrays;

/** VAD framing and bounded decode chunks; decode chunks retain one utterance identity. */
final class AudioTranscriptionSegments {
    interface Consumer {
        void accept(float[] samples, int sampleRate, long startMs, long endMs) throws Exception;
        default void accept(float[] samples, int sampleRate, long startMs, long endMs, boolean endOfUtterance) throws Exception {
            if (samples.length > 0) accept(samples, sampleRate, startMs, endMs);
        }
    }
    interface Detector { boolean speech(float[] window) throws Exception; }
    static final int RATE = 16000, WINDOW = 512;
    private static final int PRE_ROLL = WINDOW * 6, END_SILENCE = WINDOW * 23, MIN_SPEECH = WINDOW * 3;
    private final int inputRate;
    private final float[] pending, preRoll = new float[PRE_ROLL + MIN_SPEECH], window = new float[WINDOW];
    private final Consumer consumer;
    private final Detector detector;
    private int size, preSize, windowSize, quietSamples, onsetSamples;
    private long consumed, segmentStart, inputSamples, outputSamples;
    private float previousSample;
    private boolean speech, chunkSpeech, utteranceOpen;

    AudioTranscriptionSegments(int sampleRate, int maximumSeconds, Consumer consumer, Detector detector) {
        if (sampleRate < 8000 || sampleRate > 192000 || maximumSeconds < 1 || maximumSeconds > 30)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("无效的分段格式", "Invalid segment format"));
        inputRate = sampleRate;
        pending = new float[Math.multiplyExact(RATE, maximumSeconds)];
        this.consumer = consumer; this.detector = detector;
    }

    void accept(float[] samples) throws Exception {
        // Keep resampling state across PCM reads so arbitrary read boundaries do
        // not duplicate/drop samples or reset Silero's recurrent history.
        for (float value : samples) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("音频包含无效采样", "Audio contains invalid samples"));
            while (outputSamples * (double) inputRate / RATE <= inputSamples) {
                double at = outputSamples * (double) inputRate / RATE;
                float sample = inputSamples == 0 ? value
                    : previousSample + (value - previousSample) * (float) (at - inputSamples + 1);
                window[windowSize++] = sample; outputSamples++;
                if (windowSize == WINDOW) { process(WINDOW); windowSize = 0; }
            }
            previousSample = value; inputSamples++;
        }
    }

    void finish() throws Exception {
        if (windowSize > 0) {
            Arrays.fill(window, windowSize, WINDOW, 0);
            process(windowSize); windowSize = 0;
        }
        flush(true);
    }

    private void process(int count) throws Exception {
        boolean voiced = detector.speech(window);
        if (!speech) {
            // Require consecutive evidence. Scattered clicks must not add up to
            // an utterance just because they occur before the silence timeout.
            onsetSamples = voiced ? onsetSamples + count : 0;
            if (onsetSamples < MIN_SPEECH) {
                remember(window, count, PRE_ROLL + onsetSamples); consumed += count; return;
            }
            speech = true; segmentStart = consumed - preSize;
            append(preRoll, preSize); preSize = 0;
        }
        if (voiced) { quietSamples = 0; chunkSpeech = true; }
        else quietSamples += count;
        append(window, count); consumed += count;
        if (quietSamples >= END_SILENCE) flush(true);
    }

    private void append(float[] values, int count) throws Exception {
        int offset = 0;
        while (offset < count) {
            int length = Math.min(count - offset, pending.length - size);
            System.arraycopy(values, offset, pending, size, length);
            size += length; offset += length;
            if (size == pending.length) {
                emit(false, segmentStart + size);
                segmentStart += size; size = 0; chunkSpeech = false;
                // A split inside this frame still belongs to voiced audio.
                if (offset < count && quietSamples == 0) chunkSpeech = true;
            }
        }
    }

    private void remember(float[] values, int count, int limit) {
        int keep = Math.min(preSize, limit - count);
        if (keep > 0) System.arraycopy(preRoll, preSize - keep, preRoll, 0, keep);
        System.arraycopy(values, 0, preRoll, keep, count); preSize = keep + count;
    }

    private void emit(boolean end, long endSample) throws Exception {
        if (chunkSpeech && size > 0) {
            consumer.accept(Arrays.copyOf(pending, size), RATE, segmentStart * 1000L / RATE,
                endSample * 1000L / RATE, end);
            utteranceOpen = !end;
        } else if (end && utteranceOpen) {
            consumer.accept(new float[0], RATE, endSample * 1000L / RATE, endSample * 1000L / RATE, true);
            utteranceOpen = false;
        }
    }

    private void flush(boolean end) throws Exception {
        emit(end, consumed);
        size = 0; quietSamples = 0; onsetSamples = 0; speech = false; chunkSpeech = false; preSize = 0;
        segmentStart = consumed;
    }

    static String cleanText(String text) {
        String value = text.replaceAll("<\\|[^|]*\\|>", "").strip();
        return value.codePoints().anyMatch(Character::isLetterOrDigit) ? value : "";
    }

    static float[] pcm16(byte[] bytes, int channels) {
        if (channels < 1 || channels > 8 || bytes.length % (channels * 2) != 0)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("PCM16 帧未对齐", "PCM16 frames are not aligned"));
        float[] mono = new float[bytes.length / (channels * 2)];
        for (int frame = 0, index = 0; frame < mono.length; frame++) {
            float sum = 0;
            for (int channel = 0; channel < channels; channel++, index += 2)
                sum += (short) ((bytes[index] & 255) | (bytes[index + 1] << 8)) / 32768f;
            mono[frame] = sum / channels;
        }
        return mono;
    }
}

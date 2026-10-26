package io.droyagent.app;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A synthesizer is created, used and released by its one speech worker. */
interface LocalTts extends AutoCloseable {
    int sampleRate();
    void generate(String text, String voiceId, float speed, BooleanSupplier cancelled, Predicate<float[]> output);
    @Override void close();
}

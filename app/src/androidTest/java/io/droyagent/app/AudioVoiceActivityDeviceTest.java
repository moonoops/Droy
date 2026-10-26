package io.droyagent.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real bundled JNI/model checks with caller-provisioned public fixtures; no microphone. */
@RunWith(AndroidJUnit4.class)
public final class AudioVoiceActivityDeviceTest {
    @Test public void quietNoiseAndIntermittentClicksNeverReachAsr() throws Exception {
        String model = InstrumentationRegistry.getArguments().getString("audioVadModel", "");
        assumeTrue("Provide the pinned app-private Silero model", !model.isEmpty());
        float[][] inputs = new float[4][16000 * 10];
        Random random = new Random(17);
        for (int i = 0; i < inputs[1].length; i++) inputs[1][i] = (random.nextFloat() * 2 - 1) * .003f;
        for (int i = 0; i < inputs[2].length; i += 4096) {
            inputs[2][i] = .7f;
            for (int j = 0; j < 32; j++) inputs[3][i + j] = j % 2 == 0 ? .7f : -.7f;
        }
        String[] names = {"digital silence", "quiet white noise", "single-sample clicks", "intermittent click bursts"};
        for (int i = 0; i < inputs.length; i++) {
            List<float[]> decoded = new ArrayList<>();
            try (AudioVoiceActivity detector = new AudioVoiceActivity(new File(model))) {
                AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 6,
                    (samples, rate, from, to) -> decoded.add(samples), detector);
                segments.accept(inputs[i]); segments.finish();
            }
            assertTrue(names[i] + " was sent to ASR", decoded.isEmpty());
        }
    }

    @Test public void quietAndShortPublicSpeechStillReachAsr() throws Exception {
        String model = InstrumentationRegistry.getArguments().getString("audioVadModel", "");
        String fixture = InstrumentationRegistry.getArguments().getString("audioAsrFixture", "");
        assumeTrue("Provide the pinned model and public 16 kHz Chinese speech WAV", !model.isEmpty() && !fixture.isEmpty());
        List<float[]> blocks = new ArrayList<>();
        AudioTranscriptionDecoder.decode(new File(fixture), (samples, rate) -> {
            assertEquals(16000, rate); blocks.add(Arrays.copyOf(samples, samples.length));
        }, () -> {});
        int length = blocks.stream().mapToInt(block -> block.length).sum();
        float[] quiet = new float[length]; int offset = 0;
        for (float[] block : blocks) { System.arraycopy(block, 0, quiet, offset, block.length); offset += block.length; }
        // A 320 ms voiced excerpt from the same pinned fixture exercises short
        // speech without making word/language assumptions in the acceptance gate.
        assertTrue("Fixture must contain the expected public utterance", quiet.length > 16000);
        float[] shortSpeech = Arrays.copyOfRange(quiet, 11520, 16640);
        for (int i = 0; i < quiet.length; i++) quiet[i] *= .1f;
        for (float[] input : new float[][]{quiet, shortSpeech}) {
            List<float[]> decoded = new ArrayList<>();
            try (AudioVoiceActivity detector = new AudioVoiceActivity(new File(model))) {
                AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 6,
                    (samples, rate, from, to) -> decoded.add(samples), detector);
                segments.accept(input); segments.finish();
            }
            assertFalse("Quiet or short public speech was removed", decoded.isEmpty());
        }
    }

    @Test public void silenceIsNotSpeechAndPublicSpeechHasOneEndpoint() throws Exception {
        String model = InstrumentationRegistry.getArguments().getString("audioVadModel", "");
        String fixture = InstrumentationRegistry.getArguments().getString("audioAsrFixture", "");
        assumeTrue("Provide the pinned app-private Silero model and a public speech WAV", !model.isEmpty() && !fixture.isEmpty());
        List<float[]> decoded = new ArrayList<>(); List<Boolean> endings = new ArrayList<>();
        try (AudioVoiceActivity detector = new AudioVoiceActivity(new File(model))) {
            for (int i = 0; i < 128; i++) assertFalse("Silero detected speech in digital silence", detector.speech(new float[512]));
        }
        try (AudioVoiceActivity detector = new AudioVoiceActivity(new File(model))) {
            final AudioTranscriptionSegments[] segments = {null};
            AudioTranscriptionDecoder.decode(new File(fixture), (samples, rate) -> {
                if (segments[0] == null) segments[0] = new AudioTranscriptionSegments(rate, 6, new AudioTranscriptionSegments.Consumer() {
                    @Override public void accept(float[] values, int sampleRate, long from, long to) { fail("Boundary-aware consumer required"); }
                    @Override public void accept(float[] values, int sampleRate, long from, long to, boolean end) {
                        if (values.length > 0) decoded.add(values); endings.add(end);
                    }
                }, detector);
                segments[0].accept(samples);
            }, () -> {});
            assertNotNull(segments[0]); segments[0].finish();
        }
        assertFalse("Silero removed all speech from the public fixture", decoded.isEmpty());
        assertTrue("The last decode chunk must close its utterance", endings.get(endings.size() - 1));
    }
}

package io.droyagent.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class AudioTranscriptionSegmentsTest {
    private static final AudioTranscriptionSegments.Detector SPEECH = window -> {
        for (float sample : window) if (sample > .05f) return true;
        return false;
    };

    @Test public void boundsContinuousSpeechAndKeepsActualTime() throws Exception {
        List<long[]> times = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 2,
            (samples, rate, from, to) -> { assertEquals(16000, rate); assertTrue(samples.length <= 32000); times.add(new long[]{from, to}); }, SPEECH);
        segments.accept(speech(80000)); segments.finish();
        assertEquals(3, times.size());
        assertArrayEquals(new long[]{0,2000}, times.get(0));
        assertArrayEquals(new long[]{4000,5000}, times.get(2));
    }

    @Test public void highEnergyNonSpeechDoesNotDecode() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(48000, 2,
            (samples, rate, from, to) -> sizes.add(samples.length), window -> false);
        segments.accept(speech(48000 * 8)); segments.finish();
        assertTrue(sizes.isEmpty());
    }

    @Test public void scatteredPositiveFramesNeverAccumulateIntoSpeech() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 2,
            (samples, rate, from, to) -> sizes.add(samples.length), SPEECH);
        for (int i = 0; i < 80; i++) {
            segments.accept(speech(512 * 2));
            segments.accept(new float[512 * 4]);
        }
        segments.finish();
        assertTrue("Disconnected 64 ms bursts must not become speech", sizes.isEmpty());
    }

    @Test public void shortSustainedReplyKeepsItsOnsetAndPreroll() throws Exception {
        List<float[]> decoded = new ArrayList<>(); List<long[]> times = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 2,
            (samples, rate, from, to) -> { decoded.add(samples); times.add(new long[]{from, to}); }, SPEECH);
        segments.accept(new float[512 * 20]);
        segments.accept(speech(512 * 3));
        segments.accept(new float[512 * 23]); segments.finish();
        assertEquals(1, decoded.size());
        assertArrayEquals(new long[]{448,1472}, times.get(0));
        assertArrayEquals(speech(512 * 3), Arrays.copyOfRange(decoded.get(0), 512 * 6, 512 * 9), 0);
    }

    @Test public void silenceKeepsOnlyShortPrerollAndAdvancesTime() throws Exception {
        List<long[]> times = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 2,
            (samples, rate, from, to) -> times.add(new long[]{from, to}), SPEECH);
        segments.accept(new float[32768]);
        segments.accept(speech(8192)); segments.accept(new float[16000]); segments.finish();
        assertEquals(1, times.size());
        assertEquals(1856, times.get(0)[0]);
        assertTrue(times.get(0)[1] >= 3200); assertTrue(times.get(0)[1] <= 3400);
    }

    @Test public void longSpeechHasOneBoundaryAcrossDecodeChunks() throws Exception {
        List<Boolean> endings = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 2, new AudioTranscriptionSegments.Consumer() {
            @Override public void accept(float[] samples, int rate, long from, long to) { fail("Expected boundary-aware callback"); }
            @Override public void accept(float[] samples, int rate, long from, long to, boolean end) { endings.add(end); }
        }, SPEECH);
        segments.accept(speech(80000)); segments.accept(new float[16000]); segments.finish();
        assertEquals(Arrays.asList(false, false, true), endings);
    }

    @Test public void exactSizeLimitEndsWithoutDecodingSilentTail() throws Exception {
        List<Integer> sizes = new ArrayList<>(); List<Boolean> endings = new ArrayList<>();
        AudioTranscriptionSegments segments = new AudioTranscriptionSegments(16000, 2, new AudioTranscriptionSegments.Consumer() {
            @Override public void accept(float[] samples, int rate, long from, long to) { fail("Expected boundary-aware callback"); }
            @Override public void accept(float[] samples, int rate, long from, long to, boolean end) {
                sizes.add(samples.length); endings.add(end);
            }
        }, SPEECH);
        // Two seconds is not an exact 512-sample VAD window. The partial speech
        // window must keep its real tail rather than be classified as silence.
        segments.accept(speech(32000)); segments.finish();
        assertEquals(Arrays.asList(32000, 0), sizes);
        assertEquals(Arrays.asList(false, true), endings);
    }

    @Test public void resamplingRetainsStateAcrossArbitraryReads() throws Exception {
        List<float[]> complete = new ArrayList<>(), split = new ArrayList<>();
        float[] input = speech(44100);
        AudioTranscriptionSegments a = new AudioTranscriptionSegments(44100, 2,
            (samples, rate, from, to) -> complete.add(samples), SPEECH);
        AudioTranscriptionSegments b = new AudioTranscriptionSegments(44100, 2,
            (samples, rate, from, to) -> split.add(samples), SPEECH);
        a.accept(input); a.finish();
        for (int i = 0; i < input.length; i += 773) b.accept(Arrays.copyOfRange(input, i, Math.min(input.length, i + 773)));
        b.finish();
        assertEquals(1, complete.size()); assertEquals(16000, complete.get(0).length);
        assertEquals(complete.size(), split.size()); assertArrayEquals(complete.get(0), split.get(0), 0);
    }

    @Test public void cleansModelTagsAndPunctuationWithoutDroppingShortReplies() {
        for (String invalid : new String[]{" ",".","。！？", "<|zh|><|NEUTRAL|><|Speech|><|woitn|>."})
            assertEquals("", AudioTranscriptionSegments.cleanText(invalid));
        for (String valid : new String[]{"嗯", "好", "一", "0", "The."})
            assertEquals(valid, AudioTranscriptionSegments.cleanText(valid));
        assertEquals("你好。", AudioTranscriptionSegments.cleanText("<|zh|><|NEUTRAL|>你好。"));
    }

    @Test public void stereoDownmixUsesSignedSamples() {
        assertArrayEquals(new float[]{-.000015258789f},
            AudioTranscriptionSegments.pcm16(new byte[]{-1,127,0,-128},2), .000001f);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsPartialPcmFrame() {
        AudioTranscriptionSegments.pcm16(new byte[3],1);
    }
    private static float[] speech(int count) { float[] samples = new float[count]; Arrays.fill(samples, .1f); return samples; }
}

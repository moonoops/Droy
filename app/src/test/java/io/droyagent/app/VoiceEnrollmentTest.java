package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceEnrollmentTest {
    @Test public void enrollmentRejectsAnotherSpeakerWithoutChangingAcceptedSamples() {
        VoiceEnrollment enrollment = new VoiceEnrollment(null);
        enrollment.add(new float[]{1, 0, 0});
        enrollment.add(new float[]{0.9f, 0.1f, 0});
        try { enrollment.add(new float[]{0, 0, 1}); fail("Different speaker was accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("本人")); }
        assertEquals(2, enrollment.size());
        assertTrue(VoiceEnrollment.similarity(new float[]{1, 0, 0}, enrollment.centroid()) > 0.99f);
    }

    @Test public void supplementChecksExistingOwnerBeforeItsFirstSample() {
        VoiceEnrollment enrollment = new VoiceEnrollment(new float[]{1, 0, 0});
        try { enrollment.add(new float[]{0, 1, 0}); fail("Non-owner was enrolled"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("主人")); }
        assertEquals(0, enrollment.size());
    }

    @Test public void onlyFiveAcceptedSamplesCompleteTheEnrollment() {
        VoiceEnrollment enrollment = new VoiceEnrollment(null);
        for (int i = 0; i < 4; i++) enrollment.add(new float[]{1, 0.01f * i, 0});
        assertFalse(enrollment.complete());
        enrollment.add(new float[]{1, 0, 0.01f});
        assertTrue(enrollment.complete());
        assertEquals(5, enrollment.size());
    }

    @Test public void silenceAndClippingNeverBecomeEnrollmentSamples() {
        assertInvalid(new float[32000], "短");
        float[] clipped = new float[32000]; java.util.Arrays.fill(clipped, 1f);
        assertInvalid(clipped, "过大");
    }

    @Test public void quietPaddingIsTrimmedWithoutCuttingTheSpeech() {
        float[] samples = new float[48000];
        for (int i = 16000; i < 32000; i++) samples[i] = (float)Math.sin(i * 0.03) * 0.08f;
        float[] trimmed = VoiceEnrollment.trimAndCheck(samples, 16000);
        assertTrue(trimmed.length >= 16000);
        assertTrue(trimmed.length < samples.length);
    }

    @Test public void bilingualPromptsKeepBothConfiguredWakeWords() {
        String[] prompts = VoiceEnrollment.prompts("你好小黑", "Hey Droy");
        assertEquals(5, prompts.length);
        assertTrue(java.util.Arrays.stream(prompts).anyMatch(value -> value.contains("你好小黑")));
        assertTrue(java.util.Arrays.stream(prompts).anyMatch(value -> value.contains("Hey Droy")));
    }

    @Test public void invalidEmbeddingIsRejectedInsteadOfBecomingAnOwner() {
        for (float[] values : new float[][]{new float[0], new float[]{0, 0}, new float[]{Float.NaN, 1}}) {
            try { VoiceEnrollment.normalize(values); fail("Invalid voice embedding was accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void cancelDuringFifthEmbeddingCannotReplaceTheOwner() throws Exception {
        VoiceEnrollment enrollment = new VoiceEnrollment(null);
        for (int i = 0; i < 4; i++) enrollment.add(new float[]{1, 0, 0});
        java.util.concurrent.CountDownLatch computing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch computed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean saved = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                computing.countDown(); computed.await();
                enrollment.add(new float[]{1, 0, 0});
                enrollment.commit(value -> saved.set(true));
            } catch (java.util.concurrent.CancellationException expected) { }
            catch (Throwable unexpected) { failure.set(unexpected); }
        });
        worker.start();
        assertTrue(computing.await(1, java.util.concurrent.TimeUnit.SECONDS));
        enrollment.cancel(); computed.countDown(); worker.join(1000);
        assertFalse(worker.isAlive()); assertNull(failure.get());
        assertFalse(saved.get()); assertEquals(4, enrollment.size());
    }

    @Test public void cancellationBetweenFinalSampleAndCommitLeavesTheOldTemplate() throws Exception {
        VoiceEnrollment enrollment = new VoiceEnrollment(null);
        for (int i = 0; i < 5; i++) enrollment.add(new float[]{1, 0, 0});
        java.util.concurrent.atomic.AtomicReference<String> profile = new java.util.concurrent.atomic.AtomicReference<>("original");
        enrollment.cancel();
        assertFalse(enrollment.commit(value -> profile.set("replacement")));
        assertEquals("original", profile.get());
    }

    private static void assertInvalid(float[] samples, String expectedText) {
        try { VoiceEnrollment.trimAndCheck(samples, 16000); fail("Invalid audio was accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(expectedText)); }
    }
}

package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android callbacks on an owned page; no model, microphone or other app interaction. */
@RunWith(AndroidJUnit4.class)
public final class NativeTaskQuiescenceDeviceTest {
    @Test public void cancelledLongHoldWaitsForItsRealSystemCallbackBeforeBecomingQuiet() throws Exception {
        gestureBoundary(false);
    }

    @Test public void cancelledSegmentedSwipeWaitsForItsHeldPointersBeforeBecomingQuiet() throws Exception {
        gestureBoundary(true);
    }

    private void gestureBoundary(boolean segmented) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        TestPageActivity activity = null;
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        Thread worker = null;
        try {
            automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
            try {
                activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } finally { automation.dropShellPermissionIdentity(); }
            AccessibilityRecovery.get(context).onAppOpened(false);
            DeviceUi.await("Enable the existing Droy accessibility service before running this fixture", 15000,
                    () -> DroyAccessibilityService.isConnected(context));
            AtomicInteger downs = new AtomicInteger(), ups = new AtomicInteger(), cancels = new AtomicInteger();
            TestPageActivity owned = activity;
            View surface = new View(owned) {
                @Override public boolean onTouchEvent(MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downs.incrementAndGet();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) ups.incrementAndGet();
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancels.incrementAndGet();
                    return true;
                }
            };
            instrumentation.runOnMainSync(() -> {
                surface.setBackgroundColor(Color.rgb(34, 62, 85));
                surface.setContentDescription("Owned native quiescence fixture");
                owned.setContentView(surface);
            });
            instrumentation.waitForIdleSync();
            DeviceUi.await("Owned surface not laid out", 5000, () -> surface.getWidth() > 100 && surface.getHeight() > 100);
            automation.waitForIdle(200, 5000);
            int[] position = new int[2];
            instrumentation.runOnMainSync(() -> surface.getLocationOnScreen(position));
            Rect area = new Rect(position[0], position[1], position[0] + surface.getWidth(), position[1] + surface.getHeight());
            AndroidUseExecutor executor = new AndroidUseExecutor(context);
            JSONObject snapshot = executor.execute("observe", new JSONObject(), false, () -> {});
            JSONObject arguments = new JSONObject().put("snapshotId", snapshot.getString("snapshotId"));
            if (segmented) arguments.put("startX", area.centerX()).put("startY", area.top + area.height() * 3 / 4)
                    .put("endX", area.centerX()).put("endY", area.top + area.height() / 4)
                    .put("motion", "natural").put("durationMs", 2000);
            else arguments.put("x", area.centerX()).put("y", area.centerY()).put("holdMs", 1500);

            NativeTaskControl control = new NativeTaskControl();
            String task = "fixture-" + UUID.randomUUID(), operation = UUID.randomUUID().toString();
            long epoch = control.open(task, operation).epoch();
            NativeTaskControl.Call call = control.admit(task, operation, epoch, "gesture-call", segmented ? "swipe" : "tap");
            CountDownLatch actualLease = new CountDownLatch(1);
            AtomicReference<CompletableFuture<?>> actual = new AtomicReference<>();
            AtomicReference<Throwable> result = new AtomicReference<>();
            Cancellation cancellation = new Cancellation() {
                @Override public void check() throws InterruptedException { call.check(); }
                @Override public void dispatched() throws InterruptedException { call.check(); call.dispatched(); }
                @Override public void track(String kind, String id, CompletableFuture<?> completion) {
                    call.track(kind, id, completion);
                    if (kind.equals(segmented ? "held_pointers" : "gesture") && actual.compareAndSet(null, completion)) {
                        actualLease.countDown();
                        try {
                            if (!releaseDispatch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Fixture did not release native dispatch");
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    }
                }
            };
            worker = new Thread(() -> {
                try { executor.execute(segmented ? "swipe" : "tap", arguments, false, cancellation); }
                catch (Throwable failure) { result.set(failure); }
                finally { call.finish(false); }
            }, "native-quiescence-fixture");
            worker.start();
            assertTrue("Actual Android dispatch lease was not registered", actualLease.await(8, TimeUnit.SECONDS));
            NativeTaskControl.State paused = control.change(task, operation, "pause", epoch, UUID.randomUUID().toString());
            control.cleanup(task, operation).complete(null);
            assertThrows(InterruptedException.class, call::check);
            assertThrows(NativeTaskControl.Rejected.class, () -> control.admit(task, operation, epoch, "late", "tap"));
            assertThrows(NativeTaskControl.Rejected.class, () -> control.admit(task, operation, paused.epoch(), "paused-write", "tap"));
            assertFalse("Actual callback/pointers are still outstanding", actual.get().isDone());
            assertFalse(control.status(task, operation).quiescent());
            assertTrue(control.status(task, operation).pending().stream().anyMatch(p -> p.kind().equals(segmented ? "held_pointers" : "gesture")));
            emit("fenced", control.status(task, operation));
            releaseDispatch.countDown();
            worker.join(10000);
            assertFalse("Native executor did not drain", worker.isAlive());
            assertTrue("Pause must reject the executing request", result.get() instanceof InterruptedException);
            DeviceUi.await("Actual native callbacks did not settle", 5000, () -> control.status(task, operation).quiescent());
            instrumentation.waitForIdleSync();
            assertTrue(actual.get().isDone());
            assertEquals("Exactly one owned gesture began", 1, downs.get());
            assertEquals("Owned pointer must be released", 1, ups.get());
            assertEquals("Cleanup must lift rather than abandon the pointer", 0, cancels.get());
            assertEquals("paused", control.status(task, operation).phase());
            assertTrue(control.status(task, operation).needsObservation());
            NativeTaskControl.State observing = control.change(task, operation, "resume", paused.epoch(), paused.pauseId());
            assertThrows(NativeTaskControl.Rejected.class, () -> control.admit(task, operation, observing.epoch(), "early-write", "tap"));
            assertThrows(NativeTaskControl.Rejected.class, () -> control.change(task, operation, "activate", observing.epoch(), observing.pauseId()));
            NativeTaskControl.Call observation = control.admit(task, operation, observing.epoch(), "fresh-observation", "observe");
            boolean observed = false;
            try {
                executor.execute("observe", new JSONObject(), false, new Cancellation() {
                    public void check() throws InterruptedException { observation.check(); }
                    public void track(String kind, String id, CompletableFuture<?> completion) { observation.track(kind, id, completion); }
                });
                observed = true;
            } finally { observation.finish(observed); }
            DeviceUi.await("Fresh observation callbacks did not finish", 5000,
                    () -> control.status(task, operation).quiescent());
            assertEquals("active", control.change(task, operation, "activate", observing.epoch(), observing.pauseId()).phase());
            emit("drained-and-observed", control.status(task, operation));
        } finally {
            releaseDispatch.countDown();
            if (worker != null) worker.join(12000);
            // Keep the owned target in front if Android has not confirmed cleanup.
            if (worker != null && worker.isAlive()) throw new AssertionError("Native worker is still active; owned fixture retained");
            if (activity != null) {
                TestPageActivity owned = activity;
                instrumentation.runOnMainSync(owned::finish);
            }
        }
    }

    @Test public void awaitingUserCaptureOwnerCancellationCompletesItsRealLeaseWithoutOpeningAudio() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        AudioCapture audio = AudioCapture.get(context);
        assertTrue("Do not disturb an existing capture", audio.uiStatus().isNull("active"));
        assertFalse("Do not disturb dictation", audio.dictationActive());
        SharedPreferences settings = ProductPermissions.preferences(context);
        boolean hadStopped = settings.contains(ProductPermissions.STOPPED), stopped = settings.getBoolean(ProductPermissions.STOPPED, false);
        boolean hadPlayback = settings.contains("allowAudioPlayback"), playback = settings.getBoolean("allowAudioPlayback", true);
        String task = "fixture-audio-" + UUID.randomUUID(), operation = UUID.randomUUID().toString(), id = null;
        try {
            instrumentation.runOnMainSync(() -> settings.edit().putBoolean(ProductPermissions.STOPPED, false).putBoolean("allowAudioPlayback", true).commit());
            NativeTaskControl control = new NativeTaskControl();
            long epoch = control.open(task, operation).epoch();
            id = audio.start(new JSONObject().put("source", "playback").put("scope", "system").put("saveFile", false),
                    task, UUID.randomUUID().toString()).getString("captureId");
            assertEquals("awaiting_user", audio.status(id, task).getString("state"));
            CompletableFuture<Void> capture = audio.completion(id, task);
            control.trackResource(task, operation, "audio_capture", id, capture);
            control.change(task, operation, "pause", epoch, UUID.randomUUID().toString());
            control.cleanup(task, operation).complete(null);
            assertFalse(capture.isDone());
            assertFalse(control.status(task, operation).quiescent());
            audio.stopOwner("unrelated-fixture-owner");
            assertFalse(capture.isDone());
            audio.stopOwner(task);
            capture.get(5, TimeUnit.SECONDS);
            assertTrue(control.status(task, operation).quiescent());
            JSONObject ended = audio.status(id, task);
            assertEquals("task_paused", ended.getString("reason"));
            assertEquals(0, ended.getLong("bytes"));
            assertFalse(ended.getBoolean("fileReady"));
            assertNull(audio.pendingAuthorization());
            emit("audio-awaiting-user-drained", control.status(task, operation));
        } finally {
            try { if (id != null) { audio.stopFromUi(id); audio.deleteFromUi(id); } }
            finally {
                SharedPreferences.Editor restore = settings.edit();
                if (hadStopped) restore.putBoolean(ProductPermissions.STOPPED, stopped); else restore.remove(ProductPermissions.STOPPED);
                if (hadPlayback) restore.putBoolean("allowAudioPlayback", playback); else restore.remove("allowAudioPlayback");
                instrumentation.runOnMainSync(restore::commit);
            }
        }
    }

    private static void emit(String boundary, NativeTaskControl.State state) {
        Bundle status = new Bundle();
        status.putString("stream", "\nDROY_NATIVE_QUIESCENCE " + boundary + " phase=" + state.phase()
                + " quiescent=" + state.quiescent() + " pending=" + state.pending() + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
}
